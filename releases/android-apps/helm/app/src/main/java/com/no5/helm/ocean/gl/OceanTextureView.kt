package com.no5.helm.ocean.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14.*
import android.opengl.GLES30.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.no5.helm.ocean.OceanFrame
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

/** TextureView is bounded to the authored ocean rectangle, never a full-stage GL surface. */
internal class OceanTextureView(context: Context, private val mask: (Int,Int) -> Bitmap,
    private val ready: (Boolean) -> Unit, private val failed: (String) -> Unit) : TextureView(context), TextureView.SurfaceTextureListener {
    private var worker: Worker? = null
    private var frame = OceanFrame()
    private var disposed = false
    private var firstBuffer = false
    init { isOpaque=false; isClickable=false; isFocusable=false; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO; surfaceTextureListener=this; alpha=0f }
    fun submit(value: OceanFrame) { frame=value; worker?.submit(value,width,height) }
    fun dispose() { disposed=true; ready(false); worker?.stop(false) }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture,width: Int,height: Int) {
        if(disposed) return
        firstBuffer=false; alpha=0f; ready(false)
        worker=Worker(context.applicationContext,texture,mask) { message ->
            post { if(!disposed) { alpha=0f; ready(false); failed(message) } }
        }.also { it.submit(frame,width,height) }
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,width: Int,height: Int) {
        // Hide an old-sized buffer until the first correctly resized image has arrived.
        alpha=0f; firstBuffer=false; ready(false); worker?.submit(frame,width,height)
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if(!disposed && !firstBuffer) { firstBuffer=true; alpha=1f; ready(true) }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        ready(false); firstBuffer=false
        val owner=worker; worker=null
        if(owner==null) return true
        owner.stop(true) // EGL is torn down before its SurfaceTexture is released, off the UI thread.
        return false
    }
    private data class Request(val frame: OceanFrame,val width: Int,val height: Int)
    private class Worker(context: Context,private val texture: SurfaceTexture,
        private val mask: (Int,Int)->Bitmap,private val failed: (String)->Unit) {
        private val thread=HandlerThread("No5OceanGL").apply { start() }
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
        private var renderer: OceanGlRenderer?=null
        private val assets=context.assets
        private var w=0; private var h=0
        private var samples=0; private var cpuNanos=0L; private var swapNanos=0L; private var maxNanos=0L
        private var reportAt=System.nanoTime()+5_000_000_000L
        private val draw=object : Runnable {
            override fun run() {
                try {
                    if(stopped.get()) return
                    val request=latest.getAndSet(null) ?: return
                    if(request.width<=0 || request.height<=0) return
                    if(renderer==null) initialize()
                    if(stopped.get()) return
                    if(w!=request.width || h!=request.height) {
                        w=request.width; h=request.height
                        val bitmap=mask(w,h)
                        try { renderer!!.resize(w,h,bitmap,request.frame.quality) } finally { bitmap.recycle() }
                    }
                    val begin=System.nanoTime()
                    renderer!!.draw(request.frame)
                    val submitted=System.nanoTime()
                    check(eglSwapBuffers(display,surface)) { "eglSwapBuffers: 0x${eglGetError().toString(16)}" }
                    val end=System.nanoTime()
                    samples++; cpuNanos+=submitted-begin; swapNanos+=end-submitted; maxNanos=maxOf(maxNanos,end-begin)
                    if(end>=reportAt) {
                        Log.i("No5Ocean",String.format(Locale.ROOT,
                            "GLES measured samples=%d extent=%dx%d CPU-submit-mean=%.2fms swap-wait-mean=%.2fms max=%.2fms (not isolated GPU time)",
                            samples,w,h,cpuNanos/1e6/samples,swapNanos/1e6/samples,maxNanos/1e6))
                        samples=0; cpuNanos=0; swapNanos=0; maxNanos=0; reportAt=end+5_000_000_000L
                    }
                } catch(t: Exception) {
                    Log.e("No5Ocean","GLES failed; preserving supported fallback",t)
                    failed("GLES: ${t.message?.take(300)} · " +
                        if (android.os.Build.VERSION.SDK_INT >= 33) "AGSL/Bild-Rückfall" else "Bild-Rückfall (AGSL erst ab Android 13)")
                    stop(false)
                } finally {
                    scheduled.set(false)
                    if(!stopped.get() && latest.get()!=null) schedule()
                }
            }
        }
        fun submit(frame: OceanFrame,width: Int,height: Int) {
            if(stopped.get()) return
            latest.set(Request(frame,width,height)); schedule()
        }
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
            Log.i("No5Ocean","GLES ${glGetString(GL_VERSION)} renderer=${glGetString(GL_RENDERER)} vendor=${glGetString(GL_VENDOR)} SDK=${android.os.Build.VERSION.SDK_INT}")
            renderer=OceanGlRenderer(assets)
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
