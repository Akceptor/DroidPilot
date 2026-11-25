package org.droidpilot.core

import java.nio.ByteBuffer
import kotlin.random.Random

/** RFC 6184 non-interleaved mode, 90 kHz clock, payload type 96. */
class H264Rtp(private val ssrc:Int=Random.nextInt(),private val mtu:Int=1200) {
    private var sequence=Random.nextInt(65536)
    fun packetize(nals:List<ByteArray>,timestamp:Long):List<ByteArray> {
        val payloads=mutableListOf<ByteArray>()
        for(nal in nals.filter { it.isNotEmpty() }) {
            if(nal.size<=mtu-12) payloads+=nal
            else {
                val header=nal[0].toInt() and 255
                var offset=1
                while(offset<nal.size) {
                    val count=minOf(mtu-14,nal.size-offset)
                    val flags=(header and 31) or (if(offset==1) 128 else 0) or (if(offset+count==nal.size) 64 else 0)
                    payloads+=byteArrayOf(((header and 0xe0) or 28).toByte(),flags.toByte())+nal.copyOfRange(offset,offset+count)
                    offset+=count
                }
            }
        }
        return payloads.mapIndexed { index,payload ->
            ByteBuffer.allocate(12+payload.size).put(0x80.toByte()).put((96 or if(index==payloads.lastIndex) 128 else 0).toByte())
                .putShort((sequence++ and 65535).toShort()).putInt(timestamp.toInt()).putInt(ssrc).put(payload).array()
        }
    }
}

fun splitAnnexB(bytes:ByteArray):List<ByteArray> {
    // Validate the complete AVC length-prefixed structure before scanning delimiters.
    // Lengths 256..511 contain 00 00 01 and must not be interpreted as Annex B.
    val avcc=runCatching {
        val buffer=ByteBuffer.wrap(bytes);val result=mutableListOf<ByteArray>()
        while(buffer.hasRemaining()) {
            require(buffer.remaining()>=4)
            val size=buffer.int;require(size>0 && size<=buffer.remaining())
            val nal=ByteArray(size).also { buffer.get(it) }
            require((nal[0].toInt() and 128)==0 && (nal[0].toInt() and 31) in 1..23)
            result+=nal
        }
        require(result.isNotEmpty());result
    }.getOrNull()
    if(avcc!=null) return avcc
    val starts=mutableListOf<Pair<Int,Int>>()
    var i=0
    while(i+2<bytes.size) {
        val length=if(bytes[i]==0.toByte() && bytes[i+1]==0.toByte()) {
            if(bytes[i+2]==1.toByte()) 3 else if(i+3<bytes.size && bytes[i+2]==0.toByte() && bytes[i+3]==1.toByte()) 4 else 0
        } else 0
        if(length>0) { starts+=i to length;i+=length } else i++
    }
    if(starts.isEmpty()) {
        // MediaCodec may return AVC length-prefixed NAL units.
        val result=mutableListOf<ByteArray>();val buffer=ByteBuffer.wrap(bytes)
        while(buffer.remaining()>=4) {
            val n=buffer.int
            require(n>0 && n<=buffer.remaining()) { "Invalid AVC NAL length" }
            result+=ByteArray(n).also { buffer.get(it) }
        }
        require(!buffer.hasRemaining())
        return result
    }
    return starts.mapIndexed { index,(start,length) -> bytes.copyOfRange(start+length,starts.getOrNull(index+1)?.first ?: bytes.size) }.filter { it.isNotEmpty() }
}
