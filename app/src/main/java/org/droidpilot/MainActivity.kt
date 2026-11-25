package org.droidpilot

import android.Manifest
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.*
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.droidpilot.core.*
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

class MainActivity:ComponentActivity() {
    private var pending: PilotConfig?=null
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.let { config->
            if(!config.video || config.cameraId=="@synthetic" || checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) startRuntime(config)
            else AppState.log("Camera permission denied; service not started")
        }
        pending=null
    }
    private val usbPermission=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) { AppState.log("USB permission ${if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)) "granted" else "denied"}") }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.content.ContextCompat.registerReceiver(this,usbPermission,IntentFilter("org.droidpilot.USB_PERMISSION"),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { PilotApp(::requestStart,::requestUsb) }
            }
        }
    }
    private fun requestStart(config:PilotConfig) {
        val required=buildList {
            if(config.video && config.cameraId!="@synthetic" && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if(required.isEmpty()) startRuntime(config) else { pending=config;permissions.launch(required.toTypedArray()) }
    }
    private fun startRuntime(config:PilotConfig) {
        runCatching { startForegroundService(Intent(this,CompanionService::class.java).putExtra("config",config.encode())) }.onFailure { AppState.log("Start failed: ${it.message}") }
    }
    private fun requestUsb(device:android.hardware.usb.UsbDevice) {
        val manager=getSystemService(UsbManager::class.java)
        val intent=Intent("org.droidpilot.USB_PERMISSION").setPackage(packageName)
        manager.requestPermission(device,PendingIntent.getBroadcast(this,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }
    override fun onDestroy() { unregisterReceiver(usbPermission);super.onDestroy() }
}

private fun configFields(config:PilotConfig)=config.fields()
private fun fromFields(fields:Map<String,String>)=PilotConfig.fromFields(fields)

@Composable private fun PilotApp(start:(PilotConfig)->Unit,usbPermission:(android.hardware.usb.UsbDevice)->Unit) {
    val context=LocalContext.current
    val store=remember { ConfigStore(context.applicationContext) }
    val scope=rememberCoroutineScope()
    val state by AppState.state.collectAsStateWithLifecycle()
    var fields by remember { mutableStateOf(configFields(PilotConfig())) }
    var loaded by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(PilotConfig().revision()) }
    var message by remember { mutableStateOf("") }
    var screen by remember { mutableStateOf("Dashboard") }
    var phone by remember { mutableStateOf("") }
    var network by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { store.config.collect { saved->
            if(!dirty) { fields=configFields(saved);revision=saved.revision() }
            loaded=true
        } } catch(e:Exception) { message="Configuration read failed: ${e.message}" }
    }
    LaunchedEffect(state.running) {
        while(isActive) {
            phone=withContext(Dispatchers.IO) { phoneDiagnostics(context) }
            network=withContext(Dispatchers.IO) { networkDiagnostics(context) }
            delay(3000)
        }
    }
    fun set(key:String,value:String) { fields=fields+(key to value);dirty=true }
    fun save(andStart:Boolean=false) {
        scope.launch {
            try {
                val config=fromFields(fields)
                if(store.save(config,revision)) { revision=config.revision();dirty=false;message="Configuration saved";if(andStart) start(config) }
                else message="Settings changed remotely. Reload configuration before saving."
            }
            catch(e:Exception) { message="Invalid configuration: ${e.message ?: e.javaClass.simpleName}" }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text("DroidPilot",style=MaterialTheme.typography.headlineMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Dashboard","MAVLink","Video","GNSS","Network","Diagnostics").forEach { item->
                FilterChip(selected=screen==item,onClick={screen=item},label={Text(item)})
            }
        }
        if(message.isNotEmpty()) Text(message,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(vertical=6.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            @Composable fun field(key:String,label:String,multiline:Boolean=false) {
                OutlinedTextField(value=fields[key].orEmpty(),onValueChange={set(key,it)},label={Text(label)},enabled=!state.running && loaded,singleLine=!multiline,modifier=Modifier.fillMaxWidth())
            }
            @Composable fun toggle(key:String,label:String) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(label,modifier=Modifier.weight(1f));Switch(checked=fields[key]=="true",onCheckedChange={set(key,it.toString())},enabled=!state.running && loaded)
                }
            }
            when(screen) {
                "Dashboard" -> {
                    Text(if(state.running) "Companion running" else "Companion stopped",style=MaterialTheme.typography.titleLarge)
                    val t=state.traffic
                    val age=t.lastHeartbeat?.let { ((System.currentTimeMillis()-it)/1000).coerceAtLeast(0) }
                    Text("Flight controller: ${if(state.running && age!=null && age<5) "Heartbeat received" else "No current heartbeat"}\nVehicle system: ${t.vehicleSystem ?: "Unknown"} • Type: ${t.vehicleType ?: "Unknown"}\nLast heartbeat: ${t.lastHeartbeat?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "Never"}")
                    Text("RX ${t.rxBytes} B / ${t.rxPackets} packets\nTX ${t.txBytes} B / ${t.txPackets} packets\nRX rate ${if(state.running) state.rate else 0} B/s\nInvalid ${t.invalid} • CRC unverified ${t.unverified}\nLoop drops ${t.loops} • Queue drops ${t.drops}\nReconnect attempts ${state.reconnects}")
                    state.connections.forEach { (id,status)->Text("$id: $status") }
                    Text("Video: ${state.video}\nGNSS forwarding: Disabled (Phase 3)")
                    Text(phone);Text(network)
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Button(onClick={save(true)},enabled=loaded && !state.running) { Text("Start") }
                        OutlinedButton(onClick={ context.stopService(Intent(context,CompanionService::class.java)) },enabled=state.serviceAlive) { Text("Stop service") }
                    }
                    toggle("wakeLock","Keep CPU awake while running")
                }
                "MAVLink" -> {
                    Text("Stop the service to apply configuration changes.")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("USB","UDP").forEach { input->FilterChip(selected=fields["input"]==input,onClick={set("input",input)},enabled=!state.running,label={Text(input)}) }
                    }
                    if(fields["input"]=="USB") {
                        val manager=context.getSystemService(UsbManager::class.java)
                        var refresh by remember { mutableIntStateOf(0) }
                        val devices=remember(refresh) { serialDevices(manager) }
                        OutlinedButton(onClick={refresh++}) { Text("Refresh USB devices") }
                        if(devices.isEmpty()) Text("No supported USB serial adapter detected")
                        devices.forEach { driver->
                            val device=driver.device
                            Text("${driver.javaClass.simpleName}: ${device.vendorId}:${device.productId} • ${driver.ports.size} port(s)\nPermission: ${manager.hasPermission(device)}")
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick={set("usbKey",device.selectionKey())},enabled=!state.running) { Text("Select") }
                                OutlinedButton(onClick={usbPermission(device)}) { Text("Grant USB permission") }
                            }
                        }
                        field("usbKey","Selected adapter VID:PID (empty = first)")
                        field("usbPort","Adapter port index (0 based)")
                        Text("Baud: 57600, 115200, 230400, 460800, 921600")
                        field("baud","UART baud")
                    }
                    field("listenAddress","Local bind IP (0.0.0.0 = default IPv4 routing)")
                    field("udpPort","Vehicle UDP listener port")
                    Text("UDP listener pins the first sender. Other senders are ignored until 5 s of silence. Restrict access with your trusted network/VPN.")
                    field("endpoints","Remote IP:port@localPort; separate peers with ;",true)
                    Text("Use @0 for an ephemeral local port. Replies must return to that source port. IPv6 example: [::1]:14550@0. Maximum 16 peers.")
                    field("systemId","Local system ID (reserved for future injection)")
                    field("componentId","Local component ID (reserved for future injection)")
                }
                "Video" -> {
                    toggle("video","Enable hardware H.264 / RTP UDP output")
                    var infos by remember { mutableStateOf(emptyList<CameraInfo>()) }
                    LaunchedEffect(Unit) { runCatching { withContext(Dispatchers.IO) { cameras(context) } }.onSuccess { infos=it }.onFailure { message=it.message.orEmpty() } }
                    infos.forEach { info->
                        Text("${info.description}\nSizes: ${info.sizes.joinToString()}\nFPS: ${info.fps}")
                        OutlinedButton(onClick={set("cameraId",info.id)},enabled=!state.running) { Text("Select ${info.id}") }
                    }
                    OutlinedButton(onClick={set("cameraId","@synthetic")},enabled=!state.running) { Text("Select synthetic test video") }
                    field("cameraId","Camera ID (empty = first rear camera)")
                    field("width","Width");field("height","Height");field("fps","Frames per second")
                    field("bitrate","H.264 bitrate (bit/s)");field("keyframeSeconds","Keyframe interval (s)")
                    field("videoHost","RTP destination IP");field("videoPort","RTP destination port")
                    toggle("autofocus","Continuous autofocus when supported")
                    field("focusDistance","Manual focus distance (diopters)")
                    field("exposure","Exposure compensation steps")
                    toggle("stabilization","Video stabilization")
                    field("zoom","Digital zoom ratio")
                    Text("Phase 1 streams native sensor orientation. Rotation, flips, H.265, UVC and additional outputs are not implemented.")
                    CameraPreview(fields,state.running) { message=it }
                }
                "GNSS" -> Text("GNSS forwarding is disabled. GPS_INPUT generation and Android GNSS permissions are planned for Phase 3.")
                "Network" -> {
                    Text(network)
                    Text("Sockets use normal Android routing, including an active VPN. No interface binding or discovery is used. Start ZeroTier separately and configure explicit peer IP addresses.")
                    Text("Web server: ${state.web}")
                    Text("Use http://PHONE_IP:${fields["webPort"] ?: "4001"} in your browser. Sign in with your web username and password. Use a trusted local network or VPN.")
                    toggle("webEnabled","Enable web configuration server")
                    field("webBindAddress","HTTP listening address")
                    field("webPort","HTTP port")
                    Text("Listener changes apply after Stop service and Start on the phone.")
                    WebLoginSettings { message=it }
                    OutlinedButton(onClick={context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}) { Text("Battery optimization settings") }
                }
                "Diagnostics" -> {
                    Text(phone)
                    Text("Camera permission: ${context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED}")
                    var capabilities by remember { mutableStateOf("Loading capabilities…") }
                    LaunchedEffect(Unit) { capabilities=withContext(Dispatchers.IO) { runCatching { cameraReport(context)+"\n\nEncoders\n"+encoderReport() }.getOrElse { "Capabilities unavailable: ${it.message}" } } }
                    Text(capabilities)
                    OutlinedButton(onClick={
                        val report="DroidPilot 0.2.0\nAndroid API ${Build.VERSION.SDK_INT}\n$phone\n$network\n$capabilities\nTraffic: ${state.traffic}\n"+state.logs.joinToString("\n")
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,report),"Export diagnostics"))
                    }) { Text("Export diagnostic report") }
                    Text(state.logs.joinToString("\n").ifEmpty { "No events" })
                }
            }
            if(screen in listOf("MAVLink","Video","Dashboard","Network")) Button(onClick={save()},enabled=loaded && !state.running) { Text("Save configuration") }
            OutlinedButton(onClick={scope.launch {
                val saved=store.config.first();fields=configFields(saved);revision=saved.revision();dirty=false;message="Saved configuration reloaded"
            }},enabled=!state.running && loaded) { Text("Reload configuration") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun CameraPreview(fields:Map<String,String>,running:Boolean,message:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<VideoSource?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val permission=androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted->enabled=granted }
    DisposableEffect(Unit) { onDispose { job?.cancel();camera?.close() } }
    LaunchedEffect(running) { if(running) { enabled=false;job?.cancel();camera?.close();camera=null } }
    OutlinedButton(onClick={
        if(enabled) { enabled=false;job?.cancel();camera?.close();camera=null }
        else if(fields["cameraId"]=="@synthetic" || context.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) enabled=true
        else permission.launch(Manifest.permission.CAMERA)
    },enabled=!running) { Text(if(enabled) "Close preview" else "Preview camera (service stopped)") }
    if(enabled && !running) {
        AndroidView(modifier=Modifier.fillMaxWidth().height(240.dp),factory={ctx->
            TextureView(ctx).apply {
                surfaceTextureListener=object:TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture:android.graphics.SurfaceTexture,width:Int,height:Int) {
                        job=scope.launch {
                            var surface:Surface?=null
                            try {
                                val config=fromFields(fields)
                                texture.setDefaultBufferSize(config.width,config.height)
                                surface=Surface(texture)
                                val source=videoSource(ctx.applicationContext,config);camera=source
                                source.start(listOf(surface))
                                throw source.failure.await()
                            } catch(e:CancellationException) { throw e } catch(e:Exception) { message("Preview: ${e.message}") }
                            finally { camera?.close();camera=null;surface?.release() }
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(texture:android.graphics.SurfaceTexture,width:Int,height:Int) {}
                    override fun onSurfaceTextureDestroyed(texture:android.graphics.SurfaceTexture):Boolean { job?.cancel();camera?.close();return true }
                    override fun onSurfaceTextureUpdated(texture:android.graphics.SurfaceTexture) {}
                }
            }
        })
    }
}


@Composable private fun WebLoginSettings(message:(String)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val access=remember { WebAccessStore(context.applicationContext) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { username=access.read()?.username.orEmpty() }
    Text("Web login (stored separately from companion configuration)")
    OutlinedTextField(value=username,onValueChange={username=it},label={Text("Web username")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(value=password,onValueChange={password=it},label={Text("Set web password")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
    OutlinedButton(onClick={scope.launch {
        busy=true
        try { access.save(username,password);password="";message("Web login saved") }
        catch(e:Exception) { message("Web login: ${e.message}") }
        finally { busy=false }
    }},enabled=!busy && username.isNotBlank() && password.isNotEmpty()) { Text("Save web login") }
}
