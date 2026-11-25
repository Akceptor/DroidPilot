package org.droidpilot

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import org.droidpilot.core.*

private val Context.configData by preferencesDataStore("companion")
class ConfigStore(private val context:Context) {
    private val key=stringPreferencesKey("config_v1")
    val config=context.configData.data.map { prefs -> prefs[key]?.let(PilotConfig::decode) ?: PilotConfig() }
    suspend fun save(config:PilotConfig,expectedRevision:String?=null):Boolean {
        val encoded=config.encode();var saved=false
        context.configData.edit { prefs->
            val current=prefs[key]?.let(PilotConfig::decode) ?: PilotConfig()
            if(expectedRevision==null || current.revision()==expectedRevision) { prefs[key]=encoded;saved=true }
        }
        return saved
    }
}
data class RuntimeStatus(
    val running:Boolean=false,val serviceAlive:Boolean=false,val web:String="Stopped",val traffic:Traffic=Traffic(),val rate:Long=0,
    val connections:Map<String,String> = emptyMap(),val reconnects:Long=0,
    val video:String="Stopped",val network:String="",val phone:String="",
    val logs:List<String> = emptyList()
)
object AppState {
    val state=MutableStateFlow(RuntimeStatus())
    fun log(message:String) {
        val line="${java.time.Instant.now()} $message"
        state.update { it.copy(logs=(it.logs+line).takeLast(150)) }
    }
    fun connection(id:String,status:String) {
        state.update { it.copy(connections=it.connections+(id to status)) }
    }
}
