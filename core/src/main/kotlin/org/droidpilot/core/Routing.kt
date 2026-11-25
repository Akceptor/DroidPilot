package org.droidpilot.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.ArrayDeque

fun monotonicMillis() = System.nanoTime()/1_000_000

class LoopGuard(private val capacity:Int=4096,private val ttl:Long=2000,private val clock:()->Long=::monotonicMillis) {
    private val seen=LinkedHashMap<String,Long>()
    val size get()=seen.size
    @Synchronized fun accept(bytes:ByteArray):Boolean {
        val now=clock()
        seen.entries.removeAll { now-it.value>=ttl }
        val key=java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
        if(seen.containsKey(key)) return false
        seen[key]=now
        while(seen.size>capacity) seen.remove(seen.keys.first())
        return true
    }
}

/** Drop oldest on overload. No packet survives disconnection or its short send deadline. */
class PacketQueue(private val capacity:Int=64,private val ttl:Long=250,private val clock:()->Long=::monotonicMillis) {
    private val entries=ArrayDeque<Pair<Long,ByteArray>>()
    var dropped=0L; private set
    @Synchronized fun offer(bytes:ByteArray) {
        if(entries.size>=capacity) { entries.removeFirst();dropped++ }
        entries.addLast(clock() to bytes.copyOf())
    }
    @Synchronized fun poll():ByteArray? {
        while(entries.isNotEmpty()) {
            val (time,bytes)=entries.removeFirst()
            if(clock()-time<ttl) return bytes
            dropped++
        }
        return null
    }
    @Synchronized fun clear() { entries.clear() }
}
class Backoff {
    private var value=250L
    fun next():Long = value.also { value=(value*2).coerceAtMost(8000) }
    fun reset() { value=250 }
}
data class Traffic(
    val rxBytes:Long=0,val txBytes:Long=0,val rxPackets:Long=0,val txPackets:Long=0,
    val invalid:Long=0,val unverified:Long=0,val loops:Long=0,val drops:Long=0,
    val lastHeartbeat:Long?=null,val vehicleSystem:Int?=null,val vehicleType:Int?=null
)
class Router {
    private val peers=linkedMapOf<String,PacketQueue>()
    private val parsers=mutableMapOf<String,FrameParser>()
    private val guard=LoopGuard()
    private val mutable=MutableStateFlow(Traffic())
    val stats=mutable.asStateFlow()
    @Synchronized fun add(id:String,queue:PacketQueue) { peers[id]=queue;parsers[id]=FrameParser() }
    @Synchronized fun remove(id:String) { peers.remove(id)?.clear();parsers.remove(id) }
    @Synchronized fun receive(id:String,bytes:ByteArray) {
        val parser=parsers[id] ?: return
        val oldInvalid=parser.invalid;val oldUnverified=parser.unverified
        var s=mutable.value.copy(rxBytes=mutable.value.rxBytes+bytes.size)
        for(frame in parser.feed(bytes)) {
            s=s.copy(rxPackets=s.rxPackets+1)
            if(frame.heartbeatType!=null && frame.autopilot==3) s=s.copy(lastHeartbeat=System.currentTimeMillis(),vehicleSystem=frame.systemId,vehicleType=frame.heartbeatType)
            if(!guard.accept(frame.bytes)) { s=s.copy(loops=s.loops+1);continue }
            peers.filterKeys { it!=id }.values.forEach { it.offer(frame.bytes) }
        }
        mutable.value=s.copy(invalid=s.invalid+parser.invalid-oldInvalid,unverified=s.unverified+parser.unverified-oldUnverified,drops=peers.values.sumOf { it.dropped })
    }
    /** Trusted in-process services only. Does not rewrite or generate messages. */
    @Synchronized fun inject(frame:ByteArray) {
        if(guard.accept(frame)) peers.values.forEach { it.offer(frame) }
    }
    @Synchronized fun sent(bytes:Int) { mutable.value=mutable.value.copy(txBytes=mutable.value.txBytes+bytes,txPackets=mutable.value.txPackets+1) }
}
