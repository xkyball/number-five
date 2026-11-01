package com.no5.helm.sonar.gl

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14.*
import android.opengl.GLES30.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

/** Plot-only GLES3 view. The native SONAR header, A-scope and controls remain outside it. */
internal class SonarReliefTextureView(context: Context,
    private val ready: (Boolean) -> Unit, private val failed: (String) -> Unit) : TextureView(context), TextureView.SurfaceTextureListener {
    private var worker: Worker? = null
    private var frame: ReliefFrame? = null
    private var disposed = false
    private data class Flight(val ticket:Long,val frame:ReliefFrame)
    private val inFlight=AtomicReference<Flight?>()
    @Volatile internal var presentedFrame:ReliefFrame?=null;private set
    @Volatile private var firstSubmittedAtNanos=0L
    @Volatile private var lastSubmittedAtNanos=0L
    @Volatile private var lastPresentedAtNanos=0L
    @Volatile private var lastError:String?=null
    private val presentation=no5.sonar.windowrecovery.WindowBufferPresentation(this,object:no5.sonar.windowrecovery.WindowBufferPresentation.Listener{
        override fun viewportReady(){worker?.wake()}
        override fun presented(ticket:Long){
            val flight=inFlight.get()?.takeIf{it.ticket==ticket}?:return
            if(!disposed&&inFlight.compareAndSet(flight,null)){
                presentedFrame=flight.frame;lastPresentedAtNanos=System.nanoTime()
                val current=frame
                ready(current!=null&&current.data.source===flight.frame.data.source&&current.data.generation==flight.frame.data.generation)
                worker?.wake()
            }
        }
        override fun discarded(ticket:Long){inFlight.get()?.takeIf{it.ticket==ticket}?.let{inFlight.compareAndSet(it,null)};worker?.wake()}
    })
    internal fun isActive()=!disposed&&isAttachedToWindow&&isShown
    internal fun hasFailure()=lastError!=null
    internal fun presentationOverdue(now:Long)=com.no5.helm.gpu.SonarRenderHealth.presentationOverdue(lastSubmittedAtNanos,lastPresentedAtNanos,firstSubmittedAtNanos,now)
    // Keep the TextureView consumer drawable during bootstrap and resize.
    // Readiness requires the matching consumed texture and committed Window.
    init { isOpaque=false; isClickable=false; isFocusable=false; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO; surfaceTextureListener=this; alpha=1f }
    fun submit(value: ReliefFrame) {
        if(disposed)return
        frame=value;lastSubmittedAtNanos=System.nanoTime();if(firstSubmittedAtNanos==0L)firstSubmittedAtNanos=lastSubmittedAtNanos
        worker?.submit(value,width,height)
    }
    fun dispose() { disposed=true;alpha=0f;ready(false);presentation.close();inFlight.set(null);presentedFrame=null;frame=null;worker?.stop(false) }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture,width: Int,height: Int) {
        if(disposed) return
        presentation.viewportChanged();alpha=1f;ready(false)
        worker=Worker(context.applicationContext,texture) { message ->
            post { if(!disposed) {lastError=message;alpha=0f;ready(false);failed(message)} }
        }.also { owner -> frame?.let { owner.submit(it,width,height) } }
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,width: Int,height: Int) {
        // An old viewport cannot acknowledge readiness of the resized Window.
        presentation.viewportChanged();alpha=1f;ready(false);frame?.let { worker?.submit(it,width,height) }
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if(!disposed)presentation.textureUpdated()
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        ready(false);presentation.surfaceDestroyed();inFlight.set(null);presentedFrame=null
        val owner=worker; worker=null
        if(owner==null) return true
        owner.stop(true) // EGL is torn down before its SurfaceTexture is released, off the UI thread.
        return false
    }
    private data class Request(val frame: ReliefFrame,val width: Int,val height: Int)
    private inner class Worker(context: Context,private val texture: SurfaceTexture,
        private val failed: (String)->Unit) {
        private val thread=HandlerThread("No5SonarReliefGL").apply { start() }
        private val handler=Handler(thread.looper)
        private val latest=AtomicReference<Request?>()
        private val scheduled=AtomicBoolean(false)
        private val stopped=AtomicBoolean(false)
        private val ownership=Any()
        private var releaseTexture=false
        private var finished=false
        private var display=EGL_NO_DISPLAY
        private var eglContext=EGL_NO_CONTEXT
        private var surface=EGL_NO_SURFACE
        private var nativeSurface: Surface?=null
        private var renderer: SonarReliefRenderer?=null
        private val assets=context.assets
        private var w=0; private var h=0
        private var samples=0; private var cpuNanos=0L; private var swapNanos=0L; private var maxNanos=0L
        private var reportAt=System.nanoTime()+5_000_000_000L
        private val draw=object : Runnable {
            override fun run() {
                try {
                    if(stopped.get()) return
                    if(!presentation.ready()||inFlight.get()!=null)return
                    val request=latest.getAndSet(null) ?: return
                    if(request.width<=0 || request.height<=0) return
                    if(renderer==null) initialize()
                    if(stopped.get()) return
                    if(w!=request.width || h!=request.height) {
                        w=request.width; h=request.height
                        renderer!!.resize(w,h)
                    }
                    val begin=System.nanoTime()
                    if(!renderer!!.draw(request.frame))return
                    val submitted=System.nanoTime()
                    val data=request.frame.data
                    if(data.source.readLocked{data.source.lifecycleGeneration!=data.generation})return
                    val ticket=presentation.beginSubmission()
                    if(ticket==0L){latest.compareAndSet(null,request);return}
                    val flight=Flight(ticket,request.frame)
                    if(!inFlight.compareAndSet(null,flight)){presentation.failedSubmission(ticket);return}
                    if(!eglSwapBuffers(display,surface)){
                        presentation.failedSubmission(ticket);inFlight.compareAndSet(flight,null)
                        error("eglSwapBuffers: 0x${eglGetError().toString(16)}")
                    }
                    presentation.completeSubmission(ticket)
                    val end=System.nanoTime()
                    samples++; cpuNanos+=submitted-begin; swapNanos+=end-submitted; maxNanos=maxOf(maxNanos,end-begin)
                    if(end>=reportAt) {
                        Log.i("No5SonarRelief",String.format(Locale.ROOT,
                            "GLES measured samples=%d extent=%dx%d CPU-submit-mean=%.2fms swap-wait-mean=%.2fms max=%.2fms (not isolated GPU time)",
                            samples,w,h,cpuNanos/1e6/samples,swapNanos/1e6/samples,maxNanos/1e6))
                        samples=0; cpuNanos=0; swapNanos=0; maxNanos=0; reportAt=end+5_000_000_000L
                    }
                } catch(t: OutOfMemoryError) {
                    inFlight.getAndSet(null)?.let{presentation.failedSubmission(it.ticket)}
                    failed("Relief memory pressure · native 2D fallback");stop(false)
                } catch(t: Exception) {
                    inFlight.getAndSet(null)?.let{presentation.failedSubmission(it.ticket)}
                    Log.e("No5SonarRelief","GLES failed; preserving native 2D echogram",t)
                    failed("GLES: ${t.message?.take(180)} · native 2D fallback")
                    stop(false)
                } finally {
                    scheduled.set(false)
                    if(!stopped.get()&&latest.get()!=null&&presentation.ready()&&inFlight.get()==null)schedule()
                }
            }
        }
        fun submit(frame: ReliefFrame,width: Int,height: Int) {
            if(stopped.get()) return
            latest.set(Request(frame,width,height)); schedule()
        }
        fun wake(){if(!stopped.get()&&latest.get()!=null)schedule()}
        private fun schedule() { if(scheduled.compareAndSet(false,true)) handler.post(draw) }
        private fun initialize() {
            display=eglGetDisplay(EGL_DEFAULT_DISPLAY)
            check(display!=EGL_NO_DISPLAY && eglInitialize(display,IntArray(2),0,IntArray(2),0)) { "No EGL display" }
            val configs=arrayOfNulls<android.opengl.EGLConfig>(1); val count=IntArray(1)
            val attributes=intArrayOf(EGL_RENDERABLE_TYPE,0x40,EGL_SURFACE_TYPE,EGL_WINDOW_BIT,
                EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_DEPTH_SIZE,24,EGL_NONE)
            check(eglChooseConfig(display,attributes,0,configs,0,1,count,0) && count[0]>0) { "No GLES3 RGBA8 EGL config" }
            eglContext=eglCreateContext(display,configs[0],EGL_NO_CONTEXT,intArrayOf(EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE),0)
            check(eglContext!=EGL_NO_CONTEXT) { "GLES3 context unavailable" }
            nativeSurface=Surface(texture)
            surface=eglCreateWindowSurface(display,configs[0],nativeSurface,intArrayOf(EGL_NONE),0)
            check(surface!=EGL_NO_SURFACE && eglMakeCurrent(display,surface,surface,eglContext)) { "EGL window unavailable" }
            eglSwapInterval(display,1)
            Log.i("No5SonarRelief","GLES ${glGetString(GL_VERSION)} renderer=${glGetString(GL_RENDERER)}")
            renderer=SonarReliefRenderer(assets)
            renderer!!.prepare()
        }
        fun stop(release: Boolean) {
            synchronized(ownership) {
                releaseTexture=releaseTexture || release
                if(finished && releaseTexture) { texture.release(); releaseTexture=false }
            }
            if(!stopped.compareAndSet(false,true)) return
            latest.set(null)
            handler.post {
                try { renderer?.close() } finally {
                    if(display!=EGL_NO_DISPLAY) {
                        eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT)
                        if(surface!=EGL_NO_SURFACE) eglDestroySurface(display,surface)
                        if(eglContext!=EGL_NO_CONTEXT) eglDestroyContext(display,eglContext)
                        eglTerminate(display); eglReleaseThread()
                    }
                    nativeSurface?.release(); renderer=null
                    synchronized(ownership) {
                        finished=true
                        if(releaseTexture) { texture.release(); releaseTexture=false }
                    }
                    thread.quitSafely()
                }
            }
        }
    }
}
