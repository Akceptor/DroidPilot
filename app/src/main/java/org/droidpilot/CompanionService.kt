package org.droidpilot

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.net.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.droidpilot.core.*
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

class CompanionService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler { _,e->AppState.log("Subsystem error: ${e.message}") })
    private val resources=CopyOnWriteArrayList<Closeable>()
    private var wake:PowerManager.WakeLock?=null
    private var started=false
    private var callback:ConnectivityManager.NetworkCallback?=null
    private var config=PilotConfig()
    override fun onBind(intent:Intent?) = null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") { stopSelf();return START_NOT_STICKY }
        if(started) return START_NOT_STICKY
        try {
            config=PilotConfig.decode(intent?.getStringExtra("config") ?: error("Start from the visible Activity"))
            val notifications=getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("runtime","Companion runtime",NotificationManager.IMPORTANCE_LOW))
            if(Build.VERSION.SDK_INT>=29) {
                val types=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or if(config.video && config.cameraId!="@synthetic" && Build.VERSION.SDK_INT>=30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
                startForeground(1,notification(),types)
            } else startForeground(1,notification())
            started=true
        } catch(e:Exception) { AppState.log("Service start failed: ${e.message}");stopSelf();return START_NOT_STICKY }
        AppState.state.update { it.copy(running=true,connections=emptyMap(),traffic=Traffic(),reconnects=0) }
        if(config.wakeLock) {
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"DroidPilot:companion").apply { acquire() }
        }
        val router=Router()
        scope.launch { router.stats.collect { s->AppState.state.update { it.copy(traffic=s) } } }
        if(config.input=="USB") {
            retry("USB") {
                val transport=SerialTransport(getSystemService(UsbManager::class.java),config,router)
                resources+=transport
                try { transport.run() } finally { resources-=transport;transport.close() }
            }
        } else startUdp("Vehicle UDP",config.udpPort,null,router)
        config.endpoints.forEachIndexed { index,peer-> startUdp("UDP ${index+1}",peer.localPort,peer,router) }
        if(config.video) retry("Video") {
            val pipeline=VideoPipeline(this,config)
            resources+=pipeline
            try { pipeline.run() } finally { resources-=pipeline;pipeline.close() }
        }
        val manager=getSystemService(ConnectivityManager::class.java)
        callback=object:ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network:Network) { AppState.log("Network available: $network") }
            override fun onLost(network:Network) {
                AppState.log("Network lost: $network; UDP sockets will reopen")
                resources.filterIsInstance<UdpTransport>().forEach { it.close() }
            }
        }.also { manager.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),it) }
        scope.launch {
            var last=0L;var previous=monotonicMillis()
            while(isActive) {
                val current=router.stats.value.rxBytes;val now=monotonicMillis()
                AppState.state.update { it.copy(rate=(current-last)*1000/(now-previous).coerceAtLeast(1),network=networkDiagnostics(this@CompanionService),phone=phoneDiagnostics(this@CompanionService)) }
                last=current;previous=now
                getSystemService(NotificationManager::class.java).notify(1,notification())
                delay(2000)
            }
        }
        AppState.log("Companion started")
        return START_NOT_STICKY
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
    private fun notification():Notification {
        val state=AppState.state.value
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,CompanionService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val fc=state.traffic.lastHeartbeat?.let { if(System.currentTimeMillis()-it<5000) "FC heartbeat" else "FC stale" } ?: "Waiting for FC"
        return Notification.Builder(this,"runtime").setContentTitle("DroidPilot companion running")
            .setContentText("$fc • Video: ${state.video}")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null,"Stop service",stop).build()).build()
    }
    override fun onDestroy() {
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope.cancel();resources.forEach { runCatching { it.close() } };resources.clear()
        wake?.let { if(it.isHeld) it.release() };wake=null
        AppState.state.update { it.copy(running=false,connections=emptyMap(),video="Stopped") }
        AppState.log("Companion stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
