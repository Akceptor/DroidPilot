package org.droidpilot.core

import java.util.Properties
import java.io.StringReader
import java.io.StringWriter

fun numericIp(value:String):Boolean =
    if(value.contains(':')) value.matches(Regex("[0-9a-fA-F:]+")) && runCatching { java.net.InetAddress.getByName(value) is java.net.Inet6Address }.getOrDefault(false)
    else value.split('.').let { it.size==4 && it.all { part-> part.isNotEmpty() && part.all(Char::isDigit) && (part.toIntOrNull() ?: 999) in 0..255 } }

data class UdpPeer(val host:String,val port:Int,val localPort:Int=0) {
    init { require(numericIp(host)) { "Use a numeric IPv4 or IPv6 address" };require(port in 1..65535);require(localPort in 0..65535) }
    override fun toString() = if(host.contains(':')) "[$host]:$port@$localPort" else "$host:$port@$localPort"
    companion object {
        fun parse(value:String):UdpPeer {
            val parts=value.trim().split('@');require(parts.size<=2)
            val addr=parts[0];val cut=addr.lastIndexOf(':');require(cut>0)
            return UdpPeer(addr.substring(0,cut).removeSurrounding("[","]"),addr.substring(cut+1).toInt(),parts.getOrNull(1)?.toInt() ?: 0)
        }
    }
}
data class PilotConfig(
    val input:String="UDP",val usbKey:String="",val usbPort:Int=0,val baud:Int=115200,
    val listenAddress:String="0.0.0.0",val udpPort:Int=14550,
    val endpoints:List<UdpPeer> = emptyList(),val systemId:Int=191,val componentId:Int=191,
    val video:Boolean=false,val cameraId:String="",val width:Int=1280,val height:Int=720,
    val fps:Int=30,val bitrate:Int=2_000_000,val keyframeSeconds:Int=1,
    val videoHost:String="127.0.0.1",val videoPort:Int=5600,
    val autofocus:Boolean=true,val focusDistance:Float=0f,val exposure:Int=0,val stabilization:Boolean=false,val zoom:Float=1f,
    val wakeLock:Boolean=true,
    val webEnabled:Boolean=true,val webBindAddress:String="0.0.0.0",val webPort:Int=4001
) {
    fun validate() {
        require(input in listOf("USB","UDP"));require(baud in listOf(57600,115200,230400,460800,921600))
        require(numericIp(listenAddress));require(udpPort in 1..65535);require(usbPort>=0)
        require(systemId in 1..255 && componentId in 1..255)
        require(width in 160..4096 && height in 120..4096 && fps in 1..120)
        require(bitrate in 100_000..50_000_000 && keyframeSeconds in 1..10)
        require(numericIp(videoHost) && videoPort in 1..65535)
        require(zoom.isFinite() && zoom>=1 && focusDistance.isFinite() && focusDistance>=0)
        require(numericIp(webBindAddress) && webPort in 1..65535)
        require(endpoints.size<=16)
        val ports=endpoints.map { it.localPort }.filter { it!=0 } + if(input=="UDP") listOf(udpPort) else emptyList()
        require(ports.distinct().size==ports.size) { "Local UDP ports must be unique" }
    }
    fun encode():String {
        validate()
        val p=Properties()
        val fields=mapOf("input" to input,"usbKey" to usbKey,"usbPort" to usbPort,"baud" to baud,"listenAddress" to listenAddress,"udpPort" to udpPort,"endpoints" to endpoints.joinToString(";"),"systemId" to systemId,"componentId" to componentId,"video" to video,"cameraId" to cameraId,"width" to width,"height" to height,"fps" to fps,"bitrate" to bitrate,"keyframeSeconds" to keyframeSeconds,"videoHost" to videoHost,"videoPort" to videoPort,"autofocus" to autofocus,"focusDistance" to focusDistance,"exposure" to exposure,"stabilization" to stabilization,"zoom" to zoom,"wakeLock" to wakeLock,"webEnabled" to webEnabled,"webBindAddress" to webBindAddress,"webPort" to webPort)
        fields.forEach { (k,v)->p.setProperty(k,v.toString()) }
        return StringWriter().also { p.store(it,null) }.toString()
    }
    companion object {
        fun decode(text:String):PilotConfig {
            val p=Properties().apply { load(StringReader(text)) };val d=PilotConfig()
            fun s(k:String,default:Any)=p.getProperty(k,default.toString())
            fun i(k:String,default:Int)=s(k,default).toInt()
            fun b(k:String,default:Boolean)=s(k,default).toBooleanStrict()
            return PilotConfig(s("input",d.input),s("usbKey",d.usbKey),i("usbPort",d.usbPort),i("baud",d.baud),s("listenAddress",d.listenAddress),i("udpPort",d.udpPort),
                s("endpoints","").split(';').filter { it.isNotBlank() }.map(UdpPeer::parse),i("systemId",d.systemId),i("componentId",d.componentId),b("video",d.video),s("cameraId",d.cameraId),i("width",d.width),i("height",d.height),i("fps",d.fps),i("bitrate",d.bitrate),i("keyframeSeconds",d.keyframeSeconds),s("videoHost",d.videoHost),i("videoPort",d.videoPort),b("autofocus",d.autofocus),s("focusDistance",d.focusDistance).toFloat(),i("exposure",d.exposure),b("stabilization",d.stabilization),s("zoom",d.zoom).toFloat(),b("wakeLock",d.wakeLock),b("webEnabled",d.webEnabled),s("webBindAddress",d.webBindAddress),i("webPort",d.webPort)).also { it.validate() }
        }
    }
}


fun PilotConfig.fields():Map<String,String> {
    val properties=Properties().apply { load(StringReader(encode())) }
    return properties.stringPropertyNames().associateWith(properties::getProperty)
}
fun PilotConfig.Companion.fromFields(fields:Map<String,String>):PilotConfig {
    val expected=PilotConfig().fields().keys
    require(fields.keys==expected) { "Configuration fields do not match this application version" }
    val properties=Properties().apply { fields.forEach { (k,v)->setProperty(k,v) } }
    return decode(StringWriter().also { properties.store(it,null) }.toString())
}
fun PilotConfig.revision():String = java.util.Base64.getEncoder().encodeToString(
    java.security.MessageDigest.getInstance("SHA-256").digest(fields().toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8))
)
