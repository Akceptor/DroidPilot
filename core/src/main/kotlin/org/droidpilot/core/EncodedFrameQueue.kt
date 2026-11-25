package org.droidpilot.core

import java.util.ArrayDeque

data class EncodedFrame(val nals:List<ByteArray>,val pts:Long,val key:Boolean)
/** A dependency-aware queue for one encoded consumer. Invalidation requires a new IDR. */
class EncodedFrameQueue(private val capacity:Int=4) {
    private val frames=ArrayDeque<EncodedFrame>()
    private var waiting=true
    var dropped=0L;private set
    val needsKeyframe:Boolean @Synchronized get()=waiting
    @Synchronized fun offer(frame:EncodedFrame):Boolean {
        if(frames.size>=capacity) invalidate()
        if(waiting && !frame.key) { dropped++;return false }
        frames.addLast(frame)
        if(frame.key) waiting=false
        return true
    }
    @Synchronized fun poll():EncodedFrame? = frames.pollFirst()
    @Synchronized fun invalidate() { dropped+=frames.size;frames.clear();waiting=true }
}
