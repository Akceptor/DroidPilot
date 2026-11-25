package org.droidpilot.web

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Bounded, process-local sessions. Only token digests are retained. */
internal class WebSessions(
    private val clock:()->Long={System.nanoTime()/1_000_000},
    private val ttlMillis:Long=8*60*60*1000L,
    private val capacity:Int=64
) {
    private data class Session(val credentials:String,val issued:Long)
    private val sessions=LinkedHashMap<String,Session>()
    private val random=SecureRandom()
    @Synchronized fun issue(credentials:String):String {
        prune()
        while(sessions.size>=capacity) sessions.remove(sessions.keys.first())
        val token=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        sessions[digest(token)]=Session(credentials,clock())
        return token
    }
    @Synchronized fun valid(token:String?,credentials:String?):Boolean {
        prune()
        if(token==null || token.length!=43 || credentials==null) return false
        return sessions[digest(token)]?.credentials==credentials
    }
    @Synchronized fun revoke(token:String?) { if(token!=null && token.length==43) sessions.remove(digest(token)) }
    private fun prune() { val now=clock();sessions.entries.removeAll { now-it.value.issued>=ttlMillis } }
}

/** Global fixed window: constant memory, independent of attacker-controlled addresses. */
internal class LoginThrottle(
    private val clock:()->Long={System.nanoTime()/1_000_000},
    private val limit:Int=20,
    private val windowMillis:Long=60_000
) {
    private var window=clock()
    private var attempts=0
    @Synchronized fun allow():Boolean {
        val now=clock()
        if(now-window>=windowMillis) { window=now;attempts=0 }
        if(attempts>=limit) return false
        attempts++;return true
    }
}
internal fun digest(value:String):String=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
