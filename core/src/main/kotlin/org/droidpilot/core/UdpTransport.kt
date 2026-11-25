package org.droidpilot.core

import java.io.Closeable
import java.net.*
import kotlinx.coroutines.*

class UdpTransport(
    private val id:String,private val bindAddress:String,private val bindPort:Int,
    private val remote:UdpPeer?,private val router:Router,
    private val status:(String)->Unit={}
):Closeable {
    @Volatile private var socket:DatagramSocket?=null
    @Volatile var localPort:Int=0;private set
    @Volatile private var target:SocketAddress?=null
    @Volatile private var lastReceive=0L
    private val queue=PacketQueue()
    private val sessionLock=Any()
    suspend fun run():Unit=coroutineScope {
        val opened=DatagramSocket(null)
        try {
            opened.reuseAddress=false
            opened.bind(InetSocketAddress(InetAddress.getByName(bindAddress),bindPort))
            opened.soTimeout=200
            socket=opened;localPort=opened.localPort
            target=remote?.let { InetSocketAddress(InetAddress.getByName(it.host),it.port) }
            router.add(id,queue);status("Listening :$localPort${remote?.let { " → $it" } ?: " (first peer pinned)"}")
            val writer=launch(Dispatchers.IO) {
                while(isActive) {
                    var idle=false
                    synchronized(sessionLock) {
                        val bytes=queue.poll();val destination=target
                        idle=bytes==null
                        if(bytes!=null && destination!=null && (remote!=null || monotonicMillis()-lastReceive<5000)) {
                            opened.send(DatagramPacket(bytes,bytes.size,destination));router.sent(bytes.size)
                        }
                    }
                    if(idle) delay(5)
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    val buffer=ByteArray(65535)
                    while(isActive) {
                        val packet=DatagramPacket(buffer,buffer.size)
                        try { opened.receive(packet) } catch(_:SocketTimeoutException) { continue }
                        synchronized(sessionLock) {
                            if(remote==null || packet.socketAddress==target) {
                                val stale=monotonicMillis()-lastReceive>=5000
                                if(remote!=null || target==null || packet.socketAddress==target || stale) {
                                    if(stale || (remote==null && target!=packet.socketAddress)) {
                                        queue.clear();router.remove(id);router.add(id,queue)
                                    }
                                    if(remote==null) target=packet.socketAddress
                                    lastReceive=monotonicMillis()
                                    router.receive(id,packet.data.copyOfRange(packet.offset,packet.offset+packet.length))
                                }
                            }
                        }
                    }
                }
            } finally { writer.cancelAndJoin() }
        } finally { opened.close();socket=null;localPort=0;target=null;router.remove(id);queue.clear() }
    }
    override fun close() { socket?.close() }
}
