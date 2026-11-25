package org.droidpilot

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.net.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.droidpilot.core.*
import org.droidpilot.web.*

class CompanionService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler { _,e->AppState.log("Subsystem error: ${e.message}") })
    private val runtimeLock=Mutex()
    @Volatile private var runtime:CompanionRuntime?=null
    @Volatile private var server:EmbeddedWebServer?=null
    private var started=false
    private var wake:PowerManager.WakeLock?=null
    private var callback:ConnectivityManager.NetworkCallback?=null
    private var capabilities=""
    private val store by lazy { ConfigStore(applicationContext) }
    private val access by lazy { WebAccessStore(applicationContext) }
    override fun onBind(intent:Intent?) = null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="STOP") { stopSelf();return START_NOT_STICKY }
        val config=try {
            PilotConfig.decode(intent?.getStringExtra("config") ?: error("Start from the visible Activity"))
        } catch(e:Exception) { AppState.log("Start failed: ${e.message}");if(!started) stopSelf();return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("runtime","Companion runtime",NotificationManager.IMPORTANCE_LOW))
            if(Build.VERSION.SDK_INT>=29) {
                val camera=if(config.video && config.cameraId!="@synthetic" && Build.VERSION.SDK_INT>=30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
                startForeground(1,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or camera)
            } else startForeground(1,notification())
        } catch(e:Exception) { AppState.log("Foreground start failed: ${e.message}");if(!started) stopSelf();return START_NOT_STICKY }
        if(!started) {
            started=true
            AppState.state.update { it.copy(serviceAlive=true) }
            monitorNetworks()
            if(config.webEnabled) scope.launch { runWeb(config) }
            scope.launch {
                capabilities=runCatching { cameraReport(this@CompanionService)+"\n\nEncoders\n"+encoderReport() }.getOrElse { "Camera capabilities unavailable: ${it.message}" }
                while(isActive) {
                    AppState.state.update { it.copy(network=networkDiagnostics(this@CompanionService),phone=phoneDiagnostics(this@CompanionService)) }
                    getSystemService(NotificationManager::class.java).notify(1,notification());delay(2000)
                }
            }
        }
        scope.launch { runtimeLock.withLock { replaceRuntime(config) } }
        return START_NOT_STICKY
    }
    private suspend fun replaceRuntime(config:PilotConfig) {
        runtime?.stop()
        serviceMutation(scope.coroutineContext.job,Dispatchers.Main.immediate) {
            runtime=null
            wake?.let { if(it.isHeld) it.release() };wake=null
            if(config.wakeLock) {
                wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"DroidPilot:companion").apply { acquire() }
            }
            runtime=CompanionRuntime(applicationContext,config,scope.coroutineContext).also { it.start() }
        }
    }
    private suspend fun pauseRuntime() = runtimeLock.withLock {
        runtime?.stop()
        serviceMutation(scope.coroutineContext.job,Dispatchers.Main.immediate) {
            runtime=null
            wake?.let { if(it.isHeld) it.release() };wake=null
            AppState.state.update { it.copy(running=false,connections=emptyMap(),video="Stopped",rate=0) }
            AppState.log("Companion paused; web service remains available")
        }
    }
    private suspend fun runWeb(config:PilotConfig) {
        val backend=object:WebBackend {
            override suspend fun configuration()=store.config.first()
            override suspend fun save(config:PilotConfig,revision:String)=store.save(config,revision)
            override suspend fun apply():String?=runtimeLock.withLock {
                val next=store.config.first()
                if((next.video && next.cameraId!="@synthetic") || runtime?.config?.let { it.video && it.cameraId!="@synthetic" }==true) {
                    "Camera activation requires visible Android interaction. Pause here, then press Start in the phone app."
                } else { replaceRuntime(next);null }
            }
            override suspend fun pause() { pauseRuntime() }
            override suspend fun diagnostics():JsonObject {
                val s=AppState.state.value;val t=s.traffic
                val usb=runCatching { serialDevices(getSystemService(UsbManager::class.java)).joinToString("\n") { "${it.javaClass.simpleName}: ${it.device.vendorId}:${it.device.productId} • ${it.ports.size} ports • permission ${getSystemService(UsbManager::class.java).hasPermission(it.device)}" } }.getOrDefault("USB unavailable")
                return buildJsonObject {
                    put("running",s.running);put("video",s.video);put("rate",s.rate);put("reconnects",s.reconnects)
                    put("network",s.network);put("phone",s.phone);put("web",s.web)
                    put("capabilities", "USB\n${usb.ifEmpty { "No supported serial adapter" }}\n\n$capabilities")
                    put("pending",runtime?.config?.revision()!=store.config.first().revision())
                    put("connections",JsonObject(s.connections.mapValues { JsonPrimitive(it.value) }))
                    put("logs",JsonArray(s.logs.map(::JsonPrimitive)))
                    put("traffic",buildJsonObject {
                        put("rxBytes",t.rxBytes);put("txBytes",t.txBytes);put("rxPackets",t.rxPackets);put("txPackets",t.txPackets)
                        put("invalid",t.invalid);put("unverified",t.unverified);put("loops",t.loops);put("drops",t.drops)
                        put("lastHeartbeat",t.lastHeartbeat?.let(::JsonPrimitive) ?: JsonNull)
                        put("vehicleSystem",t.vehicleSystem?.let(::JsonPrimitive) ?: JsonNull)
                        put("vehicleType",t.vehicleType?.let(::JsonPrimitive) ?: JsonNull)
                        put("heartbeatCurrent",s.running && t.lastHeartbeat?.let { System.currentTimeMillis()-it<5000 }==true)
                    })
                }
            }
        }
        val backoff=Backoff()
        while(currentCoroutineContext().isActive) {
            if(access.read()==null) {
                AppState.state.update { it.copy(web="Set web login in Network settings") };delay(2000);continue
            }
            try {
                val web=EmbeddedWebServer(config.webBindAddress,config.webPort,backend,access::read);server=web
                AppState.state.update { it.copy(web="HTTP ${config.webBindAddress}:${config.webPort} • Authentication required") }
                AppState.log("Web server starting on port ${config.webPort}")
                web.run()
            } catch(e:CancellationException) { throw e } catch(e:Exception) {
                AppState.state.update { it.copy(web="HTTP error: ${e.message}") };AppState.log("HTTP server error: ${e.message}")
                delay(backoff.next())
            } finally { server=null }
        }
    }
    private fun monitorNetworks() {
        val manager=getSystemService(ConnectivityManager::class.java)
        callback=object:ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network:Network) { AppState.log("Network available: $network") }
            override fun onLost(network:Network) { AppState.log("Network lost: $network; UDP sockets will reopen");runtime?.networkLost() }
        }.also { manager.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),it) }
    }
    private fun notification():Notification {
        val s=AppState.state.value
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,CompanionService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
        val fc=s.traffic.lastHeartbeat?.let { if(s.running && System.currentTimeMillis()-it<5000) "FC heartbeat" else "FC stale" } ?: "Waiting for FC"
        return Notification.Builder(this,"runtime").setContentTitle(if(s.running) "DroidPilot companion running" else "DroidPilot web service")
            .setContentText("$fc • Video: ${s.video}").setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null,"Stop service",stop).build()).build()
    }
    override fun onDestroy() {
        callback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        scope.cancel();runtime?.close();runtime=null
        runCatching { server?.close() };server=null
        wake?.let { if(it.isHeld) it.release() };wake=null
        AppState.state.update { it.copy(running=false,serviceAlive=false,web="Stopped",connections=emptyMap(),video="Stopped",rate=0) }
        AppState.log("Companion service stopped");stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}
