package org.droidpilot.web

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.droidpilot.core.*
import org.junit.Assert.*
import org.junit.Test

class WebServerTest {
    private val credentials=WebCredentials.create("test-operator","test-password")
    private class Backend:WebBackend {
        var saved=PilotConfig()
        var active=false
        override suspend fun configuration()=saved
        override suspend fun save(config:PilotConfig,revision:String):Boolean {
            if(revision!=saved.revision()) return false
            saved=config;return true
        }
        override suspend fun diagnostics()=buildJsonObject { put("running",active) }
        override suspend fun apply():String? { if(saved.video && saved.cameraId!="@synthetic") return "Start the camera from Android";active=true;return null }
        override suspend fun pause() { active=false }
    }
    private suspend fun HttpClient.login(password:String="test-password")=post("/api/login") {
        header(HttpHeaders.Host,"localhost");header("X-DroidPilot-Request","1")
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject { put("username","test-operator");put("password",password) }.toString())
    }
    private fun cookie(response:HttpResponse)=response.headers[HttpHeaders.SetCookie]!!.substringBefore(';')
    @Test fun credentialHashRoundTrip() {
        assertTrue(credentials.verify("test-operator","test-password"))
        assertFalse(credentials.verify("test-operator","wrong"))
        assertFalse(credentials.encode().contains("test-password"))
        assertTrue(WebCredentials.decode(credentials.encode()).verify("test-operator","test-password"))
    }
    @Test fun publicLoginAndProtectedAdmin() = testApplication {
        val client=createClient { followRedirects=false }
        application { configureWeb(Backend(),{credentials}) }
        for(path in listOf("/login","/login.js","/app.css")) {
            val response=client.get(path) { header(HttpHeaders.Host,"localhost") }
            assertEquals(HttpStatusCode.OK,response.status)
            assertEquals("no-store",response.headers[HttpHeaders.CacheControl])
        }
        assertEquals("/login",client.get("/") { header(HttpHeaders.Host,"localhost") }.headers[HttpHeaders.Location])
        assertEquals("/login",client.get("/admin") { header(HttpHeaders.Host,"localhost") }.headers[HttpHeaders.Location])
        for(path in listOf("/api/config","/api/status")) {
            val response=client.get(path) { header(HttpHeaders.Host,"localhost") }
            assertEquals(HttpStatusCode.Unauthorized,response.status)
            assertNull(response.headers[HttpHeaders.WWWAuthenticate])
            assertTrue(response.bodyAsText().contains("Sign in"))
        }
        assertEquals(HttpStatusCode.Unauthorized,client.login("wrong").status)
        val login=client.login()
        assertEquals(HttpStatusCode.OK,login.status)
        val setCookie=login.headers[HttpHeaders.SetCookie]!!
        assertTrue(setCookie.contains("HttpOnly",true));assertTrue(setCookie.contains("SameSite=Strict",true))
        assertTrue(setCookie.contains("Path=/",true))
        val response=client.get("/admin") { header(HttpHeaders.Host,"localhost");header(HttpHeaders.Cookie,cookie(login)) }
        assertEquals(HttpStatusCode.OK,response.status)
        assertTrue(response.bodyAsText().contains("DroidPilot"))
    }
    @Test fun logoutAndCredentialChangesInvalidateSessions() = testApplication {
        val client=createClient { followRedirects=false }
        var current:WebCredentials?=credentials
        application { configureWeb(Backend(),{current}) }
        val session=cookie(client.login())
        suspend fun status()=client.get("/api/status") { header(HttpHeaders.Host,"localhost");header(HttpHeaders.Cookie,session) }.status
        assertEquals(HttpStatusCode.OK,status())
        assertEquals(HttpStatusCode.OK,client.post("/api/logout") {
            header(HttpHeaders.Host,"localhost");header(HttpHeaders.Cookie,session);header("X-DroidPilot-Request","1")
        }.status)
        assertEquals(HttpStatusCode.Unauthorized,status())
        val second=cookie(client.login())
        current=WebCredentials.create("test-operator","changed-password")
        assertEquals(HttpStatusCode.Unauthorized,client.get("/api/status") { header(HttpHeaders.Host,"localhost");header(HttpHeaders.Cookie,second) }.status)
    }
    @Test fun configurationValidationAndRevisionConflict() = testApplication {
        val client=createClient { followRedirects=false }
        val backend=Backend()
        application { configureWeb(backend,{credentials}) }
        val session=cookie(client.login())
        val original=backend.saved
        fun payload(config:PilotConfig,revision:String=original.revision())=buildJsonObject {
            put("revision",revision);put("config",JsonObject(config.fields().mapValues { JsonPrimitive(it.value) }))
        }.toString()
        suspend fun save(body:String)=client.put("/api/config") {
            header(HttpHeaders.Host,"localhost");header(HttpHeaders.Cookie,session);header("X-DroidPilot-Request","1")
            contentType(ContentType.Application.Json);setBody(body)
        }
        assertEquals(HttpStatusCode.Unauthorized,client.put("/api/config") { header(HttpHeaders.Host,"localhost");setBody(payload(original)) }.status)
        assertEquals(HttpStatusCode.OK,save(payload(original.copy(baud=57600))).status)
        assertEquals(57600,backend.saved.baud)
        assertEquals(HttpStatusCode.Conflict,save(payload(original)).status)
        val invalid=payload(backend.saved,backend.saved.revision()).replace("\"udpPort\":\"14550\"","\"udpPort\":\"70000\"")
        assertEquals(HttpStatusCode.BadRequest,save(invalid).status)
        assertEquals(HttpStatusCode.PayloadTooLarge,save("x".repeat(17000)).status)
    }
    @Test fun mutationsRequireSameOriginAndHeader() = testApplication {
        val client=createClient { followRedirects=false }
        val backend=Backend()
        application { configureWeb(backend,{credentials}) }
        assertEquals(HttpStatusCode.Forbidden,client.post("/api/login") { header(HttpHeaders.Host,"localhost");setBody("{}") }.status)
        assertEquals(HttpStatusCode.Forbidden,client.post("/api/login") {
            header(HttpHeaders.Host,"localhost");header("X-DroidPilot-Request","1");header(HttpHeaders.Origin,"http://evil.example");setBody("{}")
        }.status)
        val session=cookie(client.login())
        assertEquals(HttpStatusCode.Forbidden,client.post("/api/runtime/apply") { header(HttpHeaders.Host,"localhost");header(HttpHeaders.Cookie,session) }.status)
        assertEquals(HttpStatusCode.Forbidden,client.post("/api/runtime/apply") {
            header(HttpHeaders.Host,"localhost");header(HttpHeaders.Cookie,session);header("X-DroidPilot-Request","1");header(HttpHeaders.Origin,"http://evil.example")
        }.status)
        assertFalse(backend.active)
        suspend fun action(path:String)=client.post(path) {
            header(HttpHeaders.Host,"localhost");header(HttpHeaders.Cookie,session);header("X-DroidPilot-Request","1")
        }.status
        assertEquals(HttpStatusCode.OK,action("/api/runtime/apply"));assertTrue(backend.active)
        assertEquals(HttpStatusCode.OK,action("/api/runtime/pause"));assertFalse(backend.active)
        backend.saved=backend.saved.copy(video=true)
        assertEquals(HttpStatusCode.Conflict,action("/api/runtime/apply"))
    }
    @Test fun encodedAndRepeatedSlashRoutesCannotBypassAuthentication() = testApplication {
        val client=createClient { followRedirects=false }
        val backend=Backend()
        application { configureWeb(backend,{credentials}) }
        for(path in listOf("/%61pi/config","/%61pi/status","//api/config","//api/status","/%61dmin","//admin","/%61pp.js","//app.js")) {
            assertEquals(path,HttpStatusCode.Unauthorized,client.get { url { encodedPath=path };header(HttpHeaders.Host,"localhost") }.status)
        }
        for(path in listOf("/%61pi/runtime/apply","//api/runtime/apply")) {
            assertEquals(path,HttpStatusCode.Unauthorized,client.post { url { encodedPath=path };header(HttpHeaders.Host,"localhost");header("X-DroidPilot-Request","1") }.status)
        }
        assertFalse(backend.active)
    }
    @Test fun missingCredentialsFailClosedAndMalformedLoginIsRejected() = testApplication {
        val client=createClient { followRedirects=false }
        application { configureWeb(Backend(),{null}) }
        assertEquals(HttpStatusCode.Unauthorized,client.login().status)
        assertEquals(HttpStatusCode.BadRequest,client.post("/api/login") {
            header(HttpHeaders.Host,"localhost");header("X-DroidPilot-Request","1");contentType(ContentType.Application.Json);setBody("{}")
        }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge,client.post("/api/login") {
            header(HttpHeaders.Host,"localhost");header("X-DroidPilot-Request","1");contentType(ContentType.Application.Json);setBody("x".repeat(3000))
        }.status)
        assertEquals(HttpStatusCode.Forbidden,client.get("/login") { header(HttpHeaders.Host,"evil.example") }.status)
    }
}
