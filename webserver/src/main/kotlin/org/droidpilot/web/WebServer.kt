package org.droidpilot.web

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.readString
import kotlinx.serialization.json.*
import org.droidpilot.core.*
import java.io.Closeable

interface WebBackend {
    suspend fun configuration():PilotConfig
    suspend fun save(config:PilotConfig,revision:String):Boolean
    suspend fun diagnostics():JsonObject
    /** Null means applied; a message means visible Android interaction is required. */
    suspend fun apply():String?
    suspend fun pause()
}

class EmbeddedWebServer(host:String,port:Int,backend:WebBackend,credentials:suspend ()->WebCredentials?):Closeable {
    private val server=embeddedServer(CIO,host=host,port=port) {
        configureWeb(backend,credentials)
    }
    suspend fun run() {
        try { server.start(wait=false);awaitCancellation() } finally { close() }
    }
    override fun close() { server.stop(0,1000) }
}

fun Application.configureWeb(backend:WebBackend,credentials:suspend ()->WebCredentials?) {
    val authSlots=Semaphore(2)
    val sessions=WebSessions()
    val throttle=LoginThrottle()
    val cookieName="droidpilot_session"
    suspend fun signedIn(call:ApplicationCall):Boolean = sessions.valid(call.request.cookies[cookieName],credentials()?.let { digest(it.encode()) })
    fun ApplicationCall.sessionCookie(token:String,maxAge:Int) {
        response.cookies.append(Cookie(cookieName,token,path="/",maxAge=maxAge,httpOnly=true,
            secure=request.local.scheme=="https",extensions=mapOf("SameSite" to "Strict")))
    }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append(HttpHeaders.CacheControl,"no-store")
        call.response.headers.append("X-Content-Type-Options","nosniff")
        call.response.headers.append("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        val host=runCatching { java.net.URI("http://${call.request.headers[HttpHeaders.Host]}").host?.removeSurrounding("[","]") }.getOrNull()
        if(host==null || (host!="localhost" && !numericIp(host))) {
            call.respondText("Use a numeric phone IP address",status=HttpStatusCode.Forbidden);finish()
        }
    }
    intercept(ApplicationCallPipeline.Call) {
        val path=call.request.path()
        // Default-deny: Ktor also resolves encoded segments and repeated slashes.
        // Only these literal public paths may skip authentication.
        if(path !in setOf("/","/login","/login.js","/app.css","/api/login")) {
            if(!signedIn(call)) {
                if(path=="/admin" || path=="/app.js") call.respondRedirect("/login")
                else call.respondText("{\"error\":\"Sign in to continue.\"}",ContentType.Application.Json,HttpStatusCode.Unauthorized)
                finish()
            }
        }
    }
    routing {
        fun resource(path:String,name:String,type:ContentType) {
            get(path) {
                val text=object {}.javaClass.getResourceAsStream("/web/$name")?.bufferedReader()?.use { it.readText() } ?: error("Missing web asset $name")
                call.respondText(text,type)
            }
        }
        get("/") { call.respondRedirect(if(signedIn(call)) "/admin" else "/login") }
        resource("/login","login.html",ContentType.Text.Html)
        resource("/login.js","login.js",ContentType.Application.JavaScript)
        resource("/admin","index.html",ContentType.Text.Html)
        resource("/app.js","app.js",ContentType.Application.JavaScript)
        resource("/app.css","app.css",ContentType.Text.CSS)
        post("/api/login") {
            if(!call.acceptMutation()) return@post
            try {
                val body=withTimeout(5000) { call.receiveChannel().readRemaining(2049).readString() }
                if(body.toByteArray(Charsets.UTF_8).size>2048) { call.respondText("Login request too large",status=HttpStatusCode.PayloadTooLarge);return@post }
                val value=Json.parseToJsonElement(body).jsonObject
                require(value.keys==setOf("username","password")) { "Enter a username and password." }
                val user=value.getValue("username").jsonPrimitive
                val pass=value.getValue("password").jsonPrimitive
                require(user.isString && pass.isString && user.content.length<=64 && pass.content.length<=256) { "Invalid login request." }
                if(!throttle.allow() || !authSlots.tryAcquire()) {
                    call.response.headers.append(HttpHeaders.RetryAfter,"60")
                    call.respondText("Too many sign-in attempts. Try again in a minute.",status=HttpStatusCode.TooManyRequests);return@post
                }
                try {
                    val current=credentials()
                    val valid=withContext(Dispatchers.Default) { current?.verify(user.content,pass.content)==true }
                    if(!valid) {
                        delay(200)
                        call.respondText("Incorrect username or password.",status=HttpStatusCode.Unauthorized)
                    } else {
                        val token=sessions.issue(digest(current!!.encode()))
                        call.sessionCookie(token,8*60*60)
                        call.json(buildJsonObject { put("signedIn",true) })
                    }
                } finally { authSlots.release() }
            } catch(e:kotlinx.coroutines.CancellationException) { throw e } catch(e:IllegalArgumentException) {
                call.respondText("Enter a valid username and password.",status=HttpStatusCode.BadRequest)
            }
        }
        post("/api/logout") {
            if(!call.acceptMutation()) return@post
            sessions.revoke(call.request.cookies[cookieName]);call.sessionCookie("",0)
            call.json(buildJsonObject { put("signedOut",true) })
        }
            get("/api/config") {
                val config=backend.configuration()
                call.json(buildJsonObject { put("revision",config.revision());put("config",JsonObject(config.fields().mapValues { JsonPrimitive(it.value) })) })
            }
            get("/api/status") { call.json(backend.diagnostics()) }
            put("/api/config") {
                if(!call.acceptMutation()) return@put
                try {
                    val body=withTimeout(5000) { call.receiveChannel().readRemaining(16385).readString() }
                    if(body.toByteArray(Charsets.UTF_8).size>16384) { call.respondText("Configuration body too large",status=HttpStatusCode.PayloadTooLarge);return@put }
                    val value=Json.parseToJsonElement(body).jsonObject
                    require(value.keys==setOf("revision","config")) { "Expected revision and config" }
                    val revision=value.getValue("revision").jsonPrimitive.content
                    val fields=value.getValue("config").jsonObject.mapValues { (_,v)->v.jsonPrimitive.content }
                    val config=PilotConfig.fromFields(fields)
                    if(!backend.save(config,revision)) call.respondText("Configuration changed. Reload before saving.",status=HttpStatusCode.Conflict)
                    else call.json(buildJsonObject { put("saved",true);put("revision",config.revision()) })
                } catch(e:kotlinx.coroutines.CancellationException) { throw e } catch(e:IllegalArgumentException) {
                    call.respondText(e.message ?: "Invalid configuration",status=HttpStatusCode.BadRequest)
                }
            }
            post("/api/runtime/apply") {
                if(!call.acceptMutation()) return@post
                val error=backend.apply()
                if(error!=null) call.respondText(error,status=HttpStatusCode.Conflict)
                else call.json(buildJsonObject { put("applied",true) })
            }
            post("/api/runtime/pause") {
                if(!call.acceptMutation()) return@post
                backend.pause();call.json(buildJsonObject { put("paused",true) })
            }
    }
}
private suspend fun ApplicationCall.json(value:JsonObject) { respondText(value.toString(),ContentType.Application.Json) }
private suspend fun ApplicationCall.acceptMutation():Boolean {
    val origin=request.headers[HttpHeaders.Origin]
    val expected="http://${request.headers[HttpHeaders.Host]}"
    if(request.headers["X-DroidPilot-Request"]!="1" || (origin!=null && origin!=expected)) {
        respondText("Same-origin configuration request required",status=HttpStatusCode.Forbidden);return false
    }
    return true
}
