package org.droidpilot.core

fun crcX25(bytes: ByteArray): Int {
    var crc=0xffff
    for (b in bytes) {
        var t=(b.toInt() and 255) xor (crc and 255)
        t=t xor ((t shl 4) and 255)
        crc=((crc shr 8) xor (t shl 8) xor (t shl 3) xor (t shr 4)) and 65535
    }
    return crc
}

class WireFrame(val bytes: ByteArray, val crcVerified: Boolean) {
    val v2 get() = (bytes[0].toInt() and 255)==253
    val systemId get() = bytes[if(v2) 5 else 3].toInt() and 255
    val componentId get() = bytes[if(v2) 6 else 4].toInt() and 255
    val messageId get() = if(v2) (bytes[7].toInt() and 255) or ((bytes[8].toInt() and 255) shl 8) or ((bytes[9].toInt() and 255) shl 16) else bytes[5].toInt() and 255
    val heartbeatType: Int? get() = if(messageId==0 && (bytes[1].toInt() and 255)>=6) bytes[if(v2) 14 else 10].toInt() and 255 else null
    val autopilot: Int? get() = if(messageId==0 && (bytes[1].toInt() and 255)>=6) bytes[if(v2) 15 else 11].toInt() and 255 else null
}

/** Streaming framing only. Signed wire bytes are preserved; signatures are not authenticated. */
class FrameParser(private val clock:()->Long=::monotonicMillis) {
    private var partialSince=0L
    private var pending=byteArrayOf()
    var invalid=0L; private set
    var unverified=0L; private set
    fun feed(input: ByteArray): List<WireFrame> {
        val now=clock()
        if(pending.isNotEmpty() && now-partialSince>=250) { pending=byteArrayOf();invalid++ }
        if(pending.isEmpty()) partialSince=now
        pending+=input
        val result=mutableListOf<WireFrame>()
        var offset=0
        while(offset<pending.size) {
            val magic=pending[offset].toInt() and 255
            if(magic!=253 && magic!=254) { offset++;continue }
            val header=if(magic==253) 10 else 6
            if(pending.size-offset<header) break
            val length=pending[offset+1].toInt() and 255
            val flags=if(magic==253) pending[offset+2].toInt() and 255 else 0
            if(flags and 254 !=0) { invalid++;offset++;continue }
            val size=header+length+2+if(flags and 1!=0) 13 else 0
            if(pending.size-offset<size) break
            val frame=pending.copyOfRange(offset,offset+size)
            val candidate=WireFrame(frame,false)
            val extra=CrcExtras.values[candidate.messageId]
            if(extra!=null) {
                val expected=crcX25(frame.copyOfRange(1,header+length)+byteArrayOf(extra.toByte()))
                val actual=(frame[header+length].toInt() and 255) or ((frame[header+length+1].toInt() and 255) shl 8)
                if(expected!=actual) { invalid++;offset++;continue }
            } else unverified++
            result+=WireFrame(frame,extra!=null)
            offset+=size
        }
        pending=pending.copyOfRange(offset,pending.size)
        if(offset>0) partialSince=now
        return result
    }
}
