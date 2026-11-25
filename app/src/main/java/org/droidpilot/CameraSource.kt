package org.droidpilot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.graphics.Rect
import android.media.MediaCodec
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import kotlinx.coroutines.*
import org.droidpilot.core.PilotConfig
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface VideoSource:Closeable {
    suspend fun start(surfaces:List<Surface>)
    val failure:CompletableDeferred<Throwable>
}
data class CameraInfo(val id:String,val description:String,val sizes:List<String>,val fps:String)
fun cameras(context:Context):List<CameraInfo> {
    val manager=context.getSystemService(CameraManager::class.java)
    return manager.cameraIdList.map { id->
        val c=manager.getCameraCharacteristics(id)
        val facing=when(c[CameraCharacteristics.LENS_FACING]) { CameraCharacteristics.LENS_FACING_FRONT->"Front";CameraCharacteristics.LENS_FACING_BACK->"Rear";else->"External/other" }
        CameraInfo(id,"$id • $facing • sensor ${c[CameraCharacteristics.SENSOR_ORIENTATION]}°",
            c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(MediaCodec::class.java)?.map { "${it.width}x${it.height}" } ?: emptyList(),
            c[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.joinToString() ?: "Unknown")
    }
}
fun cameraReport(context:Context):String = context.getSystemService(CameraManager::class.java).let { manager->
    cameras(context).joinToString("\n\n") { info->
        val c=manager.getCameraCharacteristics(info.id)
        "${info.description}\nEncoder sizes: ${info.sizes.joinToString()}\nFPS: ${info.fps}\nAF modes: ${c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES]?.joinToString()}\nMinimum focus diopters: ${c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]}\nExposure compensation: ${c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]}\nStabilization: ${c[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]?.joinToString()}\nMax zoom: ${c[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM]}"
    }
}

class Camera2Source(private val context:Context,private val config:PilotConfig):VideoSource {
    private val thread=HandlerThread("Camera2 capture").apply { start() }
    private val handler=Handler(thread.looper)
    // State callbacks must remain deliverable after close during asynchronous acquisition.
    private val stateHandler=Handler(android.os.Looper.getMainLooper())
    private val resourceLock=Any()
    private var closed=false
    @Volatile private var device:CameraDevice?=null
    @Volatile private var session:CameraCaptureSession?=null
    override val failure=CompletableDeferred<Throwable>()
    @SuppressLint("MissingPermission")
    override suspend fun start(surfaces:List<Surface>) {
        check(context.checkSelfPermission(Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED) { "Camera permission required" }
        val manager=context.getSystemService(CameraManager::class.java)
        val id=config.cameraId.ifBlank { manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING]==CameraCharacteristics.LENS_FACING_BACK } ?: manager.cameraIdList.firstOrNull() ?: error("No Camera2 camera") }
        val characteristics=manager.getCameraCharacteristics(id)
        val sizes=characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(MediaCodec::class.java) ?: error("No encoder surface sizes")
        require(sizes.any { it.width==config.width && it.height==config.height }) { "Camera $id does not support ${config.width}x${config.height}" }
        val opened=suspendCancellableCoroutine<CameraDevice> { continuation->
            manager.openCamera(id,object:CameraDevice.StateCallback() {
                override fun onOpened(camera:CameraDevice) {
                    synchronized(resourceLock) {
                        if(closed || !continuation.isActive) camera.close()
                        else { device=camera;continuation.resume(camera) { _,value,_->value.close() } }
                    }
                }
                override fun onDisconnected(camera:CameraDevice) { camera.close();val e=IllegalStateException("Camera disconnected");failure.complete(e);if(continuation.isActive) continuation.resumeWithException(e) }
                override fun onError(camera:CameraDevice,error:Int) { camera.close();val e=IllegalStateException("Camera error $error");failure.complete(e);if(continuation.isActive) continuation.resumeWithException(e) }
            },stateHandler)
        }
        currentCoroutineContext().ensureActive()
        val capture=suspendCancellableCoroutine<CameraCaptureSession> { continuation->
            opened.createCaptureSession(surfaces,object:CameraCaptureSession.StateCallback() {
                override fun onConfigured(value:CameraCaptureSession) {
                    synchronized(resourceLock) {
                        if(closed || !continuation.isActive) value.close()
                        else { session=value;continuation.resume(value) { _,resource,_->resource.close() } }
                    }
                }
                override fun onConfigureFailed(value:CameraCaptureSession) { value.close();if(continuation.isActive) continuation.resumeWithException(IllegalStateException("Unsupported camera surface combination")) }
            },stateHandler)
        }
        currentCoroutineContext().ensureActive()
        val request=opened.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        surfaces.forEach(request::addTarget)
        request.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO)
        val ranges=characteristics[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES].orEmpty()
        val range=ranges.filter { it.contains(config.fps) }.minByOrNull { it.upper-it.lower } ?: error("Unsupported camera FPS ${config.fps}")
        request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,range)
        val af=characteristics[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        if(config.autofocus && af.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) request.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        else if(!config.autofocus) {
            require(af.contains(CaptureRequest.CONTROL_AF_MODE_OFF)) { "Manual focus unavailable" }
            val max=characteristics[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f
            require(config.focusDistance<=max) { "Focus distance exceeds capability" }
            request.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF)
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE,config.focusDistance)
        }
        val exposure=characteristics[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE] ?: Range(0,0)
        require(exposure.contains(config.exposure)) { "Exposure compensation out of range $exposure" }
        request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,config.exposure)
        if(config.stabilization) {
            require((characteristics[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()).contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) { "Video stabilization unavailable" }
            request.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }
        require(config.zoom<=(characteristics[CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM] ?: 1f)) { "Zoom exceeds capability" }
        characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]?.let { sensor->
            val w=(sensor.width()/config.zoom).toInt();val h=(sensor.height()/config.zoom).toInt()
            val left=sensor.left+(sensor.width()-w)/2;val top=sensor.top+(sensor.height()-h)/2
            request.set(CaptureRequest.SCALER_CROP_REGION,Rect(left,top,left+w,top+h))
        }
        capture.setRepeatingRequest(request.build(),object:CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(session:CameraCaptureSession,request:CaptureRequest,result:CaptureFailure) { failure.complete(IllegalStateException("Capture failed: ${result.reason}")) }
        },handler)
    }
    override fun close() = synchronized(resourceLock) {
        closed=true
        runCatching { session?.close() };session=null
        runCatching { device?.close() };device=null
        thread.quitSafely()
        Unit
    }
}
