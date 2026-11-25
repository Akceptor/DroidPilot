package org.droidpilot

import android.opengl.*
import android.view.Surface
import kotlinx.coroutines.*
import org.droidpilot.core.PilotConfig
import java.util.concurrent.Executors

/** Explicit development source. It never supplies telemetry or simulated connection status. */
class SyntheticSource(private val config:PilotConfig):VideoSource {
    override val failure=CompletableDeferred<Throwable>()
    private val dispatcher=Executors.newSingleThreadExecutor { task->Thread(task,"Synthetic video").apply { priority=Thread.MIN_PRIORITY } }.asCoroutineDispatcher()
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    override suspend fun start(surfaces:List<Surface>) {
        require(surfaces.size==1)
        val ready=CompletableDeferred<Unit>()
        scope.launch {
            var display=EGL14.EGL_NO_DISPLAY
            var eglContext=EGL14.EGL_NO_CONTEXT
            var window=EGL14.EGL_NO_SURFACE
            try {
                display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),1))
                val configs=arrayOfNulls<EGLConfig>(1)
                val attrs=intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE)
                val count=IntArray(1)
                check(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0) && count[0]>0)
                eglContext=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
                window=EGL14.eglCreateWindowSurface(display,configs[0],surfaces[0],intArrayOf(EGL14.EGL_NONE),0)
                check(EGL14.eglMakeCurrent(display,window,window,eglContext))
                GLES20.glViewport(0,0,config.width,config.height)
                ready.complete(Unit)
                var frame=0L
                while(isActive) {
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                    GLES20.glClearColor(0.05f,0.1f,0.3f,1f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                    GLES20.glScissor(((frame*8)%config.width).toInt(),config.height/3,config.width/8,config.height/3)
                    GLES20.glClearColor(0.1f,0.9f,0.5f,1f);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    EGLExt.eglPresentationTimeANDROID(display,window,System.nanoTime())
                    check(EGL14.eglSwapBuffers(display,window))
                    frame++;delay((1000L/config.fps).coerceAtLeast(1))
                }
            } catch(e:CancellationException) { throw e } catch(e:Throwable) { ready.completeExceptionally(e);failure.complete(e) }
            finally {
                if(display!=EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display,window);EGL14.eglDestroyContext(display,eglContext);EGL14.eglTerminate(display)
                }
            }
        }
        ready.await()
    }
    override fun close() {
        scope.cancel()
        scope.coroutineContext[Job]?.invokeOnCompletion { dispatcher.close() }
    }
}
fun videoSource(context:android.content.Context,config:PilotConfig):VideoSource =
    if(config.cameraId=="@synthetic") SyntheticSource(config) else Camera2Source(context,config)
