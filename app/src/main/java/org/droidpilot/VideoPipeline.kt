package org.droidpilot

import android.content.Context
import android.media.*
import android.os.*
import android.view.Surface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import org.droidpilot.core.*
import java.io.Closeable
import java.net.*

fun encoderReport():String = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { t->t==MediaFormat.MIMETYPE_VIDEO_AVC || t==MediaFormat.MIMETYPE_VIDEO_HEVC } }.joinToString("\n") { info->
    "${info.name}: ${info.supportedTypes.joinToString()}${if(Build.VERSION.SDK_INT>=29) " hardware=${info.isHardwareAccelerated}" else " hardware status unavailable on API 28"}"
}

class VideoPipeline(private val context:Context,private val config:PilotConfig):Closeable {
    @Volatile private var source:VideoSource?=null
    @Volatile private var encoder:MediaCodec?=null
    @Volatile private var surface:Surface?=null
    @Volatile private var socket:DatagramSocket?=null
    private val queue=EncodedFrameQueue()
    private var sent=0L
    suspend fun run():Unit=coroutineScope {
        val codecInfo=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info->
            info.isEncoder && info.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC) &&
                (if(Build.VERSION.SDK_INT>=29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")) &&
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).let { caps->
                    caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) && caps.videoCapabilities?.areSizeAndRateSupported(config.width,config.height,config.fps.toDouble())==true
                } }.getOrDefault(false)
        } ?: error("No compatible hardware H.264 surface encoder")
        val codec=MediaCodec.createByCodecName(codecInfo.name);encoder=codec
        val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,config.width,config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE,config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE,config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,config.keyframeSeconds)
            setInteger(MediaFormat.KEY_PRIORITY,0)
            setInteger(MediaFormat.KEY_LATENCY,0) // Optional hint; vendor codecs may ignore it.
        }
        codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface=codec.createInputSurface();codec.start()
        val camera=videoSource(context,config);source=camera
        camera.start(listOf(surface!!))
        val destination=InetSocketAddress(InetAddress.getByName(config.videoHost),config.videoPort)
        val rtp=H264Rtp()
        val sender=launch(Dispatchers.IO) {
            val backoff=Backoff()
            while(isActive) {
                try {
                    DatagramSocket().use { opened->
                        socket=opened
                        while(isActive) {
                            val unit=queue.poll()
                            if(unit==null) { delay(3);continue }
                            for(packet in rtp.packetize(unit.nals,unit.pts*90/1000)) opened.send(DatagramPacket(packet,packet.size,destination))
                            sent++
                        }
                    }
                } catch(e:CancellationException) { throw e } catch(e:Exception) {
                    AppState.log("RTP send: ${e.message}");queue.invalidate();delay(backoff.next())
                } finally { socket=null }
            }
        }
        try {
            val info=MediaCodec.BufferInfo();var parameterSets=emptyList<ByteArray>();var lastRequest=0L;var lastReport=0L
            while(isActive) {
                if(camera.failure.isCompleted) throw camera.failure.await()
                val now=monotonicMillis()
                if(queue.needsKeyframe && now-lastRequest>500) {
                    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0) });lastRequest=now
                }
                val index=codec.dequeueOutputBuffer(info,10000)
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out=codec.outputFormat
                    parameterSets=listOf("csd-0","csd-1").flatMap { key->out.getByteBuffer(key)?.let { b->
                        val copy=b.duplicate();splitAnnexB(ByteArray(copy.remaining()).also { copy.get(it) })
                    } ?: emptyList() }
                } else if(index>=0) {
                    try {
                        val buffer=codec.getOutputBuffer(index)
                        if(buffer!=null && info.size>0) {
                            buffer.position(info.offset);buffer.limit(info.offset+info.size)
                            val nals=splitAnnexB(ByteArray(info.size).also { buffer.get(it) })
                            if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0) parameterSets=nals
                            else {
                                val key=info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME!=0 || nals.any { (it[0].toInt() and 31)==5 }
                                queue.offer(EncodedFrame(if(key) parameterSets+nals else nals,info.presentationTimeUs,key))
                            }
                        }
                    } finally { codec.releaseOutputBuffer(index,false) }
                }
                if(now-lastReport>1000) {
                    AppState.state.update { it.copy(video="H.264 RTP ${config.width}x${config.height} • $sent frames sent • ${queue.dropped} dropped") };lastReport=now
                }
            }
        } finally { sender.cancel();socket?.close();sender.join();close() }
    }
    @Synchronized override fun close() {
        source?.close();source=null
        socket?.close();socket=null
        encoder?.let { runCatching { it.stop() };runCatching { it.release() } };encoder=null
        surface?.release();surface=null
        queue.invalidate()
    }
}
