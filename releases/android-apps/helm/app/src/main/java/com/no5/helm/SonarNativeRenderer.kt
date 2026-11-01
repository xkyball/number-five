package com.no5.helm

import com.no5.ui.No5Text
import com.no5.helm.numeric.*

import android.content.Context
import android.graphics.*
import com.no5.domain.*
import kotlin.math.*

/** Fixed 2048×1024 native bitmap; unchanged transforms upload only the new ping column. */
internal class SonarNativeRenderer(context: Context, private val transferLut:SonarTransferLut=SonarTransferLut(), private val bulkUpload:SonarBulkUploadBuffer=SonarBulkUploadBuffer(), private val externalPlot:Boolean=false, private val externalData:Boolean=false) {
    private val bitmapOwner=lazy{Bitmap.createBitmap(SONAR_CAPACITY, SONAR_BINS, Bitmap.Config.ARGB_8888)}
    private val bitmap by bitmapOwner
    private val column = IntArray(SONAR_BINS)
    private val uploadAges=IntArray(SonarTimeImageHistory.SLOTS)
    private val uploadVersions=LongArray(SonarTimeImageHistory.SLOTS)
    private val colors = IntArray(65536)
    private var history: SonarImageColumns? = null
    private val timeVersions=LongArray(SonarTimeImageHistory.SLOTS){-1}
    private var version = -1L
    private var transform = ""
    private var colorTransform = ""
    private var lastLutNanos=0L
    private var lastRecolorUploadNanos=0L
    private var lastPackNanos=0L
    private var lastBitmapCopyNanos=0L
    private var lastUploadCalls=0L
    private var lastUploadedColumns=0L
    private var lastUploadedBytes=0L
    private var lastVisibleColumns=0L
    private var lastAggregated=false
    private var pairPrepareAtStart=0L
    private var pairMissesAtStart=0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint()
    private val historySourceRect = Rect()
    private val historyTargetRect = RectF()
    private val temporalSourceRect=Rect()
    private val temporalTargetRect=RectF()
    private val temporalShear=Matrix()
    private val temporalShearValues=FloatArray(9)
    private val ridgePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1.5f}
    private val ridgeGradientMatrix=Matrix()
    private val ridgeSlots=IntArray(SONAR_CAPACITY)
    private val ridgeCoordinates=FloatArray(SONAR_CAPACITY*6)
    private var ridgeCount=0
    private var anchoredImageDrawn=false
    private val temporalOwner=lazy{SonarTemporalImageCache()}
    private val temporal by temporalOwner
    private val aggregateTemporalOwner=lazy{SonarAggregateInterpolationCache()}
    private val aggregateTemporal by aggregateTemporalOwner
    private val amplitudeSmoothingOwner=lazy{SonarDisplaySmoothingCache()}
    private val amplitudeSmoothing by amplitudeSmoothingOwner
    private var smoothingRequested=false
    private var activeSmoothing:SonarDisplaySmoothingCache?=null
    internal val preparedSmoothedColumns:Long get()=amplitudeSmoothing.preparedColumns
    internal val preparedAggregatePairs:Long get()=aggregateTemporal.preparedPairs
    internal val aggregateTemporalPools:Int get()=aggregateTemporal.allocatedPools
    internal val preparedTemporalPairs:Long get()=temporal.preparedPairs
    internal val temporalAllocated:Boolean get()=temporal.allocated
    fun releaseTemporalResources(retry:Boolean=true){
        if(temporalOwner.isInitialized())temporal.release(retry)
        if(aggregateTemporalOwner.isInitialized())aggregateTemporal.release(retry)
        if(amplitudeSmoothingOwner.isInitialized())amplitudeSmoothing.release(retry)
        activeSmoothing=null
    }
    fun dispose(){releaseTemporalResources();if(bitmapOwner.isInitialized()&&!bitmap.isRecycled)bitmap.recycle()}
    internal val mainUploadCalls:Long get()=lastUploadCalls
    internal val mainUploadedColumns:Long get()=lastUploadedColumns
    internal val mainUploadedBytes:Long get()=lastUploadedBytes
    internal val uploadStripeBytes:Int get()=(bulkUpload.pixels?.size?:0)*4
    internal val observedPairMisses:Long get()=temporal.prepareMisses+aggregateTemporal.prepareMisses
    internal fun readUploadedPixels(out:IntArray) {
        require(out.size==SONAR_CAPACITY*SONAR_BINS)
        bitmap.getPixels(out,0,SONAR_CAPACITY,0,0,SONAR_CAPACITY,SONAR_BINS)
    }
    private val label = context.resources.getFont(R.font.barlow_condensed_semibold)
    private val hero = context.resources.getFont(R.font.barlow_condensed_extrabold)
    private val path = Path()
    private val scopePath = Path()
    private var night = false
    private val ink get() = if (night) 0xffd40000.toInt() else 0xff071022.toInt()
    private val secondary get() = if (night) 0xffb00000.toInt() else 0xff122653.toInt()
    private val structural get() = if (night) 0xff700000.toInt() else 0xff526b82.toInt()
    private val accent get() = if (night) 0xffff1a0a.toInt() else 0xff005daf.toInt()
    private val stageInk get() = if (night) 0xffb8614d.toInt() else 0xffb4c6d3.toInt()
    private val stage get() = if (night) 0xff030000.toInt() else 0xff061323.toInt()
    private val background get() = if (night) Color.BLACK else 0xfff6f9fa.toInt()
    private fun fill(c: Canvas, rect: RectF, color: Int, radius: Float = 0f) { paint.color=color;paint.style=Paint.Style.FILL;c.drawRoundRect(rect,radius,radius,paint) }
    private fun line(c: Canvas, x: Float, y: Float, x2: Float, y2: Float, color: Int, width: Float=2f) { paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=width;c.drawLine(x,y,x2,y2,paint);paint.style=Paint.Style.FILL }
    private fun outline(c: Canvas, rect: RectF, radius: Float, color: Int=structural, width: Float=2f) {paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=width;c.drawRoundRect(rect,radius,radius,paint);paint.style=Paint.Style.FILL}
    private fun text(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int=ink, bold: Boolean=false, center: Boolean=false, maxWidth: Float=Float.MAX_VALUE,slots: NumericSlots?=null) {
        paint.color=color;paint.style=Paint.Style.FILL;paint.textSize=size;paint.typeface=if(bold)hero else label;paint.textAlign=if(center)Paint.Align.CENTER else Paint.Align.LEFT
        // Fit extremely long diagnostic text by clipping, never by nonuniform font scaling.
        c.save();if(maxWidth<Float.MAX_VALUE)c.clipRect(if(center)x-maxWidth/2 else x,y-size-5,if(center)x+maxWidth/2 else x+maxWidth,y+8);if(slots!=null) NativeNumericText.draw(c,text,x,y,paint,slots,maxWidth)
        else c.drawText(text,x,y,paint);c.restore()
    }
    private fun upload(controller: SonarNativeController) {
        val m=controller.model;val ring=m.viewImageColumns;val d=m.viewDisplay
        val viewScale=m.viewAmplitudeFullScale
        val smooth=controller.linearInterpolation&&!d.exactPingProfiles
        if(!smooth&&smoothingRequested)amplitudeSmoothing.release()
        smoothingRequested=smooth
        activeSmoothing=if(smooth&&ring.count>0&&amplitudeSmoothing.begin(ring,m.epoch,d))amplitudeSmoothing else null
        val mapped=ring.count>0&&ring.imageMappingAt(0)!=null
        val key="${d.palette}:${if(d.gainMode==SonarImageMode.AUTO&&mapped)"AUTO"else d.gainDb}:${if(d.gammaMode==SonarImageMode.AUTO&&mapped)"AUTO"else d.gamma}:${if(d.thresholdMode==SonarImageMode.AUTO&&mapped)"AUTO"else d.threshold}:${d.autoGainOffsetDb}:${ring.imageMappingVersion}:$night:${viewScale}:${activeSmoothing!=null}"
        val transformed=key!=transform
        val colorKey=if(activeSmoothing!=null)"INTENSITY:${d.palette}:$night" else key
        if(colorKey!=colorTransform) {
            val started=System.nanoTime()
            if(activeSmoothing!=null)transferLut.fillIntensityColors(colors,d.palette,night)
            else transferLut.fill(colors,viewScale,d,night)
            lastLutNanos=System.nanoTime()-started
            colorTransform=colorKey
        }
        val recolorStarted=System.nanoTime()
        val full=transformed||history!==ring||ring.version<version||ring.version-version>ring.capacity
        uploadAges.fill(-1)
        lastAggregated=ring.aggregated
        if(ring.aggregated) {
            if(temporal.allocated||temporal.unavailable)temporal.release()
            if(full)timeVersions.fill(-1)
            for(age in 0 until m.visibleImageCount()) {
                if(!ring.occupiedAt(age))continue
                lastVisibleColumns++
                val slot=ring.storageSlotAt(age)
                val revision=activeSmoothing?.prepare(age)?:ring.contentVersionAt(age)
                if(timeVersions[slot]!=revision){uploadAges[slot]=age;uploadVersions[slot]=revision}
            }
        } else {
            if(aggregateTemporal.allocatedPools>0)aggregateTemporal.release()
            if(full)timeVersions.fill(-1)
            val visible=m.visibleCount()
            lastVisibleColumns=visible.toLong()
            // Retained raw measurements outside the viewport are not draw work.
            // Keep one older boundary column available for the existing interpolation.
            val required=min(ring.count,visible+1)
            for(age in required-1 downTo 0) {
                val slot=ring.storageSlotAt(age)
                // ring.version is monotonic across clear/ID-reset epochs. Ping IDs
                // alone could incorrectly reuse a stale physical bitmap slot.
                val revision=activeSmoothing?.prepare(age)?:ring.version-age
                if(timeVersions[slot]!=revision){uploadAges[slot]=age;uploadVersions[slot]=revision}
            }
        }
        val height=if(ring.aggregated)256 else 1024
        val planes=if(ring.aggregated)4 else 1
        val buffer=bulkUpload.pixels?:column
        val smoothing=activeSmoothing
        for(plane in 0 until planes) {
            var x=0
            while(x<2048) {
                if(uploadAges[plane*2048+x]<0){x++;continue}
                val start=x
                while(x<2048&&x-start<bulkUpload.maxColumns&&uploadAges[plane*2048+x]>=0)x++
                val width=x-start
                val packStarted=System.nanoTime()
                for(offset in 0 until width) {
                    val age=uploadAges[plane*2048+start+offset]
                    val scale=ring.amplitudeFullScaleAt(age)
                    val mapping=if(mapped)ring.imageValuesAt(age,d)else null
                    for(bin in 0 until height) {
                        val sourceBin=if(ring.aggregated)(bin*1023.0/255).roundToInt()else bin
                        if(smoothing!=null)buffer[bin*width+offset]=colors[smoothing.intensityCodeAt(age,sourceBin)]
                        else {
                            val raw=ring.sampleAt(age,sourceBin)
                            buffer[bin*width+offset]=if(mapping!=null)SonarNativeMath.palette(mapping.amplitude(raw/scale.toDouble()),d.palette,night)else if(scale==viewScale)colors[raw] else SonarNativeMath.palette(m.displayAmplitude(raw,scale,d),d.palette,night)
                        }
                    }
                }
                lastPackNanos+=System.nanoTime()-packStarted
                val copyStarted=System.nanoTime()
                bitmap.setPixels(buffer,0,width,start,if(ring.aggregated)plane*256 else 0,width,height)
                lastBitmapCopyNanos+=System.nanoTime()-copyStarted
                lastUploadCalls++;lastUploadedColumns+=width;lastUploadedBytes+=width.toLong()*height*4
                for(offset in 0 until width) {
                    val slot=plane*2048+start+offset
                    val age=uploadAges[slot]
                    timeVersions[slot]=uploadVersions[slot]
                }
            }
        }
        history=ring;version=ring.version;transform=key
        lastRecolorUploadNanos=System.nanoTime()-recolorStarted
    }
    private fun recordCostBreakdown(preview:Boolean,elapsedNanos:Long) {
        if(externalPlot)return // Overlay CPU is recorded separately; no hidden bitmap/interpolation owner.
        val pairNanos=(temporal.prepareNanos+aggregateTemporal.prepareNanos-pairPrepareAtStart).coerceAtLeast(0)
        val misses=(temporal.prepareMisses+aggregateTemporal.prepareMisses-pairMissesAtStart).coerceAtLeast(0)
        SonarPaintDiagnostics.recordCosts(preview,lastPackNanos,lastBitmapCopyNanos,pairNanos,
            (elapsedNanos-lastLutNanos-lastRecolorUploadNanos-pairNanos).coerceAtLeast(0),
            lastUploadCalls,lastUploadedColumns,lastUploadedBytes,misses,lastVisibleColumns,lastAggregated,bulkUpload.unavailable)
    }
    private var depthUnit = DepthUnit.METERS
    fun drawBackdrop(c:Canvas,nightMode:Boolean) {
        night=nightMode;c.save();c.clipRect(0f,130f,2000f,1058f)
        fill(c,RectF(0f,130f,2000f,1058f),background)
        fill(c,RectF(0f,130f,2000f,174f),if(night)0xff100302.toInt() else 0xffe5ecef.toInt())
        line(c,0f,130f,2000f,130f,if(night)0xff5d1912.toInt() else 0xfff9fbfc.toInt())
        line(c,0f,174f,2000f,174f,structural)
        fill(c,RectF(0f,SonarLayout.TOP,2000f,SonarLayout.BOTTOM),stage)
        c.restore()
    }
    fun draw(c: Canvas, controller: SonarNativeController, nightMode: Boolean, unit: DepthUnit = DepthUnit.METERS, audio: AudioNativeController? = null, backgroundOnly:Boolean=false) {
        if(backgroundOnly){drawBackdrop(c,nightMode);return}
        val paintStarted=System.nanoTime()
        lastLutNanos=0;lastRecolorUploadNanos=0
        lastPackNanos=0;lastBitmapCopyNanos=0;lastUploadCalls=0;lastUploadedColumns=0;lastUploadedBytes=0;lastVisibleColumns=0
        pairPrepareAtStart=if(externalPlot)0 else temporal.prepareNanos+aggregateTemporal.prepareNanos
        pairMissesAtStart=if(externalPlot)0 else temporal.prepareMisses+aggregateTemporal.prepareMisses
        try {
        night=nightMode; depthUnit=unit
        val shown=if(externalPlot)controller.displayedGpuSlice()else null
        val s=controller.snapshot;val m=controller.model;val d=s.display;val bounds=if(shown!=null)SonarNativeBounds(shown.top,shown.bottom,shown.viewportState)else controller.bounds()
        if(externalPlot)anchoredImageDrawn=shown?.registeredPairs?.let{it>0}==true
        val labels=sonarPanelPresentation(controller,unit)
        val right=SonarLayout.plotRight(d.aScope);val width=SonarLayout.plotWidth(d.aScope)
        val top=SonarLayout.TOP;val bottom=SonarLayout.BOTTOM;val height=SonarLayout.HEIGHT
        val viewMeters=if(shown!=null)shown.metersPerBin else m.viewMetersPerBin
        val stageRect=RectF(0f,top,2000f,bottom)
        c.save();c.clipRect(0f,130f,2000f,1058f)
        if(!externalPlot)drawBackdrop(c,nightMode)
        text(c,No5Text.ui("SONAR"),51f,159f,22f,ink,true)
        text(c,controller.sourceLabel(),151f,159f,22f,secondary,maxWidth=220f)
        text(c,controller.rateTitle(),390f,159f,18f,secondary,maxWidth=135f)
        text(c,controller.rateLabel(),535f,159f,24f,ink,true,maxWidth=250f,slots=NumericSlots(4,1))
        text(c,No5Text.ui("Tiefenrate"),805f,159f,18f,secondary,maxWidth=95f)
        text(c,controller.selectedUsableRateLabel(),910f,159f,22f,secondary,maxWidth=185f,slots=NumericSlots(4,1))
        // State changes must not resize or recenter the retained numeric reading.
        text(c,labels.depthLabel,1680f,159f,26f,ink,true,center=true,maxWidth=280f,slots=NumericFormats.depth)
        text(c,labels.depthStatusLabel,1832f,159f,17f,secondary,maxWidth=148f)
        c.save();c.clipRect(stageRect)
        if(!externalPlot)upload(controller)
        val count=if(externalData)m.viewRing.count else m.visibleCount()
        if(!externalPlot)drawHistory(c,controller,RectF(SonarLayout.PLOT_LEFT,top,right,bottom))
        if(d.aScope&&!externalData)fill(c,RectF(right,top,SonarLayout.RIGHT,bottom),if(night)0xff070000.toInt() else 0xff071727.toInt())
        if(!externalData){
        val grid=if(night)0xff401512.toInt() else 0xff23394a.toInt()
        for(i in 1..5){val y=top+i*height/6;line(c,SonarLayout.PLOT_LEFT,y,SonarLayout.RIGHT,y,grid,1f)}
        for(i in 1..7){val x=SonarLayout.PLOT_LEFT+i*width/8;line(c,x,top,x,bottom,grid,1f)}
        }
        drawScope(c,controller)
        drawTracks(c,controller)
        if(!externalData)drawBottomHistory(c,controller)
        drawCursor(c,controller)
        if(!controller.reliefReady || controller.reliefAligned || m.viewDisplay.exactPingProfiles){
        text(c,if(viewMeters!=null||d.startDepthM>0)"${depthUnit.symbol} TX" else "BIN",18f,201f,18f,stageInk)
        for(i in 0..5){val y=top+60f+i*(height-100f)/5;val bin=bounds.start+(y-top)/height*(bounds.end-bounds.start)
            text(c,if(!bounds.available)"—"else if(viewMeters!=null)sonarNumber(depthUnit.fromMeters(bin*viewMeters)) else bin.roundToInt().toString(),81f,y+8f,24f,stageInk,center=true,maxWidth=82f,slots=NumericFormats.axis)
            line(c,83f,y,SonarLayout.PLOT_LEFT,y,stageInk,1f)}
        } else text(c,No5Text.ui("ORBIT"),18f,201f,17f,stageInk)
        if(count==0){
            text(c,if(s.connected)No5Text.ui("WAITING FOR SONAR PROFILES") else No5Text.ui("SONAR SOURCE UNAVAILABLE"),(SonarLayout.PLOT_LEFT+right)/2,510f,44f,stageInk,true,true)
            text(c,No5Text.ui("No profile received · Depth unavailable"),(SonarLayout.PLOT_LEFT+right)/2,553f,25f,stageInk,center=true)
        }
        // Freeze is already named in the persistent fascia and A-scope. A second
        // plot banner would cover the current host observation/error line.
        c.restore()
        line(c,0f,top,SonarLayout.RIGHT,top,structural)
        drawRail(c,controller,audio)
        drawCursorReading(c,controller)
        c.restore()
        if(!externalPlot)controller.displayed()
        } finally { val elapsedNanos=System.nanoTime()-paintStarted;SonarPaintDiagnostics.record(false,elapsedNanos);SonarPaintDiagnostics.recordPhases(false,lastLutNanos,lastRecolorUploadNanos,(elapsedNanos-lastLutNanos-lastRecolorUploadNanos).coerceAtLeast(0));recordCostBreakdown(false,elapsedNanos) }
    }
    internal fun copyColorColumnForProof(slot:Int,out:IntArray) {
        require(!externalPlot && out.size==1024 && slot in 0 until SONAR_CAPACITY)
        bitmap.getPixels(out,0,1,slot,0,1,1024)
    }
    /** Offscreen comparison entry: same original upload/draw path, without UI grid/text. */
    internal fun drawPlotForProof(c:Canvas,controller:SonarNativeController,nightMode:Boolean,target:RectF) {
        require(!externalPlot)
        night=nightMode;c.save();c.clipRect(target);fill(c,target,stage);upload(controller)
        try{drawHistory(c,controller,target)}finally{c.restore()}
    }
    /** Data-only viewport for DASHBOARD. Shares the main renderer's raw history upload
     * and transfer function; no full-scene fonts or controls are stretched into a thumbnail. */
    fun drawPreview(c:Canvas,controller:SonarNativeController,nightMode:Boolean,target:RectF) {
        if(target.width()<=0f||target.height()<=0f)return
        val paintStarted=System.nanoTime()
        lastLutNanos=0;lastRecolorUploadNanos=0
        lastPackNanos=0;lastBitmapCopyNanos=0;lastUploadCalls=0;lastUploadedColumns=0;lastUploadedBytes=0;lastVisibleColumns=0
        pairPrepareAtStart=if(externalPlot)0 else temporal.prepareNanos+aggregateTemporal.prepareNanos
        pairMissesAtStart=if(externalPlot)0 else temporal.prepareMisses+aggregateTemporal.prepareMisses
        try {
        night=nightMode
        c.save();c.clipRect(target)
        if(!externalPlot){fill(c,target,stage);upload(controller)}
        val model=controller.model
        val ring=model.viewRing
        val count=model.visibleCount()
        if(!externalPlot)drawHistory(c,controller,target)
        val grid=if(night)0xff401512.toInt() else 0xff23394a.toInt()
        for(i in 1..3) {val y=target.top+target.height()*i/4;line(c,target.left,y,target.right,y,grid,1f)}
        c.restore()
        // The current transform and visible history have actually been drawn above.
        if(!externalPlot)controller.displayed()
        } finally { val elapsedNanos=System.nanoTime()-paintStarted;SonarPaintDiagnostics.record(true,elapsedNanos);SonarPaintDiagnostics.recordPhases(true,lastLutNanos,lastRecolorUploadNanos,(elapsedNanos-lastLutNanos-lastRecolorUploadNanos).coerceAtLeast(0));recordCostBreakdown(true,elapsedNanos) }
    }
    /** A timestamp owns x; a column's own spacing owns its physical y coverage. */
    private fun drawHistory(c: Canvas, controller: SonarNativeController, target: RectF) {
        val m=controller.model;val ring=m.viewImageColumns;val count=m.visibleImageCount()
        val exact=m.viewDisplay.exactPingProfiles
        val linear=controller.linearInterpolation&&!exact
        anchoredImageDrawn=false;ridgeCount=0
        if(!linear) {
            if(temporal.allocated||temporal.unavailable)temporal.release()
            if(aggregateTemporal.allocatedPools>0||aggregateTemporal.unavailable)aggregateTemporal.release()
        }
        if(count==0)return
        val bounds=controller.bounds();if(!bounds.available)return
        val axisMeters=m.viewMetersPerBin?:Double.NaN
        val sourceTop=floor(bounds.start).toInt().coerceIn(0,1023)
        val sourceBottom=(ceil(bounds.end).toInt()+1).coerceIn(sourceTop+1,1024)
        bitmapPaint.isFilterBitmap=linear
        if(linear) {
            if(ring.aggregated)drawAggregateTemporalHistory(c,controller,target,axisMeters,bounds)
            else drawTemporalHistory(c,controller,target,axisMeters,bounds)
        }
        for(age in count-1 downTo 0) {
            if(!ring.occupiedAt(age))continue
            val slot=ring.storageSlotAt(age)
            if(ring.aggregated) {
                drawTimeBin(c,controller,target,ring,age,slot,axisMeters,bounds)
                continue
            }
            val width=if(!exact && !linear && age>0) ((ring.timestampAt(age-1)-ring.timestampAt(age)).coerceIn(0,150).toDouble() /
                (m.viewDisplay.historySeconds*1000)*target.width()).toFloat().coerceAtLeast(1f) else 1f
            // Reserve the two-pixel A-scope separator; even a single newest
            // column must paint a complete pixel inside the plot, not under it.
            val x=if(exact)target.right-2.5f-(m.imageFractionAt(age)*target.width()).toFloat()
                else if(linear)(target.right-2.5f-(m.imageFractionAt(age)*target.width()).toFloat()).coerceAtLeast(target.left+.5f)
                else (target.right-(m.imageFractionAt(age)*target.width()).toFloat()).coerceIn(target.left+width/2,target.right-2f-width/2)
            val columnMeters=ring.metersPerBinValueAt(age)
            if(columnMeters==axisMeters || columnMeters.isNaN() && axisMeters.isNaN()) {
                // Keep the established raster path unchanged for identical geometry.
                historySourceRect.set(slot,sourceTop,slot+1,sourceBottom)
                historyTargetRect.set(x-width/2,target.top,x+width/2,target.bottom)
                c.drawBitmap(bitmap,historySourceRect,historyTargetRect,bitmapPaint)
            } else {
                val axisStart=ring.axisBinAt(age,0.0,axisMeters)
                val axisEnd=ring.axisBinAt(age,1023.0,axisMeters)
                if(!axisStart.isFinite() || !axisEnd.isFinite())continue
                val pixelsPerSourceBin=(axisEnd-axisStart)/1023/(bounds.end-bounds.start)*target.height()
                val zeroY=target.top+(axisStart-bounds.start)/(bounds.end-bounds.start)*target.height()
                val lastY=target.top+(axisEnd-bounds.start)/(bounds.end-bounds.start)*target.height()
                val coveredTop=maxOf(target.top.toDouble(),zeroY)
                val coveredBottom=minOf(target.bottom.toDouble(),lastY)
                if(coveredTop>=coveredBottom)continue
                // Bitmap pixel centres represent sample bins 0..1023. Clip to
                // actual measured coverage; a short capture never fills deeper water.
                historySourceRect.set(slot,0,slot+1,1024)
                historyTargetRect.set(x-width/2,(zeroY-pixelsPerSourceBin/2).toFloat(),x+width/2,(lastY+pixelsPerSourceBin/2).toFloat())
                c.save();c.clipRect(x-width/2,coveredTop.toFloat(),x+width/2,coveredBottom.toFloat())
                c.drawBitmap(bitmap,historySourceRect,historyTargetRect,bitmapPaint);c.restore()
            }
        }
        if(linear)drawRegisteredRidgeCoverage(c)
    }
    /** A peak bin occupies only the time/physical coverage actually observed in this context. */
    private fun drawTimeBin(c:Canvas,controller:SonarNativeController,target:RectF,ring:SonarImageColumns,age:Int,slot:Int,axisMeters:Double,bounds:SonarNativeBounds) {
        val m=controller.model;val horizon=m.viewDisplay.historySeconds*1000
        val right=target.right-2.5f
        val first=right-((m.imageNewestTime()-ring.firstTimestampAt(age))/horizon*target.width()).toFloat()
        val last=right-((m.imageNewestTime()-ring.lastTimestampAt(age))/horizon*target.width()).toFloat()
        if(last<target.left||first>right)return
        val axisStart=ring.axisBinAt(age,0.0,axisMeters);val axisEnd=ring.axisBinAt(age,1023.0,axisMeters)
        if(!axisStart.isFinite()||!axisEnd.isFinite())return
        val zeroY=target.top+(axisStart-bounds.start)/(bounds.end-bounds.start)*target.height()
        val lastY=target.top+(axisEnd-bounds.start)/(bounds.end-bounds.start)*target.height()
        val top=maxOf(target.top.toDouble(),zeroY);val bottom=minOf(target.bottom.toDouble(),lastY)
        if(top>=bottom)return
        val samplePixels=(lastY-zeroY)/255
        val x=slot%2048;val y=(slot/2048)*256
        historySourceRect.set(x,y,x+1,y+256)
        historyTargetRect.set(first-.5f,(zeroY-samplePixels/2).toFloat(),maxOf(first+.5f,last+.5f),(lastY+samplePixels/2).toFloat())
        c.save();c.clipRect(maxOf(target.left,first-.5f),top.toFloat(),minOf(right+.5f,maxOf(first+.5f,last+.5f)),bottom.toFloat())
        c.drawBitmap(bitmap,historySourceRect,historyTargetRect,bitmapPaint);c.restore()
    }
    /** Crossfade ONLY: no canonical bottom or depth trajectory is assigned to an aggregate. */
    private fun drawAggregateTemporalHistory(c:Canvas,controller:SonarNativeController,target:RectF,axisMeters:Double,bounds:SonarNativeBounds) {
        val m=controller.model;val ring=m.viewImageColumns;val count=m.visibleImageCount()
        if(aggregateTemporal.unavailable||!aggregateTemporal.begin(ring,transform))return
        for(newer in count-1 downTo 0) {
            if(!ring.occupiedAt(newer))continue
            val older=aggregateTemporal.predecessor(ring,newer,count)
            if(older<0)continue
            val oldSlot=ring.storageSlotAt(older);val newSlot=ring.storageSlotAt(newer)
            val oldSpacing=ring.metersPerBinValueAt(older);val newSpacing=ring.metersPerBinValueAt(newer)
            val same=oldSpacing==newSpacing||oldSpacing.isNaN()&&newSpacing.isNaN()
            val oldX=target.right-2.5f-((m.imageNewestTime()-ring.lastTimestampAt(older))/(m.viewDisplay.historySeconds*1000)*target.width()).toFloat()
            val newX=target.right-2.5f-((m.imageNewestTime()-ring.firstTimestampAt(newer))/(m.viewDisplay.historySeconds*1000)*target.width()).toFloat()
            val width=newX-oldX
            if(width<=0||newX<target.left||oldX>target.right)continue
            val spacing:Double;val image:Bitmap
            if(same&&oldSlot/2048==newSlot/2048&&newSlot%2048==oldSlot%2048+1) {
                val y=(oldSlot/2048)*256
                temporalSourceRect.set(oldSlot%2048,y,newSlot%2048+1,y+256)
                spacing=newSpacing;image=bitmap
            } else {
                val slot=aggregateTemporal.prepare(ring,newer,older,bitmap,activeSmoothing,colors)
                if(slot<0)return
                aggregateTemporal.sourceRect(slot,temporalSourceRect)
                spacing=aggregateTemporal.spacing(slot);image=aggregateTemporal.bitmap(slot)
            }
            val scale=when {spacing==axisMeters||spacing.isNaN()&&axisMeters.isNaN()->1.0
                spacing.isFinite()&&axisMeters.isFinite()->spacing/axisMeters
                else->continue}
            val pixelsPerBin=scale*1023/255/(bounds.end-bounds.start)*target.height()
            val zeroY=target.top-bounds.start/(bounds.end-bounds.start)*target.height()
            val lastY=zeroY+255*pixelsPerBin
            val top=maxOf(target.top.toDouble(),zeroY);val bottom=minOf(target.bottom.toDouble(),lastY)
            if(top>=bottom)continue
            temporalTargetRect.set(oldX-width/2,(zeroY-pixelsPerBin/2).toFloat(),newX+width/2,(lastY+pixelsPerBin/2).toFloat())
            c.save();c.clipRect(maxOf(target.left,oldX),top.toFloat(),minOf(target.right,newX),bottom.toFloat())
            c.drawBitmap(image,temporalSourceRect,temporalTargetRect,bitmapPaint);c.restore()
        }
    }
    private fun drawTemporalHistory(c:Canvas,controller:SonarNativeController,target:RectF,axisMeters:Double,bounds:SonarNativeBounds) {
        val model=controller.model;val ring=model.viewRing
        if(model.visibleCount()<2 || temporal.unavailable)return
        val count=model.visibleCount()
        for(newerAge in count-2 downTo 0) {
            if(!temporal.compatible(ring,newerAge))continue
            val olderAge=newerAge+1
            // One fixed time origin for every pair. The inset reserves the separator
            // without compressing the final interval or changing timestamp weights.
            val olderX=target.right-2.5f-(model.historyFractionAt(olderAge)*target.width()).toFloat()
            val newerX=target.right-2.5f-(model.historyFractionAt(newerAge)*target.width()).toFloat()
            val elapsedWidth=newerX-olderX
            val anchored=temporal.hasBottomAnchors(ring,newerAge)
            if(elapsedWidth<=0f || elapsedWidth<=1f && !anchored)continue
            val newerSlot=(ring.head-1-newerAge+ring.capacity)%ring.capacity
            val olderSlot=(ring.head-1-olderAge+ring.capacity)%ring.capacity
            val newerSpacing=ring.metersPerBinValueAt(newerAge);val olderSpacing=ring.metersPerBinValueAt(olderAge)
            val sameSpacing=newerSpacing==olderSpacing || newerSpacing.isNaN() && olderSpacing.isNaN()
            val spacing:Double
            val image:Bitmap
            var oldBottom=Double.NaN;var newBottom=Double.NaN
            var preparedSlot=-1
            if(sameSpacing && newerSlot==olderSlot+1 && !anchored) {
                // Common case: filter the existing adjacent columns directly.
                // Gain/palette changes never require a second full-history pixel pass.
                temporalSourceRect.set(olderSlot,0,newerSlot+1,1024)
                spacing=newerSpacing;image=bitmap
            } else {
                if(!temporal.begin(ring,model.epoch,transform))return
                val slot=temporal.prepare(ring,newerAge,colors,activeSmoothing)
                if(slot<0)return
                spacing=temporal.spacing(slot);image=temporal.bitmap!!
                preparedSlot=slot;oldBottom=temporal.oldBottom(slot);newBottom=temporal.newBottom(slot)
                temporal.sourceRect(slot,temporalSourceRect)
            }
            val scale=when {
                spacing==axisMeters || spacing.isNaN() && axisMeters.isNaN()->1.0
                spacing.isFinite() && axisMeters.isFinite()->spacing/axisMeters
                else->continue
            }
            val binPixels=scale/(bounds.end-bounds.start)*target.height()
            val zeroY=target.top-bounds.start/(bounds.end-bounds.start)*target.height()
            val lastY=zeroY+1023*binPixels
            val top=maxOf(target.top.toDouble(),zeroY);val bottom=minOf(target.bottom.toDouble(),lastY)
            if(top>=bottom)continue
            // The two pixel centres are precisely the two measured timestamps.
            temporalTargetRect.set(olderX-elapsedWidth/2,(zeroY-binPixels/2).toFloat(),newerX+elapsedWidth/2,(lastY+binPixels/2).toFloat())
            c.save();c.clipRect(olderX,top.toFloat(),newerX,bottom.toFloat())
            if(anchored) {
                val metresToPixels=target.height()/(axisMeters*(bounds.end-bounds.start))
                val firstShift=(oldBottom-newBottom)/2*metresToPixels
                val slope=((newBottom-oldBottom)*metresToPixels/elapsedWidth).toFloat()
                val matrix=temporalShearValues
                matrix[0]=1f;matrix[1]=0f;matrix[2]=0f
                matrix[3]=slope;matrix[4]=1f;matrix[5]=(firstShift-slope*olderX).toFloat()
                matrix[6]=0f;matrix[7]=0f;matrix[8]=1f
                temporalShear.setValues(matrix);c.concat(temporalShear)
                anchoredImageDrawn=true
            }
            c.drawBitmap(image,temporalSourceRect,temporalTargetRect,bitmapPaint);c.restore()
            if(anchored) {
                val at=ridgeCount*6
                ridgeSlots[ridgeCount++]=preparedSlot
                ridgeCoordinates[at]=olderX;ridgeCoordinates[at+1]=newerX
                ridgeCoordinates[at+2]=(target.top+(oldBottom/axisMeters-bounds.start)/(bounds.end-bounds.start)*target.height()).toFloat()
                ridgeCoordinates[at+3]=(target.top+(newBottom/axisMeters-bounds.start)/(bounds.end-bounds.start)*target.height()).toFloat()
                ridgeCoordinates[at+4]=top.toFloat();ridgeCoordinates[at+5]=bottom.toFloat()
            }
        }
    }
    private fun drawRegisteredRidgeCoverage(c:Canvas) {
        for(index in 0 until ridgeCount) {
            val slot=ridgeSlots[index];val at=index*6
            val oldX=ridgeCoordinates[at];val newX=ridgeCoordinates[at+1]
            val gradient=temporal.ridgeGradient(slot)
            if(gradient!=null) {
                ridgeGradientMatrix.setScale(newX-oldX,1f);ridgeGradientMatrix.postTranslate(oldX,0f)
                gradient.setLocalMatrix(ridgeGradientMatrix)
            }
            // Minimum native raster coverage when a measured interval is narrower
            // than a pixel or the registered echo crosses many rows in one pixel.
            ridgePaint.shader=gradient;ridgePaint.color=temporal.ridgeColor(slot)
            c.save();c.clipRect(oldX,ridgeCoordinates[at+4],newX,ridgeCoordinates[at+5])
            c.drawLine(oldX,ridgeCoordinates[at+2],newX,ridgeCoordinates[at+3],ridgePaint);c.restore()
        }
    }
    private fun drawBottomHistory(c:Canvas,controller:SonarNativeController) {
        val m=controller.model;val ring=m.viewRing
        if(!m.viewDisplay.overlayBottom || controller.reliefReady&&!controller.reliefAligned)return
        val b=controller.bounds();if(!b.available)return
        val axisMeters=m.viewMetersPerBin?:return
        var previousX=0f;var previousY=0f;var previousTime=0L;var linked=false
        val right=SonarLayout.plotRight(m.display.aScope);val width=right-SonarLayout.PLOT_LEFT
        for(age in m.visibleCount()-1 downTo 0) {
            val bottom=ring.bottomAt(age);val presentation=ring.presentationAt(age)
            if(!bottom.validated || presentation?.quality?.let{it!=SonarAnalysisQuality.LOCKED}==true) {linked=false;continue}
            val x=(right-(m.historyFractionAt(age)*width).toFloat()).coerceIn(SonarLayout.PLOT_LEFT+1f,right-2.5f)
            val axisBin=ring.axisBinAt(age,bottom.bin!!,axisMeters)
            if(!axisBin.isFinite()){linked=false;continue}
            val y=SonarLayout.TOP+((axisBin-b.start)/(b.end-b.start)*SonarLayout.HEIGHT).toFloat()
            if(y !in SonarLayout.TOP..SonarLayout.BOTTOM){linked=false;continue}
            val timestamp=ring.timestampAt(age)
            if(linked && timestamp-previousTime<=250) line(c,previousX,previousY,x,y,accent,3f)
            else line(c,x-1,y,x+1,y,accent,3f)
            previousX=x;previousY=y;previousTime=timestamp;linked=true
        }
    }
    private fun drawTracks(c:Canvas,controller:SonarNativeController) {
        val model=controller.model;val display=model.display
        if(!display.overlayTargets || model.viewMetersPerBin==null || controller.reliefReady&&!controller.reliefAligned)return
        val presentation=(if(externalData)controller.displayedGpuAnalysis()else model.viewPresentation)?:return
        val bounds=controller.bounds();if(!bounds.available)return
        val right=SonarLayout.plotRight(display.aScope)
        val color=if(night)0xffa74328.toInt() else 0xfff6cf77.toInt()
        for(track in presentation.tracks) {
            val y=SonarLayout.TOP+((track.bin-bounds.start)/(bounds.end-bounds.start)*SonarLayout.HEIGHT).toFloat()
            if(y in SonarLayout.TOP..SonarLayout.BOTTOM&&!externalData)line(c,right-30,y,right-4,y,color,3f)
        }
    }

    private fun drawScope(c: Canvas, controller: SonarNativeController) {
        val m=controller.model;val s=controller.snapshot;val d=s.display;if(!d.aScope)return
        val left=SonarLayout.SCOPE_LEFT;val top=SonarLayout.TOP;val bottom=SonarLayout.BOTTOM;val height=SonarLayout.HEIGHT
        line(c,left,top,left,bottom,structural)
        val aligned=controller.reliefEnabled&&controller.reliefAligned
        val shown=if(externalPlot)controller.displayedGpuSlice()else null
        val latest=if(externalPlot)controller.displayedGpuRawFrame()else m.viewRing.atAge(0)
        if(latest==null)return
        // During freeze the history axis may retain another calibration; A-scope stays in bins.
        val b=if(shown!=null)SonarNativeBounds(shown.top,shown.bottom,shown.viewportState)else controller.bounds()
        if(!b.available)return
        if(!externalData){
        scopePath.reset()
        for(y in 0..height.toInt()){
            val bin=(b.start+y/height*(b.end-b.start)).roundToInt().coerceIn(0,1023)
            val x=left+18+m.viewImageColumns.imageValuesAt(0,m.viewDisplay).amplitude(latest.sample(bin)/m.viewAmplitudeFullScale.toDouble()).toFloat()*214
            if(y==0)scopePath.moveTo(x,top) else scopePath.lineTo(x,top+y)
        }
        paint.style=Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=if(night)0xffa74328.toInt() else 0xff3dc9e9.toInt();c.drawPath(scopePath,paint);paint.style=Paint.Style.FILL
        if(m.viewBottomValid&&d.overlayBottom){
            val y=top+((m.viewBottom.bin!!-b.start)/(b.end-b.start)*height).toFloat()
            if(y in top..bottom)line(c,left,y,SonarLayout.RIGHT,y,if(night)0xffa74b27.toInt() else 0xfff6d671.toInt(),4f)
        }
        }
    }

    private fun drawCursor(c:Canvas,controller:SonarNativeController){
        val p=controller.cursor?:return;val scope=controller.snapshot.display.aScope
        val right=SonarLayout.plotRight(scope);val width=SonarLayout.plotWidth(scope)
        val color=if(night)0xffac3f2d.toInt() else 0xfff6cf77.toInt()
        line(c,SonarLayout.PLOT_LEFT+p.x*width,SonarLayout.TOP,SonarLayout.PLOT_LEFT+p.x*width,SonarLayout.BOTTOM,color)
        line(c,SonarLayout.PLOT_LEFT,SonarLayout.TOP+p.y*SonarLayout.HEIGHT,right,SonarLayout.TOP+p.y*SonarLayout.HEIGHT,color)
    }
    private fun drawCursorReading(c:Canvas,controller:SonarNativeController){
        val p=controller.cursor
        val depth=p?.let{if(it.depthM==null)"BIN ${it.bin}" else "${sonarNumber(depthUnit.fromMeters(it.depthM))} ${depthUnit.symbol}"}?:"—"
        val age=p?.let{"−${sonarNumber(it.secondsAgo)} s"}?:"—"
        val amplitude=p?.let{"${it.amplitude} / ${it.amplitudeFullScale}"}?:"—"
        text(c,"CURSOR  $depth",353f,890f,23f,secondary,maxWidth=345f,slots=NumericSlots(5,1))
        text(c,"ZEIT  $age",735f,890f,23f,secondary,maxWidth=345f,slots=NumericSlots(5,1))
        text(c,"AMP  $amplitude",1115f,890f,23f,secondary,maxWidth=541f,slots=NumericSlots(5,1))
    }
    private fun drawRail(c:Canvas,controller:SonarNativeController,audio:AudioNativeController?){
        val d=controller.snapshot.display
        val xs=floatArrayOf(353f,559f,735f,931f)
        val ws=floatArrayOf(190f,160f,180f,274f)
        val labels=listOf(if(d.freeze)No5Text.ui("RESUME")else No5Text.ui("FREEZE"),No5Text.ui("Bild Auto"), "A-SCOPE", No5Text.ui("Menü"))
        labels.forEachIndexed { i,label ->
            fill(c,RectF(xs[i]+4,960f,xs[i]+ws[i]-4,1036f),background,9f)
            outline(c,RectF(xs[i]+4,960f,xs[i]+ws[i]-4,1036f),9f)
            text(c,label,xs[i]+ws[i]/2,1009f,28f,ink,center=true,maxWidth=ws[i]-16)
        }
        // One immediate mode switch, not navigation or a second audio editor.
        val audioMode=controller.knobMode==SonarKnobMode.AUDIO
        val modeLeft=1333f;val modeRight=1656f;val modeCentre=(modeLeft+modeRight)/2f
        fill(c,RectF(modeLeft+4,960f,modeRight-4,1036f),background,9f)
        outline(c,RectF(modeLeft+4,960f,modeRight-4,1036f),9f)
        text(c,No5Text.ui("SONAR"),modeCentre-77f,1009f,26f,if(audioMode)secondary else accent,bold=!audioMode,center=true,maxWidth=112f)
        text(c,"⇄",modeCentre,1008f,24f,secondary,center=true,maxWidth=34f)
        text(c,No5Text.ui("AUDIO"),modeCentre+77f,1009f,26f,if(audioMode)accent else secondary,bold=audioMode,center=true,maxWidth=112f)
        val selectedX=modeCentre+if(audioMode)77f else -77f
        line(c,selectedX-46f,1023f,selectedX+46f,1023f,accent,3f)
        text(c,if(controller.knobLeaseReason==null)No5Text.ui("KNOPF-LEASE AKTIV") else No5Text.ui("KNÖPFE: %s",controller.knobLeaseReason),1000f,925f,18f,secondary,center=true,maxWidth=570f)
        val selectedFader=audio?.selectedFader()?.let{audio.displayName(it)}?:"—"
        text(c,if(audioMode)"$selectedFader / ${No5Text.ui("Stumm")}" else No5Text.ui("BEREICH / FREEZE"),353f,925f,24f,secondary,maxWidth=345f)
        text(c,No5Text.ui(if(audioMode)"MEDIEN / PLAY / PAUSE" else "GAIN / A-SCOPE"),1333f,925f,24f,secondary,maxWidth=323f)
    }
}
