package org.droidpilot

import android.content.Context
import android.hardware.usb.UsbManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.droidpilot.core.*
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/** Independently cancellable MAVLink/video owner; the HTTP service outlives a paused runtime. */
class CompanionRuntime(private val context:Context,val config:PilotConfig,parent:CoroutineContext):Closeable {
    private val supervisor=SupervisorJob(parent[Job])
    private val scope=CoroutineScope(parent+supervisor+Dispatchers.IO)
    private val resources=CopyOnWriteArrayList<Closeable>()
    fun start() {
        AppState.state.update { it.copy(running=true,connections=emptyMap(),traffic=Traffic(),rate=0,reconnects=0,video="Stopped") }
        val router=Router()
        scope.launch { router.stats.collect { s->AppState.state.update { it.copy(traffic=s) } } }
        if(config.input=="USB") {
            retry("USB") {
                val transport=SerialTransport(context.getSystemService(UsbManager::class.java),config,router)
                resources+=transport
                try { transport.run() } finally { resources-=transport;transport.close() }
            }
        } else startUdp("Vehicle UDP",config.udpPort,null,router)
        config.endpoints.forEachIndexed { index,peer-> startUdp("UDP ${index+1}",peer.localPort,peer,router) }
        if(config.video) retry("Video") {
            val pipeline=VideoPipeline(context,config)
            resources+=pipeline
            try { pipeline.run() } finally { resources-=pipeline;pipeline.close() }
        }
        scope.launch {
            var last=0L;var previous=monotonicMillis()
            while(isActive) {
                val current=router.stats.value.rxBytes;val now=monotonicMillis()
                AppState.state.update { it.copy(rate=(current-last)*1000/(now-previous).coerceAtLeast(1)) }
                last=current;previous=now;delay(2000)
            }
        }
        AppState.log("Companion runtime started")
    }
    private fun startUdp(id:String,port:Int,peer:UdpPeer?,router:Router) = retry(id) {
        val transport=UdpTransport(id,config.listenAddress,port,peer,router) { AppState.connection(id,it) }
        resources+=transport
        try { transport.run() } finally { resources-=transport;transport.close() }
    }
    private fun retry(id:String,block:suspend ()->Unit) = scope.launch {
        val backoff=Backoff();var attempts=0
        while(isActive) {
            val began=monotonicMillis()
            try { block() } catch(e:CancellationException) { throw e } catch(e:Exception) {
                AppState.connection(id,"Waiting: ${e.message}")
                if(id=="Video") AppState.state.update { it.copy(video="Error: ${e.message}") }
                AppState.log("$id: ${e.message}")
            }
            if(monotonicMillis()-began>10000) backoff.reset()
            if(attempts++>0) AppState.state.update { it.copy(reconnects=it.reconnects+1) }
            delay(backoff.next())
        }
    }
    fun networkLost() { resources.filterIsInstance<UdpTransport>().forEach { it.close() } }
    suspend fun stop() { close();supervisor.join() }
    override fun close() {
        supervisor.cancel()
        resources.forEach { runCatching { it.close() } };resources.clear()
    }
}
