package com.no5.helm.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.EGL14.*
import android.opengl.GLES30.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class VideoViewFrame(val heading: Float=0f,val fog: Float=0f)

/** Owns a hardware decoder -> SurfaceTexture -> external GLES texture, never CPU video bitmaps. */
internal class VideoTextureView(context: Context,private val uri: String,private val position: AtomicLong,
    private val mask: (Int,Int)->Bitmap,private val ready:(Boolean)->Unit,
    private val report:(String,Boolean)->Unit): TextureView(context),TextureView.SurfaceTextureListener {
    private var worker: Worker?=null
    private var disposed=false
    private var generation=0L
    private var first=false
    private var frame=VideoViewFrame()
    init {isOpaque=false;isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;alpha=0f;surfaceTextureListener=this}
    fun submit(value: VideoViewFrame){frame=value;worker?.submit(value,width,height)}
    fun dispose(){disposed=true;generation++;ready(false);worker?.stop(false)}
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture,w: Int,h: Int){
        if(disposed)return
        first=false;alpha=0f;ready(false)
        val ownerGeneration=++generation
        worker=Worker(context.applicationContext,texture,uri,position,mask){message,failure->
            post {if(!disposed&&generation==ownerGeneration){if(failure){alpha=0f;ready(false)};report(message,failure)}}
        }.also {it.submit(frame,w,h)}
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture,w: Int,h: Int){first=false;alpha=0f;ready(false);worker?.submit(frame,w,h)}
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture){if(!disposed&&!first){first=true;alpha=1f;ready(true)}}
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        generation++;ready(false);first=false;val owner=worker;worker=null
        if(owner==null)return true
        owner.stop(true);return false
    }
    private data class Request(val frame: VideoViewFrame,val width: Int,val height: Int)
    private class Worker(private val context: Context,private val output: SurfaceTexture,private val uri: String,
        private val position: AtomicLong,private val mask:(Int,Int)->Bitmap,private val report:(String,Boolean)->Unit) {
        private val thread=HandlerThread("No5VideoGL").apply{start()}
        private val handler=Handler(thread.looper)
        private val stopped=AtomicBoolean(false)
        private val latest=AtomicReference<Request?>()
        private val ownership=Any();private var releaseOutput=false;private var finished=false
        private var display=EGL_NO_DISPLAY;private var eglContext=EGL_NO_CONTEXT;private var eglSurface=EGL_NO_SURFACE
        private var windowSurface: Surface?=null;private var decoderSurface: Surface?=null
        private var inputTexture: SurfaceTexture?=null;private var gl: VideoGl?=null
        private var extractor: MediaExtractor?=null;private var codec: MediaCodec?=null
        private val timeline=VideoTimeline();private val info=MediaCodec.BufferInfo();private val matrix=FloatArray(16)
        private var pending=-1;private var pendingPts=0L;private var inputEnded=false
        private var newFrame=false;private var hasFrame=false;private var w=0;private var h=0
        private var resumeAt=position.get().coerceAtLeast(0)
        private val scheduled=AtomicBoolean(false);private var frames=0;private var loops=0;private var reportAt=System.nanoTime()
        fun submit(frame: VideoViewFrame,width: Int,height: Int){
            latest.set(Request(frame,width,height))
            if(!stopped.get()&&scheduled.compareAndSet(false,true))handler.post(pump)
        }
        private val pump=object:Runnable {
            override fun run(){
                if(stopped.get())return
                try {
                    val request=latest.get()
                    if(request!=null&&request.width>0&&request.height>0){
                        if(codec==null)initialize()
                        if(w!=request.width||h!=request.height){
                            w=request.width;h=request.height
                            val bitmap=mask(w,h);try{gl!!.resize(w,h,bitmap)}finally{bitmap.recycle()}
                        }
                        decode()
                        if(newFrame){inputTexture!!.updateTexImage();inputTexture!!.getTransformMatrix(matrix);newFrame=false;hasFrame=true}
                        if(hasFrame){
                            gl!!.draw(w,h,matrix,request.frame.heading,request.frame.fog)
                            check(eglSwapBuffers(display,eglSurface)){"Video eglSwapBuffers 0x${eglGetError().toString(16)}"}
                        }
                    }
                    if(!stopped.get())handler.postDelayed(this,16)
                }catch(t:Exception){
                    val detail=when(t){is MediaCodec.CodecException->t.diagnosticInfo;is SecurityException->"Dateizugriff fehlt oder wurde widerrufen; Datei erneut auswählen";else->t.message?.take(240)?:t.javaClass.simpleName}
                    Log.e("No5Video","Video failed: $detail",t)
                    report("Video: $detail · Bild-Rückfall",true);stop(false)
                }
            }
        }
        private fun initialize(){
            check(Uri.parse(uri).scheme=="content"){"Only user-selected content documents are supported"}
            display=eglGetDisplay(EGL_DEFAULT_DISPLAY)
            check(display!=EGL_NO_DISPLAY&&eglInitialize(display,IntArray(2),0,IntArray(2),0)){"No EGL display"}
            val configs=arrayOfNulls<android.opengl.EGLConfig>(1);val count=IntArray(1)
            check(eglChooseConfig(display,intArrayOf(EGL_RENDERABLE_TYPE,0x40,EGL_SURFACE_TYPE,EGL_WINDOW_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE),0,configs,0,1,count,0)&&count[0]>0){"No GLES3 RGBA8 video config"}
            eglContext=eglCreateContext(display,configs[0],EGL_NO_CONTEXT,intArrayOf(EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE),0)
            check(eglContext!=EGL_NO_CONTEXT){"Video GLES3 context unavailable"}
            windowSurface=Surface(output);eglSurface=eglCreateWindowSurface(display,configs[0],windowSurface,intArrayOf(EGL_NONE),0)
            check(eglSurface!=EGL_NO_SURFACE&&eglMakeCurrent(display,eglSurface,eglSurface,eglContext)){"Video EGL window unavailable"}
            eglSwapInterval(display,1)
            gl=VideoGl(context.assets)
            inputTexture=SurfaceTexture(gl!!.videoTexture).apply{setOnFrameAvailableListener({newFrame=true},handler)}
            decoderSurface=Surface(inputTexture)
            val source=MediaExtractor().also{extractor=it;it.setDataSource(context,Uri.parse(uri),null)}
            val track=(0 until source.trackCount).firstOrNull{source.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true}
                ?:error("No video track")
            val format=source.getTrackFormat(track);val mime=format.getString(MediaFormat.KEY_MIME)?:error("Missing video MIME")
            val width=format.getInteger(MediaFormat.KEY_WIDTH);val height=format.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation=if(format.containsKey(MediaFormat.KEY_ROTATION))format.getInteger(MediaFormat.KEY_ROTATION) else 0
            check(VideoPolicy.validPanorama(width,height,rotation)){"Benötigt ein ungedrehtes 2:1-Monopanorama; Datei ist ${width}x${height}, Rotation $rotation"}
            val maxTexture=IntArray(1);glGetIntegerv(GL_MAX_TEXTURE_SIZE,maxTexture,0)
            check(width<=maxTexture[0] && height<=maxTexture[0]) { "Video exceeds GPU texture limit ${maxTexture[0]}: ${width}x${height}" }
            val fps=if(format.containsKey(MediaFormat.KEY_FRAME_RATE))(format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() ?: 0.0) else 0.0
            val candidates=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter{!it.isEncoder&&it.isHardwareAccelerated&&mime in it.supportedTypes}.filter{
                runCatching{val caps=it.getCapabilitiesForType(mime);caps.isFormatSupported(format)&&
                    (if(fps>0)caps.videoCapabilities.areSizeAndRateSupported(width,height,fps) else caps.videoCapabilities.isSizeSupported(width,height))}.getOrDefault(false)
            }
            check(candidates.isNotEmpty()){"Kein gemeldeter Hardwaredecoder für $mime ${width}x${height} ${if(fps>0)"${fps}fps" else "FPS unbekannt"}; kleinere 2:1-Datei wählen"}
            source.selectTrack(track)
            val duration=if(format.containsKey(MediaFormat.KEY_DURATION))format.getLong(MediaFormat.KEY_DURATION) else 0L
            if(duration>0)resumeAt=resumeAt.coerceAtMost((duration-1).coerceAtLeast(0))
            source.seekTo(resumeAt,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val selected=candidates.first()
            codec=MediaCodec.createByCodecName(selected.name).also{it.configure(format,decoderSurface,null,0);it.start()}
            val description="360° Video · ${selected.name} · ${width}x${height} · ${if(fps>0)"${fps}fps Quelle" else "FPS unbekannt"} · stumm, Wiederholung"
            report(description,false)
            Log.i("No5Video","$description; GLES ${glGetString(GL_VERSION)} / ${glGetString(GL_RENDERER)}; capability report, not measured throughput")
            reportAt=System.nanoTime()
        }
        private fun decode(){
            val decoder=codec!!;val source=extractor!!
            repeat(4){
                if(!inputEnded){
                    val index=decoder.dequeueInputBuffer(0)
                    if(index>=0){
                        val buffer=decoder.getInputBuffer(index)?:error("Missing decoder input buffer");buffer.clear()
                        val size=source.readSampleData(buffer,0)
                        if(size<0){decoder.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                        else{decoder.queueInputBuffer(index,0,size,source.sampleTime,0);source.advance()}
                    }
                }
                if(pending<0){
                    val index=decoder.dequeueOutputBuffer(info,0)
                    if(index>=0){
                        if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0){
                            decoder.releaseOutputBuffer(index,false);decoder.flush();source.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            inputEnded=false;resumeAt=0;position.set(0);timeline.reset();loops++
                            Log.i("No5Video","loop=$loops (source may have a visible cut)");return
                        }
                        if(info.presentationTimeUs<resumeAt){decoder.releaseOutputBuffer(index,false)}
                        else{pending=index;pendingPts=info.presentationTimeUs}
                    }
                }
                if(pending>=0&&timeline.due(pendingPts,System.nanoTime())){
                    decoder.releaseOutputBuffer(pending,true);position.set(pendingPts);pending=-1;frames++
                }
            }
            val now=System.nanoTime()
            if(now-reportAt>=5_000_000_000L){
                Log.i("No5Video","decoded/released=$frames in ${(now-reportAt)/1e9}s loops=$loops (not display FPS or GPU time)")
                frames=0;reportAt=now
            }
        }
        fun stop(release: Boolean){
            synchronized(ownership){releaseOutput=releaseOutput||release;if(finished&&releaseOutput){output.release();releaseOutput=false}}
            if(!stopped.compareAndSet(false,true))return
            handler.removeCallbacks(pump)
            handler.post{
                runCatching{codec?.stop()};runCatching{codec?.release()};codec=null
                runCatching{extractor?.release()};extractor=null
                runCatching{decoderSurface?.release()};runCatching{inputTexture?.release()};runCatching{gl?.close()}
                if(display!=EGL_NO_DISPLAY){
                    eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT)
                    if(eglSurface!=EGL_NO_SURFACE)eglDestroySurface(display,eglSurface)
                    if(eglContext!=EGL_NO_CONTEXT)eglDestroyContext(display,eglContext)
                    eglTerminate(display);eglReleaseThread()
                }
                runCatching{windowSurface?.release()}
                synchronized(ownership){finished=true;if(releaseOutput){output.release();releaseOutput=false}}
                thread.quitSafely()
            }
        }
    }
}
