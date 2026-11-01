package com.no5.helm.gpu

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import no5.sonar.windowrecovery.WindowBufferPresentation
import com.no5.domain.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One GL worker and ONE replaceable command; commands hold no copied history.
 * The raw ring is shared. A read lock fences uploads against overwrite/epoch
 * change. At most one 256-ping staging block exists, erased after upload.
 */
internal class PreFenceGpuView(context: Context,composite:Boolean=false): TextureView(context), TextureView.SurfaceTextureListener, AutoCloseable {
    companion object { private val generations=java.util.concurrent.atomic.AtomicLong() }
    val viewGeneration=generations.incrementAndGet()
    private val main=Handler(android.os.Looper.getMainLooper())
    private val thread=HandlerThread("no5-sonar-gl").apply { start() }
    private val worker=Handler(thread.looper)
    private val pending=AtomicReference<SonarGpuFrame?>()
    private var lastSubmitted:SonarGpuFrame?=null
    @Volatile var lastSubmittedAtNanos=0L;private set
    @Volatile private var firstSubmittedAtNanos=0L
    @Volatile var lastPresentedAtNanos=0L;private set
    @Volatile private var lastWorkerProgressAtNanos=0L
    internal fun missingPresentation(now:Long):Boolean = com.no5.helm.watchdog.PresentationLiveness.missing(firstSubmittedAtNanos,lastSubmittedAtNanos,lastPresentedAtNanos,lastWorkerProgressAtNanos,now)
    internal fun makingForwardProgress(now:Long):Boolean = com.no5.helm.watchdog.PresentationLiveness.progressing(lastSubmittedAtNanos,lastPresentedAtNanos,lastWorkerProgressAtNanos,now)
    internal fun presentationOverdue(now:Long):Boolean = SonarRenderHealth.presentationOverdue(
        lastSubmittedAtNanos,lastPresentedAtNanos,firstSubmittedAtNanos,now)
    internal fun hasFailure():Boolean=lastError!=null
    internal fun isActive():Boolean = !closed.get() && isAttachedToWindow && isShown
    private val scheduled=AtomicBoolean(false)
    private val closed=AtomicBoolean(false)
    private class Flight(val frame: SonarGpuFrame, val surfaceGeneration: Long, val ticket: Long) {
        @Volatile var completion: SonarGpuCompletion?=null
        @Volatile var textureSeen=false
        var bufferNotified=false
    }
    private val inFlight=AtomicReference<Flight?>()
    private val needsRender=AtomicBoolean(false)
    @Volatile var lastObservedTextureTimestampNanos=0L;private set
    private val dimensions=IntArray(2)
    private val viewportInfo=FloatArray(7)
    private val previousViewport=FloatArray(6){Float.NaN}
    private var viewportGeneration=0L
    private val presentation=WindowBufferPresentation(this,object:WindowBufferPresentation.Listener {
        override fun viewportReady(){needsRender.set(true);schedule()}
        override fun presented(ticket:Long){
            val flight=inFlight.get()?.takeIf{it.ticket==ticket}?:return
            val stamp=flight.completion?:return
            if(!closed.get()&&inFlight.compareAndSet(flight,null)){
                lastPresentedAtNanos=System.nanoTime();presented=stamp
                com.no5.helm.SonarTrace.phase("sonar.window.present"){com.no5.helm.SonarTrace.localPresentation(stamp.localReceivedAtNanos,stamp.pingId)}
                // A completed empty warm-up is not live measurement evidence.
                // It only admits the first RAW subscription AFTER native setup,
                // texture consumption and the matching hardware Window commit.
                preparationChanged(true)
                onTexturePresented?.invoke(stamp)
                if(needsRender.get())schedule()
            }
        }
        override fun discarded(ticket:Long){
            inFlight.get()?.takeIf{it.ticket==ticket}?.let{inFlight.compareAndSet(it,null)}
        }
    },composite)
    @Volatile var lastError: String?=null; private set
    @Volatile var presented: SonarGpuCompletion?=null; private set
    @Volatile internal var pipelinePrepared=false;private set
    internal var onPreparationChanged:((Boolean)->Unit)?=null
    private fun preparationChanged(value:Boolean) {
        if(pipelinePrepared==value)return
        pipelinePrepared=value
        if(android.os.Looper.myLooper()==main.looper)onPreparationChanged?.invoke(value)
        else main.post {if(!closed.get())onPreparationChanged?.invoke(pipelinePrepared)}
    }
    @Volatile var frameWallNanos: Long=0; private set
    internal val smoothingColumnsPrepared:Long get()=0L // Means are generated on the GPU.
    var onTextureBuffered: ((SonarGpuCompletion)->Unit)?=null
    internal fun compositeRecorded(recorded:SonarGpuCompletion?){
        // Only the metadata actually drawn with THIS consumed buffer may close
        // its Window ticket. A UI-only redraw of the prior slice is not a proof.
        val flight=inFlight.get()?:return
        if(SonarCompositeRecordBinding.matches(flight.completion,recorded,flight.bufferNotified))
            presentation.compositeRecorded(flight.ticket)
    }
    private fun announceBuffered(flight:Flight){
        check(android.os.Looper.myLooper()==main.looper)
        val stamp=flight.completion?:return
        if(!closed.get()&&flight.textureSeen&&!flight.bufferNotified&&inFlight.get()===flight){
            flight.bufferNotified=true;onTextureBuffered?.invoke(stamp)
        }
    }
    var onTexturePresented: ((SonarGpuCompletion)->Unit)?=null
    // Following fields are confined to the GL worker.
    private var target: Surface?=null
    private var texture: SurfaceTexture?=null
    // Android owns the consumer until onSurfaceTextureDestroyed. Its terminal
    // callback can follow AndroidView.onRelease, after our EGL producer closed.
    // Main accepts/removes callbacks; the GL worker alone counts live leases.
    private val frameworkSurfaces=java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<SurfaceTexture,Boolean>())
    private var consumerLeases=0
    private var surfaceGeneration=0L
    private var handle=0L
    private var capacity=0
    private var ringOwner: SonarRingBuffer?=null
    private var ringGeneration=-1L
    private var expectedIds=LongArray(0)
    @Volatile private var debugAttempts=0L
    @Volatile private var debugPhase=0L
    @Volatile private var debugRawAbort=0L
    @Volatile private var debugMeanAbort=0L
    @Volatile private var debugPairAbort=0L
    @Volatile private var debugMetadataNs=0L
    @Volatile private var debugRawNs=0L
    @Volatile private var debugMeanNs=0L
    @Volatile private var debugPairNs=0L
    @Volatile private var debugPairs=0L
    internal fun fixtureProgress():LongArray {
        check(com.no5.helm.BuildConfig.DEBUG&&context.packageName=="com.no5.helm.sonarfixture")
        return longArrayOf(debugAttempts,debugPhase,debugRawAbort,debugMeanAbort,debugPairAbort,
            debugMetadataNs,debugRawNs,debugMeanNs,debugPairNs,debugPairs,frameWallNanos)
    }
    private var batch=ShortArray(0)
    private var current: SonarGpuFrame?=null
    private var lastUploadedPing = Long.MIN_VALUE
    private var uploadedContext: String? = null
    private var rowMetadata = FloatArray(0)
    private var rowTones = FloatArray(0)
    private var toneVersion = -1L
    private var paletteKey: Any? = null
    private val palette256 = IntArray(256)
    private data class RowContext(val segment: Long, val source: SonarPingSource?,
        val reference: SonarTxReference?, val amplitude: SonarAmplitude?, val fullScale: Int)
    private val rowContexts = HashMap<RowContext, Int>()
    private var transferKey: Any?=null
    init { isOpaque=true; surfaceTextureListener=this }

    // A Compose AndroidView can detach and reattach the SAME owned instance.
    // Surface destruction releases EGL; only its owner's onRelease is terminal.

    fun submit(frame: SonarGpuFrame) {
        if(closed.get()||lastSubmitted==frame)return
        isOpaque=!frame.fullStage;lastSubmitted=frame;lastSubmittedAtNanos=System.nanoTime();if(firstSubmittedAtNanos==0L)firstSubmittedAtNanos=lastSubmittedAtNanos;pending.set(frame);needsRender.set(true)
        schedule()
    }
    private fun schedule() {
        if(!closed.get()&&scheduled.compareAndSet(false,true))worker.post {
            try {
                pending.getAndSet(null)?.let { current=it }
                if(presentation.ready()&&inFlight.get()==null&&needsRender.compareAndSet(true,false))current?.let(::render)
            }
            catch(error:RuntimeException){lastError=(error.message?:error.javaClass.simpleName).take(160)}
            catch(error:LinkageError){lastError=(error.message?:error.javaClass.simpleName).take(160)}
            finally { scheduled.set(false);if(pending.get()!=null&&inFlight.get()==null)schedule() }
        }
    }
    private fun destroyGpu() {
        preparationChanged(false)
        if(handle!=0L){SonarGpuNative.destroy(handle);handle=0}
        inFlight.set(null);previousViewport.fill(Float.NaN);transferKey=null;paletteKey=null;toneVersion=-1;rowTones=FloatArray(0);ringOwner=null;ringGeneration=-1
        lastUploadedPing=Long.MIN_VALUE;uploadedContext=null;rowContexts.clear();rowMetadata=FloatArray(0)
        expectedIds=LongArray(0);batch=ShortArray(0);capacity=0
    }
    private data class Prepared(val count:Int,val head:Int,val newest:Long,val pingId:Long,val changed:Int,val toneVersion:Long,val received:Long)
    private fun render(frame: SonarGpuFrame) {
        if(lastError!=null)return
        val surface=target?:return
        if(!surface.isValid)return
        val started=System.nanoTime();lastWorkerProgressAtNanos=started
        if(com.no5.helm.BuildConfig.DEBUG){debugAttempts++;debugPhase=1;debugPairNs=0;debugPairs=0}
        val ring=frame.ring
        if(handle==0L||capacity!=ring.capacity){
            destroyGpu();handle=SonarGpuNative.create(surface,ring.capacity);capacity=ring.capacity
            expectedIds=LongArray(capacity){Long.MIN_VALUE}
        }
        if(ringOwner!==ring||ringGeneration!=frame.ringGeneration||uploadedContext!=frame.context||rowContexts.size>capacity*2){
            lastUploadedPing=Long.MIN_VALUE;toneVersion=-1;rowContexts.clear()
            ringOwner=ring;ringGeneration=frame.ringGeneration;uploadedContext=frame.context
        }
        // Only inspect new rows. The raw ring stays the single authoritative
        // history; no JNI/driver call is allowed while holding its receive lock.
        val prepared=ring.readLocked {
            if(closed.get()||ring.lifecycleGeneration!=frame.ringGeneration)return@readLocked null
            var changed=0
            while(changed<ring.count&&ring.pingIdAt(changed)!=lastUploadedPing){
                expectedIds[ring.storageSlotAt(changed)]=ring.pingIdAt(changed);changed++
            }
            if(toneVersion!=ring.imageMappingVersion) for(age in 0 until ring.count) expectedIds[ring.storageSlotAt(age)]=ring.pingIdAt(age)
            Prepared(ring.count,ring.head,if(ring.count>0)ring.timestampAt(0)else 0L,
                if(ring.count>0)ring.pingIdAt(0)else -1L,changed,ring.imageMappingVersion,if(ring.count>0)ring.localReceivedAtNanosAt(0)else 0L)
        }?:return
        if(com.no5.helm.BuildConfig.DEBUG){debugMetadataNs=System.nanoTime()-started;debugPhase=2}
        var left=prepared.changed
        var first=(prepared.head-left+capacity)%capacity
        while(left>0){
            if(closed.get())return
            val columns=minOf(left,256-first%256,capacity-first)
            if(batch.size!=columns*1024)batch=ShortArray(columns*1024)
            if(rowMetadata.size!=columns*8)rowMetadata=FloatArray(columns*8)
            val exact=ring.readLocked {
                if(ring.lifecycleGeneration!=frame.ringGeneration)return@readLocked false
                for(i in 0 until columns){
                    val slot=first+i;val age=(ring.head-1-slot+capacity)%capacity
                    if(age>=ring.count||ring.pingIdAt(age)!=expectedIds[slot])return@readLocked false
                    ring.copyDisplayProfileAt(age,batch,i*1024)
                    val spacing=ring.metersPerBinValueAt(age)
                    val amplitude=ring.amplitudeContextAt(age)
                    val source=ring.pingSourceAt(age)
                    val context=RowContext(ring.imageSegmentAt(age),source,ring.geometryAt(age)?.txReference,
                        amplitude,ring.amplitudeFullScaleAt(age))
                    val token=if(amplitude?.usable==false||source==null||source==SonarPingSource.ALL)0
                        else rowContexts.getOrPut(context){rowContexts.size+1}
                    val at=i*8
                    // Exact integer ms within the display horizon. This modulo
                    // is only GPU coordinates; UTC and freshness proofs stay original.
                    rowMetadata[at]=Math.floorMod(ring.timestampAt(age),16777216L).toFloat()
                    rowMetadata[at+1]=if(spacing.isFinite()&&spacing>0)spacing.toFloat()else 0f
                    rowMetadata[at+2]=token.toFloat();rowMetadata[at+3]=ring.amplitudeFullScaleAt(age).toFloat()
                    rowMetadata[at+4]=0f;rowMetadata[at+5]=0f;rowMetadata[at+6]=0f;rowMetadata[at+7]=0f
                    val bottom=ring.bottomAt(age);val presentation=ring.presentationAt(age)
                    if(spacing.isFinite()&&spacing>0&&bottom.validated&&bottom.source!=null&&
                        presentation?.quality==SonarAnalysisQuality.LOCKED&&presentation.pingId==ring.pingIdAt(age)){
                        val depth=bottom.bin!!*spacing
                        if(depth>0&&depth<spacing*1023){rowMetadata[at+4]=depth.toFloat();rowMetadata[at+5]=1f}
                    }
                }
                true
            }
            if(!exact){batch.fill(0);rowMetadata.fill(0f);needsRender.set(true);return}
            try{
                com.no5.helm.SonarTrace.phase("sonar.gpu.upload"){SonarGpuNative.upload(handle,first,columns,batch)}
                SonarGpuNative.metadataRows(handle,first,columns,rowMetadata)
            }finally{batch.fill(0);rowMetadata.fill(0f)}
            lastWorkerProgressAtNanos=System.nanoTime();left-=columns;first=(first+columns)%capacity
        }
        var toneLeft=if(toneVersion!=prepared.toneVersion)prepared.count else prepared.changed
        var toneFirst=(prepared.head-toneLeft+capacity)%capacity
        while(toneLeft>0){
            val columns=minOf(toneLeft,256-toneFirst%256,capacity-toneFirst)
            if(rowTones.size!=columns*4)rowTones=FloatArray(columns*4)
            val exact=ring.readLocked {
                if(ring.lifecycleGeneration!=frame.ringGeneration||ring.imageMappingVersion!=prepared.toneVersion)return@readLocked false
                for(i in 0 until columns){
                    val slot=toneFirst+i;val age=(ring.head-1-slot+capacity)%capacity
                    if(age>=ring.count||ring.pingIdAt(age)!=expectedIds[slot])return@readLocked false
                    val tone=ring.imageMappingAt(age)?:frame.display.imageValues()
                    rowTones[i*4]=Math.pow(10.0,tone.gainDb/20).toFloat()
                    rowTones[i*4+1]=(1.0/tone.gamma).toFloat();rowTones[i*4+2]=tone.threshold.toFloat();rowTones[i*4+3]=1f
                };true
            }
            if(!exact){rowTones.fill(0f);needsRender.set(true);return}
            try{SonarGpuNative.toneRows(handle,toneFirst,columns,rowTones)}finally{rowTones.fill(0f)}
            toneLeft-=columns;toneFirst=(toneFirst+columns)%capacity
        }
        toneVersion=prepared.toneVersion
        if(!ring.readLocked{ring.lifecycleGeneration==frame.ringGeneration&&
            (prepared.count==0||((ring.head-1-(prepared.head-1+capacity)%capacity+capacity)%capacity).let{
                it<ring.count&&ring.pingIdAt(it)==prepared.pingId})}){needsRender.set(true);return}
        lastUploadedPing=prepared.pingId
        if(com.no5.helm.BuildConfig.DEBUG){debugRawNs=System.nanoTime()-started;debugPhase=3}
        val key=listOf(frame.display.palette,frame.display.gainDb,frame.display.gamma,frame.display.threshold,frame.night)
        val colors=listOf(frame.display.palette,frame.night)
        if(colors!=paletteKey){
            for(i in palette256.indices)palette256[i]=com.no5.helm.SonarNativeMath.palette(i/255.0,frame.display.palette,frame.night)
            paletteKey=colors
        }
        if(key!=transferKey){
            SonarGpuNative.configure(handle,frame.display.gainDb.toFloat(),frame.display.gamma.toFloat(),frame.display.threshold.toFloat(),palette256)
            transferKey=key
        }
        val autoMask=(if(frame.display.gainMode==SonarImageMode.AUTO)1 else 0) or
            (if(frame.display.gammaMode==SonarImageMode.AUTO)2 else 0) or (if(frame.display.thresholdMode==SonarImageMode.AUTO)4 else 0)
        SonarGpuNative.toneControl(handle,autoMask,frame.display.autoGainOffsetDb.toFloat())
        SonarGpuNative.rasterScale(handle,frame.rasterScale)
        if(frame.dashboardPreview) com.no5.helm.DashboardGeometry.echogram.let {
            SonarGpuNative.plot(handle,it.x,it.y,it.x+it.width,it.y+it.height,2000f)
        }
        else if(frame.fullStage)SonarGpuNative.plot(handle,com.no5.helm.SonarLayout.PLOT_LEFT,com.no5.helm.SonarLayout.TOP,
            com.no5.helm.SonarLayout.plotRight(frame.display.aScope),com.no5.helm.SonarLayout.BOTTOM,2000f)
        else SonarGpuNative.plot(handle,0f,0f,0f,0f,0f)
        SonarGpuNative.effects(handle,frame.fullStage&&!frame.dashboardPreview&&frame.display.aScope,frame.display.overlayBottom,frame.trackBins.toFloatArray())
        val mode=if(frame.display.exactPingProfiles)2 else if(frame.linear)1 else 0
        if(com.no5.helm.BuildConfig.DEBUG)debugPhase=5
        val ticket=presentation.beginSubmission()
        if(ticket==0L){needsRender.set(true);return}
        val flight=Flight(frame,surfaceGeneration,ticket)
        if(!inFlight.compareAndSet(null,flight)){presentation.failedSubmission(ticket);return}
        val stamp=try{
            SonarGpuNative.draw(handle,prepared.count,prepared.head,Math.floorMod(prepared.newest,16777216L).toFloat(),
                frame.display.historySeconds.toFloat(),frame.top.toFloat(),frame.bottom.toFloat(),
                (frame.metersPerBin?:0.0).toFloat(),mode,if(frame.linear&&!frame.display.exactPingProfiles)1 else 0,
                if(frame.night)0xff030000.toInt()else 0xff061323.toInt())
        }catch(failure:RuntimeException){presentation.failedSubmission(ticket);inFlight.compareAndSet(flight,null);throw failure}
        lastWorkerProgressAtNanos=System.nanoTime()
        SonarGpuNative.dimensions(handle,dimensions)
        SonarGpuNative.viewportInfo(handle,viewportInfo)
        val density=resources.displayMetrics.density
        if((0..4).any{viewportInfo[it]!=previousViewport[it]}||density!=previousViewport[5]){
            viewportInfo.copyInto(previousViewport,0,0,5);previousViewport[5]=density;viewportGeneration++
        }
        flight.completion=SonarGpuCompletion(frame.context,frame.ringGeneration,surfaceGeneration,dimensions[0],dimensions[1],prepared.pingId,stamp,prepared.newest,
            viewportInfo[0],viewportInfo[1],viewportInfo[2],viewportInfo[3],viewportInfo[4],density,viewportGeneration,
            frame.displayRevision,frame.display,frame.top,frame.bottom,frame.metersPerBin,(prepared.head-1+capacity)%capacity,viewportInfo[5]>.5f,viewportInfo[6].toInt(),viewGeneration,prepared.received)
        main.post{announceBuffered(flight)}
        presentation.completeSubmission(ticket)
        frameWallNanos=System.nanoTime()-started
        if(com.no5.helm.BuildConfig.DEBUG)debugPhase=6
    }
    override fun onSurfaceTextureAvailable(value: SurfaceTexture,width:Int,height:Int) {
        if(closed.get())return
        if(!frameworkSurfaces.add(value))return
        presentation.viewportChanged()
        worker.post {
            consumerLeases++
            destroyGpu();target?.release();texture?.takeIf{it!==value}?.release()
            texture=value;target=Surface(value);surfaceGeneration++
            schedule()
        }
    }
    override fun onSurfaceTextureSizeChanged(value:SurfaceTexture,width:Int,height:Int){if(closed.get())return;presentation.viewportChanged();worker.post{surfaceGeneration++;needsRender.set(true);schedule()}}
    override fun onSurfaceTextureDestroyed(value:SurfaceTexture):Boolean {
        // A texture offered only after terminal close was never accepted by
        // this worker; leave its destruction to Android.
        if(!frameworkSurfaces.remove(value))return true
        preparationChanged(false)
        presentation.surfaceDestroyed();inFlight.set(null)
        if(!worker.post {
            if(texture===value){destroyGpu();target?.release();target=null;texture=null}
            value.release()
            consumerLeases--
            if(closed.get()&&consumerLeases==0)thread.quitSafely()
        }){
            lastError="SONAR_SURFACE_RETIRE_WORKER_CLOSED"
            value.release()
        }
        return false
    }
    override fun onSurfaceTextureUpdated(value:SurfaceTexture) {
        // This guest reports zero timestamps even for verified new hardware pixels.
        // One outstanding buffer + a settled viewport fences its actual update.
        // The subsequent Window frame commit, not swap success, completes presentation.
        lastObservedTextureTimestampNanos=value.timestamp
        if(!closed.get()){presentation.textureUpdated();inFlight.get()?.let{it.textureSeen=true;announceBuffered(it)}}
    }
    /** Called only after the watchdog consumed its one admitted EGL-repair budget. */
    internal fun recoverOwnedEgl() {
        if(closed.get())return
        worker.post { destroyGpu();lastError=null;needsRender.set(true);schedule() }
    }
    internal fun fixtureRenderAgain(){
        check(com.no5.helm.BuildConfig.DEBUG&&context.packageName=="com.no5.helm.sonarfixture")
        worker.post{needsRender.set(true);schedule()}
    }
    /** Private fixture observes only its own renderer on the owning GL worker. */
    internal fun fixtureOwnHandle(block:(Long)->Unit) {
        check(com.no5.helm.BuildConfig.DEBUG&&context.packageName=="com.no5.helm.sonarfixture")
        worker.post { if(!closed.get()&&handle!=0L)block(handle) }
    }
    override fun close() {
        if(!closed.compareAndSet(false,true))return
        preparationChanged(false);onPreparationChanged=null
        presentation.close();pending.set(null);lastSubmitted=null;onTexturePresented=null;onTextureBuffered=null;presented=null
        worker.post {
            destroyGpu();current=null;target?.release();target=null
            // Keep the owner queue alive for the framework's later consumer
            // release. No RAW history or EGL producer remains during this wait.
            if(consumerLeases==0)thread.quitSafely()
        }
    }
}
