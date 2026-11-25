package org.droidpilot.web

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class WebCredentials private constructor(val username:String,private val salt:ByteArray,private val hash:ByteArray) {
    fun verify(user:String,password:String):Boolean {
        if(password.length>256 || user.length>64) return false
        val candidate=derive(password,salt)
        return MessageDigest.isEqual(hash,candidate) && MessageDigest.isEqual(username.toByteArray(Charsets.UTF_8),user.toByteArray(Charsets.UTF_8))
    }
    fun encode():String = listOf(username.toByteArray(Charsets.UTF_8),salt,hash).joinToString(":") { Base64.getEncoder().encodeToString(it) }
    companion object {
        fun create(username:String,password:String):WebCredentials {
            require(username.isNotBlank() && username.length<=64 && username.none { it==':' || it.isISOControl() }) { "Invalid web username" }
            require(password.isNotEmpty() && password.length<=256) { "Password must contain 1 to 256 characters" }
            val salt=ByteArray(16).also(SecureRandom()::nextBytes)
            return WebCredentials(username,salt,derive(password,salt))
        }
        fun decode(value:String):WebCredentials {
            val parts=value.trim().split(':');require(parts.size==3)
            val decoded=parts.map { Base64.getDecoder().decode(it) }
            require(decoded[1].size==16 && decoded[2].size==32)
            return WebCredentials(String(decoded[0],Charsets.UTF_8),decoded[1],decoded[2])
        }
        private fun derive(password:String,salt:ByteArray):ByteArray {
            val spec=PBEKeySpec(password.toCharArray(),salt,120_000,256)
            return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        }
    }
}
