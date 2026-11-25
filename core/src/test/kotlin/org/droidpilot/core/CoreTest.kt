package org.droidpilot.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.net.*

class CoreTest {
    private fun heartbeat(v2: Boolean = false, signed: Boolean = false): ByteArray {
        val payload = byteArrayOf(0,0,0,0,2,3,0,4,3)
        val header = if (v2) byteArrayOf(0xfd.toByte(),9,if(signed) 1 else 0,0,7,1,1,0,0,0)
                     else byteArrayOf(0xfe.toByte(),9,7,1,1,0)
        val body = header + payload
        val crc = crcX25(body.copyOfRange(1,body.size) + byteArrayOf(50))
        return body + byteArrayOf(crc.toByte(),(crc shr 8).toByte()) + if(signed) ByteArray(13) { it.toByte() } else byteArrayOf()
    }
    @Test fun partialReadsAndMultipleFrames() {
        val parser = FrameParser()
        val a = heartbeat(); val b = heartbeat(true,true)
        assertTrue(parser.feed(a.copyOfRange(0,5)).isEmpty())
        val frames = parser.feed(a.copyOfRange(5,a.size)+b)
        assertEquals(2,frames.size)
        assertArrayEquals(a,frames[0].bytes)
        assertArrayEquals(b,frames[1].bytes)
        assertEquals(1,frames[1].systemId)
    }
    @Test fun corruptCrcRejectedAndResynchronized() {
        val parser = FrameParser()
        val bad = heartbeat().also { it[8] = 77 }
        assertEquals(1, parser.feed(bad+heartbeat()).size)
        assertEquals(1L, parser.invalid)
    }
    @Test fun loopCacheIsBoundedAndExpires() {
        var now = 0L
        val cache = LoopGuard(2,100) { now }
        assertTrue(cache.accept(byteArrayOf(1)))
        assertFalse(cache.accept(byteArrayOf(1)))
        now = 101
        assertTrue(cache.accept(byteArrayOf(1)))
        cache.accept(byteArrayOf(2)); cache.accept(byteArrayOf(3))
        assertEquals(2,cache.size)
    }
    @Test fun queueOverflowAndStaleCommands() {
        var now = 0L
        val queue = PacketQueue(2,100) { now }
        queue.offer(byteArrayOf(1)); queue.offer(byteArrayOf(2)); queue.offer(byteArrayOf(3))
        assertEquals(1L,queue.dropped)
        assertArrayEquals(byteArrayOf(2),queue.poll())
        now=101
        assertNull(queue.poll())
        assertEquals(2L,queue.dropped)
    }
    @Test fun configRoundTripAndValidation() {
        val c = PilotConfig(endpoints=listOf(UdpPeer("10.1.2.3",14550,14551)), baud=921600)
        assertEquals(c, PilotConfig.decode(c.encode()))
        assertThrows(IllegalArgumentException::class.java) { c.copy(udpPort=70000).validate() }
        assertThrows(IllegalArgumentException::class.java) { UdpPeer.parse("hostname:14550") }
    }
    @Test fun backoffIsBoundedAndResets() {
        val b = Backoff()
        assertEquals(listOf(250L,500L,1000L,2000L,4000L,8000L,8000L),List(7){b.next()})
        b.reset(); assertEquals(250L,b.next())
    }
    @Test fun rtpFragmentationAndMarker() {
        val nal = byteArrayOf(0x65)+ByteArray(3000){it.toByte()}
        val packets = H264Rtp(ssrc=1).packetize(listOf(nal),90000)
        assertEquals(3,packets.size)
        assertTrue(packets.all { it.size<=1200 })
        assertEquals(0x80, packets.first()[13].toInt() and 0x80)
        assertEquals(0x40, packets.last()[13].toInt() and 0x40)
        assertEquals(0,packets.first()[1].toInt() and 0x80)
        assertEquals(0x80,packets.last()[1].toInt() and 0x80)
        val restored=byteArrayOf(0x65)+packets.flatMap { it.drop(14) }.toByteArray()
        assertArrayEquals(nal,restored)
    }
    @Test fun routerIsolationAndNoReflection() {
        val router=Router()
        val a=PacketQueue(); val b=PacketQueue(); val slow=PacketQueue(1)
        router.add("a",a);router.add("b",b);router.add("slow",slow)
        router.receive("a",heartbeat())
        assertNull(a.poll());assertArrayEquals(heartbeat(),b.poll())
        router.receive("b",heartbeat())
        assertNull(a.poll())
        assertEquals(1L,router.stats.value.loops)
    }
    @Test fun partialFrameExpires() {
        var now=0L
        val parser=FrameParser(clock={now})
        val frame=heartbeat()
        parser.feed(frame.copyOfRange(0,8))
        now=300
        assertTrue(parser.feed(frame.copyOfRange(8,frame.size)).isEmpty())
        assertEquals(1L,parser.invalid)
        assertEquals(1,parser.feed(frame).size)
    }
    @Test fun avccLengthsDoNotBecomeStartCodes() {
        for(size in 256..511) {
            val nal=byteArrayOf(0x65)+ByteArray(size-1){0x22}
            val data=java.nio.ByteBuffer.allocate(size+4).putInt(size).put(nal).array()
            assertArrayEquals(nal,splitAnnexB(data).single())
        }
        val a=byteArrayOf(0x67,1,2);val b=byteArrayOf(0x68,3)
        val avcc=java.nio.ByteBuffer.allocate(13).putInt(a.size).put(a).putInt(b.size).put(b).array()
        val parsed=splitAnnexB(avcc)
        assertArrayEquals(a,parsed[0]);assertArrayEquals(b,parsed[1])
        val annex=byteArrayOf(0,0,0,1)+a+byteArrayOf(0,0,1)+b
        assertArrayEquals(a,splitAnnexB(annex)[0]);assertArrayEquals(b,splitAnnexB(annex)[1])
    }
    @Test fun videoOverflowWaitsForKeyframe() {
        val queue=EncodedFrameQueue(2)
        val p=EncodedFrame(listOf(byteArrayOf(0x41)),0,false)
        val key=p.copy(key=true)
        assertFalse(queue.offer(p))
        assertTrue(queue.offer(key));assertTrue(queue.offer(p))
        assertFalse(queue.offer(p)) // overflow invalidates every dependent frame
        assertNull(queue.poll());assertTrue(queue.needsKeyframe)
        assertFalse(queue.offer(p));assertTrue(queue.offer(key))
        assertTrue(queue.poll()!!.key)
        queue.invalidate();assertTrue(queue.needsKeyframe)
    }
    @Test fun udpToUdpRoundTrip() = runBlocking {
        val router = Router()
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { ground ->
            DatagramSocket(0,InetAddress.getLoopbackAddress()).use { vehicle ->
                ground.soTimeout=3000;vehicle.soTimeout=3000
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
                val a=UdpTransport("vehicle", "127.0.0.1",0,UdpPeer("127.0.0.1",vehicle.localPort),router)
                val b=UdpTransport("ground", "127.0.0.1",0,UdpPeer("127.0.0.1",ground.localPort),router)
                val jobs=listOf(scope.launch { a.run() },scope.launch { b.run() })
                try {
                    withTimeout(3000) { while(a.localPort==0 || b.localPort==0) delay(10) }
                    val frame=heartbeat()
                    vehicle.send(DatagramPacket(frame,frame.size,InetAddress.getLoopbackAddress(),a.localPort))
                    val packet=DatagramPacket(ByteArray(512),512);ground.receive(packet)
                    assertArrayEquals(frame,packet.data.copyOf(packet.length))
                    val reply=heartbeat(true)
                    ground.send(DatagramPacket(reply,reply.size,InetAddress.getLoopbackAddress(),b.localPort))
                    vehicle.receive(packet)
                    assertArrayEquals(reply,packet.data.copyOf(packet.length))
                } finally { a.close();b.close();jobs.forEach { it.cancelAndJoin() };scope.cancel() }
            }
        }
    }
}
