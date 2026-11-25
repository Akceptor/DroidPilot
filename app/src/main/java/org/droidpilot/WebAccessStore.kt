package org.droidpilot

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.droidpilot.web.WebCredentials
import java.io.File

class WebAccessStore(context:Context) {
    companion object { private val lock=Mutex() }
    private val file=AtomicFile(File(context.filesDir,"web-access"))
    suspend fun read():WebCredentials?=withContext(Dispatchers.IO) { lock.withLock {
        if(!file.baseFile.exists()) null else runCatching { WebCredentials.decode(file.openRead().bufferedReader().use { it.readText() }) }.getOrNull()
    } }
    suspend fun save(username:String,password:String)=withContext(Dispatchers.IO) { lock.withLock {
        val credential=WebCredentials.create(username,password)
        val stream=file.startWrite()
        try { stream.write(credential.encode().toByteArray(Charsets.UTF_8));file.finishWrite(stream) }
        catch(e:Exception) { file.failWrite(stream);throw e }
    } }
}
