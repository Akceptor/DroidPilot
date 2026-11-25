package org.droidpilot.web

import org.junit.Assert.*
import org.junit.Test

class WebSessionsTest {
    @Test fun sessionsExpireRevokeAndRespectCapacity() {
        var now=100L
        val sessions=WebSessions(clock={now},ttlMillis=1000,capacity=2)
        val first=sessions.issue("credential-a")
        assertTrue(sessions.valid(first,"credential-a"))
        assertFalse(sessions.valid(first,"credential-b"))
        val second=sessions.issue("credential-a")
        val third=sessions.issue("credential-a")
        assertNotEquals(second,third)
        assertFalse(sessions.valid(first,"credential-a"))
        sessions.revoke(second)
        assertFalse(sessions.valid(second,"credential-a"))
        assertTrue(sessions.valid(third,"credential-a"))
        now+=1000
        assertFalse(sessions.valid(third,"credential-a"))
        assertFalse(sessions.valid("malformed","credential-a"))
    }
    @Test fun loginThrottleHasBoundedWindow() {
        var now=0L
        val guard=LoginThrottle(clock={now},limit=2,windowMillis=1000)
        assertTrue(guard.allow());assertTrue(guard.allow());assertFalse(guard.allow())
        now=1000
        assertTrue(guard.allow())
    }
}
