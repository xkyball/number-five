package com.no5.helm.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.no5.helm.chart.ChartBufferSnapshot
import com.no5.helm.chart.ChartBufferTransformMode
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.ChartRasterGeometry
import com.no5.helm.chart.chartRasterCrop
import com.no5.helm.chart.chartBufferCameraAffine

/** Two fixed producers. An opaque front wrapper conceals all back pixels, including transformed gaps. */
internal data class ChartPresentationSurfacePair(val first: Surface, val second: Surface, val width: Int, val height: Int)

internal class ChartPresentationView(context: Context, val chart: ChartSurfaceView,
    val pairingSupported: Boolean = true) : FrameLayout(context) {
    private val replacement = TextureView(context)
    private val textures = arrayOf<TextureView>(chart, replacement)
    private val wrappers = Array(2) { FrameLayout(context) }
    private val attachmentCover = View(context)
    private var world: WorldUnderlayView?=null
    var worldUnderlayEnabled=false;private set
    fun setWorldUnderlay(enabled:Boolean, style:String) {
        if(enabled && world==null){world=WorldUnderlayView(context);addView(world,0,LayoutParams(-1,-1))}
        val changed=worldUnderlayEnabled!=enabled
        worldUnderlayEnabled=enabled
        world?.visibility=if(enabled)VISIBLE else GONE
        if(enabled){world?.configurePalette(style);world?.camera=target?:chart.viewport}
        if(changed){
            wrappers.forEach{it.setBackgroundColor(if(enabled)Color.TRANSPARENT else Color.rgb(8,27,43))}
            attachmentCover.setBackgroundColor(if(enabled)Color.TRANSPARENT else Color.rgb(8,27,43))
            chart.isOpaque=!enabled && mode==ChartBufferTransformMode.OFF && !usePairedPresentation
        }
        syncWorldComposition()
    }
    fun updateWorldCamera(camera:ChartViewport){if(worldUnderlayEnabled)world?.camera=camera}
    private fun syncWorldComposition(){
        if(!worldUnderlayEnabled){wrappers.forEach{it.alpha=1f};return}
        val front=if(indexOfChild(wrappers[0])>indexOfChild(wrappers[1]))0 else 1
        val covered=sourceCoverRequired || indexOfChild(attachmentCover)>indexOfChild(wrappers[front])
        // Endpoint alpha keeps both producers attached. A hidden old Texture must
        // never show through the current frame's genuinely transparent pixels.
        for(slot in 0..1)wrappers[slot].alpha=if(!covered && slot==front)1f else 0f
    }
    private fun notifyVisiblePresentation(){syncWorldComposition();onVisiblePresentationChanged()}
    private val surfaces = arrayOfNulls<Surface>(2)
    private val surfaceTextures = arrayOfNulls<SurfaceTexture>(2)
    private val producerGenerations = LongArray(2) { -1L }
    private val replacementLoans = ChartSurfaceLoans()
    private val surfaceWidths = IntArray(2)
    private val surfaceHeights = IntArray(2)
    private val pendingAcquire = arrayOfNulls<SurfaceTexture>(2)
    internal val slots = ChartPresentationSlots()
    private var fitted: ChartSurfaceGeometry? = null
    private var generation = 0L
    private var mode = ChartBufferTransformMode.OFF
    private var target: ChartViewport? = null
    private var sourceAuthority: Long? = null
    private var sourceCoverRequired = false
    var onSurfacesReady: (Surface, Surface, Int, Int) -> Unit = { _, _, _, _ -> }
    var onSurfaceRelease: (() -> Unit) -> Unit = { release -> release() }
    var onOwnedSurfaceRelease: ((ChartSurfaceLoan, () -> Unit) -> Unit)? = null
    var onPresented: (Int, Long) -> Unit = { _, _ -> }
    var onAcquired: (Int, Long) -> Boolean = { _, _ -> true }
    var onWindowVisible: () -> Unit = {}
    /** Instrument observation only; never acquires/acknowledges a producer slot. */
    var onVisiblePresentationChanged: () -> Unit = {}
    private val visualMatrix = Matrix()
    private val visualCropGeometry = arrayOfNulls<ChartRasterGeometry>(2)
    private val visualCropX = DoubleArray(2) { Double.NaN }
    private val visualCropY = DoubleArray(2) { Double.NaN }
    val usePairedPresentation: Boolean get() = pairingSupported && isHardwareAccelerated && android.os.Build.VERSION.SDK_INT >= 29
    private val pollAcquire = Runnable { pollAcquisitions() }
    private var diagnosticWindowEpoch = 0L
    private var diagnosticAttachmentEpoch = 0L
    private var diagnosticUpdates = 0
    private val diagnosticFrameCallbacks = arrayOfNulls<Runnable>(8)
    private val diagnosticMatrix = Matrix()
    private val diagnosticMatrixValues = FloatArray(9)
    private var visibleWindowState = -1
    /** Identity proof only. The immutable pixels/camera/measurement remain solely in slots.visible. */
    private data class FrontOwner(val slot: Int, val stamp: Long, val bufferGeneration: Long,
        val texture: SurfaceTexture, val surface: Surface, val producerGeneration: Long,
        val attachmentEpoch: Long, val windowEpoch: Long, val sourceGeneration: Long)
    private var frontOwner: FrontOwner? = null
    private class VisibleFrameWake(val owner: Any, val first: Surface, val second: Surface?,
        val firstTexture: SurfaceTexture, val secondTexture: SurfaceTexture?,
        val windowEpoch: Long, val attachmentEpoch: Long, val ready: () -> Unit) {
        var observer: ViewTreeObserver? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var draw: ViewTreeObserver.OnDrawListener? = null
        var drawn = false
        var committed: Runnable? = null
    }
    private var visibleFrameWake: VisibleFrameWake? = null
    private var deliveredWakeOwner: Any? = null
    private var deliveredWakeWindowEpoch = -1L
    private var deliveredWakeAttachmentEpoch = -1L

    /** One retained-frame resend, only after HWUI has rebuilt the visible consumer layer. */
    fun scheduleVisibleFrameWake(owner: Any, ready: () -> Unit) {
        if (visibleWindowState!=VISIBLE || windowVisibility!=VISIBLE || !isAttachedToWindow) return
        val first=surfaces[0] ?: return
        val firstTexture=surfaceTextures[0] ?: return
        val second=if(usePairedPresentation) surfaces[1] ?: return else null
        val secondTexture=if(second!=null) surfaceTextures[1] ?: return else null
        if(firstTexture.isReleased || secondTexture?.isReleased==true) return
        val pending=visibleFrameWake
        if(pending?.owner===owner && pending.windowEpoch==diagnosticWindowEpoch &&
            pending.attachmentEpoch==diagnosticAttachmentEpoch) return
        if(deliveredWakeOwner===owner && deliveredWakeWindowEpoch==diagnosticWindowEpoch &&
            deliveredWakeAttachmentEpoch==diagnosticAttachmentEpoch) return
        cancelVisibleFrameWake()
        val wake=VisibleFrameWake(owner,first,second,firstTexture,secondTexture,
            diagnosticWindowEpoch,diagnosticAttachmentEpoch,ready)
        visibleFrameWake=wake
        val observer=viewTreeObserver
        wake.observer=observer
        val beforeDraw=ViewTreeObserver.OnPreDrawListener {
            wake.preDraw?.let { if(observer.isAlive) observer.removeOnPreDrawListener(it) };wake.preDraw=null
            if(!visibleFrameWakeCurrent(wake)) { if(visibleFrameWake===wake)cancelVisibleFrameWake() }
            else if(android.os.Build.VERSION.SDK_INT>=29 && isHardwareAccelerated) {
                val committed=Runnable { post { completeVisibleFrameWake(wake) } }
                wake.committed=committed
                observer.registerFrameCommitCallback(committed)
            } else {
                val drawn=ViewTreeObserver.OnDrawListener {
                    if(!wake.drawn) {
                        wake.drawn=true
                        // OnDraw listeners cannot be removed during dispatch.
                        // This post runs only after the software traversal drew.
                        post {
                            wake.draw?.let { if(observer.isAlive)observer.removeOnDrawListener(it) };wake.draw=null
                            completeVisibleFrameWake(wake)
                        }
                    }
                }
                wake.draw=drawn;observer.addOnDrawListener(drawn)
            }
            true
        }
        wake.preDraw=beforeDraw
        observer.addOnPreDrawListener(beforeDraw)
        postInvalidateOnAnimation()
    }
    private fun visibleFrameWakeCurrent(wake: VisibleFrameWake): Boolean =
        visibleFrameWake===wake && wake.windowEpoch==diagnosticWindowEpoch &&
            wake.attachmentEpoch==diagnosticAttachmentEpoch && isAttachedToWindow &&
            visibleWindowState==VISIBLE && windowVisibility==VISIBLE && isShown &&
            surfaces[0]===wake.first && (wake.second==null || surfaces[1]===wake.second) &&
            surfaceTextures[0]===wake.firstTexture && !wake.firstTexture.isReleased &&
            (wake.second==null || (surfaceTextures[1]===wake.secondTexture && wake.secondTexture?.isReleased==false))
    private fun completeVisibleFrameWake(wake: VisibleFrameWake) {
        if(!visibleFrameWakeCurrent(wake)) { if(visibleFrameWake===wake)cancelVisibleFrameWake();return }
        visibleFrameWake=null
        deliveredWakeOwner=wake.owner;deliveredWakeWindowEpoch=wake.windowEpoch;deliveredWakeAttachmentEpoch=wake.attachmentEpoch
        ChartSurfaceLifecycleTrace.record("VISIBLE_FRAME_WAKE", paired=usePairedPresentation)
        wake.ready()
    }
    fun cancelVisibleFrameWake() {
        val wake=visibleFrameWake ?: return
        visibleFrameWake=null
        val observer=wake.observer
        if(observer?.isAlive==true) {
            wake.preDraw?.let(observer::removeOnPreDrawListener)
            wake.draw?.let(observer::removeOnDrawListener)
            if(android.os.Build.VERSION.SDK_INT>=29) wake.committed?.let(observer::unregisterFrameCommitCallback)
        }
    }

    private fun resetConsumerDiagnostics() {
        diagnosticUpdates = 0
        val observer = viewTreeObserver
        if (android.os.Build.VERSION.SDK_INT >= 29 && observer.isAlive)
            diagnosticFrameCallbacks.forEach { it?.let(observer::unregisterFrameCommitCallback) }
        diagnosticFrameCallbacks.fill(null)
    }
    fun beginAttachmentDiagnostics() {
        cancelVisibleFrameWake()
        frontOwner = null
        diagnosticAttachmentEpoch++
        resetConsumerDiagnostics()
        notifyVisiblePresentation()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val changed=visibility!=visibleWindowState
        if(changed) {
            frontOwner = null
            visibleWindowState=visibility
            diagnosticWindowEpoch++
            cancelVisibleFrameWake();resetConsumerDiagnostics()
        }
        ChartSurfaceLifecycleTrace.record("WINDOW_VISIBILITY", current=visibility==VISIBLE, paired=usePairedPresentation)
        if(changed && visibility==VISIBLE) onWindowVisible()
        if(changed) notifyVisiblePresentation()
    }
    private fun traceConsumer(event: String, slot: Int, texture: SurfaceTexture, stamp: Long, currentStamp: Long = stamp) {
        val front = if (indexOfChild(attachmentCover)==childCount-1) 2
            else if (indexOfChild(wrappers[1])>indexOfChild(wrappers[0])) 1 else 0
        textures[slot].getTransform(diagnosticMatrix);diagnosticMatrix.getValues(diagnosticMatrixValues)
        ChartSurfaceLifecycleTrace.consumer(event,slot,stamp,currentStamp,chart.onDiagnosticBuffer(stamp),generation,
            diagnosticWindowEpoch,diagnosticAttachmentEpoch,mode.name,textures[slot].windowVisibility,
            textures[slot].isShown,textures[slot].isAvailable,surfaceTextures[slot]===texture,front,
            front==2&&attachmentCover.visibility==VISIBLE&&attachmentCover.alpha>0f,
            diagnosticMatrixValues[Matrix.MSCALE_X],diagnosticMatrixValues[Matrix.MSCALE_Y],
            diagnosticMatrixValues[Matrix.MTRANS_X],diagnosticMatrixValues[Matrix.MTRANS_Y])
    }
    /** Pure, on-demand image-proof read. No acquisition, ACK, redraw or latest-frame substitution. */
    internal fun readWindowIdentity(): RendererWindowIdentity {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return RendererWindowIdentity()
        val top = (0 until childCount).map { getChildAt(it) }.filter { it.visibility == VISIBLE && it.alpha > 0f }
            .maxWithOrNull(compareBy<View> { it.z }.thenBy { indexOfChild(it) })
        val front = when (top) { wrappers[0] -> 0; wrappers[1] -> 1; else -> -1 }
        val texture = surfaceTextures.getOrNull(front)
        val view = textures.getOrNull(front)
        val current = texture != null && !texture.isReleased && view?.isAvailable == true && view.surfaceTexture === texture
        val stamp = if (current) runCatching { requireNotNull(texture).timestamp }.getOrDefault(0L) else 0L
        val buffer = if (stamp > 0) chart.onDiagnosticBuffer(stamp) else null
        val values = FloatArray(9)
        view?.getTransform(Matrix())?.getValues(values)
        val window = IntArray(2); val screen = IntArray(2)
        getLocationInWindow(window); getLocationOnScreen(screen)
        val visibleRect = android.graphics.Rect()
        val full = getGlobalVisibleRect(visibleRect) && visibleRect.left == screen[0] && visibleRect.top == screen[1] &&
            visibleRect.width() == width && visibleRect.height() == height
        return RendererWindowIdentity(
            presenterOwner = this, textureOwner = texture, surfaceOwner = surfaces.getOrNull(front), readOnMain = true,
            visible = isAttachedToWindow && isHardwareAccelerated && hasWindowFocus() && windowVisibility == VISIBLE && isShown && alpha == 1f &&
                view?.isShown == true && view.alpha == 1f && wrappers.getOrNull(front)?.alpha == 1f,
            fullyVisible = full, covered = sourceCoverRequired || top === attachmentCover, currentTexture = current,
            frontSlot = front, stamp = stamp, buffer = buffer, presenterGeneration = generation,
            producerGeneration = producerGenerations.getOrNull(front) ?: -1, sourceAuthority = sourceAuthority,
            windowEpoch = diagnosticWindowEpoch, attachmentEpoch = diagnosticAttachmentEpoch, mode = mode,
            geometry = fitted, requestedViewport = chart.viewport, touchActive = chart.isTouchActive,
            matrix = RendererWindowMatrix(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8]),
            windowRect = RendererWindowRect(window[0], window[1], width, height),
            screenRect = RendererWindowRect(screen[0], screen[1], width, height),
        )
    }
    /** O(1), reusable VISUAL-only read. The stricter diagnostic Window identity above is unchanged. */
    internal fun readVisualPresentation(output: VisualPresentationSnapshot): Boolean {
        output.clear()
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || !isAttachedToWindow ||
            windowVisibility != VISIBLE || !isShown || alpha != 1f || sourceCoverRequired) return false
        var top: View? = null
        for (index in 0 until childCount) {
            val candidate = getChildAt(index)
            if (candidate.visibility == VISIBLE && candidate.alpha > 0f && (top == null || candidate.z >= top.z)) top = candidate
        }
        val slot = when (top) { wrappers[0] -> 0; wrappers[1] -> 1; else -> return false }
        val view = textures[slot]; val texture = surfaceTextures[slot] ?: return false
        val surface = surfaces[slot] ?: return false
        val geometry = fitted ?: return false
        if (!view.isAvailable || !view.isShown || view.alpha != 1f || wrappers[slot].alpha != 1f ||
            texture.isReleased || view.surfaceTexture !== texture || width != geometry.surfaceWidth || height != geometry.surfaceHeight ||
            view.width != width || view.height != height) return false
        val stamp = try { texture.timestamp } catch (_: IllegalStateException) { return false }
        val pin = ownedFront(slot, stamp, texture, surface, geometry)
        val diagnostic = chart.onDiagnosticBuffer(stamp)
        // A live diagnostic association may add completion/scale metadata, never change this image.
        if (pin != null && diagnostic != null && !sameFrontImage(pin, diagnostic)) {
            frontOwner = null
            return false
        }
        // Completion can arrive after acquisition. Keep that exact image's metadata in the
        // existing slot, so a later query cancellation does not lose already observed evidence.
        if (pin != null && diagnostic != null && diagnostic !== pin && !slots.refresh(diagnostic)) {
            frontOwner = null
            return false
        }
        val exact = diagnostic ?: pin ?: return false
        view.getTransform(visualMatrix); visualMatrix.getValues(output.matrix)
        if (texture.isReleased || surfaceTextures[slot] !== texture ||
            try { texture.timestamp != stamp } catch (_: IllegalStateException) { true }) {
            if (frontOwner?.slot == slot) frontOwner = null
            return false
        }
        output.presenterOwner = this; output.textureOwner = texture; output.surfaceOwner = surface
        output.observedStamp = stamp; output.buffer = exact; output.generation = generation
        output.pinnedFront = exact.takeIf { pin != null }
        output.producerGeneration = producerGenerations[slot]; output.sourceAuthority = sourceAuthority
        output.attachmentEpoch = diagnosticAttachmentEpoch; output.windowEpoch = diagnosticWindowEpoch
        output.frontSlot = slot; output.geometry = geometry; output.cropX = visualCropX[slot]; output.cropY = visualCropY[slot]
        output.available = true
        return true
    }

    private fun sameFrontImage(first: ChartBufferSnapshot, second: ChartBufferSnapshot): Boolean {
        val a = first.frame; val b = second.frame
        return first.stamp == second.stamp && first.generation == second.generation &&
            first.cameraRevision == second.cameraRevision && first.presentationSlot == second.presentationSlot &&
            first.stage == second.stage && first.raster == second.raster &&
            a.revision == b.revision && a.cameraToken == b.cameraToken && a.geometry == b.geometry &&
            a.viewport == b.viewport && a.sourceGeneration == b.sourceGeneration && a.contentGeneration == b.contentGeneration
    }

    private fun ownedFront(slot: Int, stamp: Long, texture: SurfaceTexture, surface: Surface,
        geometry: ChartSurfaceGeometry): ChartBufferSnapshot? {
        val owner = frontOwner ?: return null
        if (owner.slot == slot && (owner.stamp != stamp || owner.texture !== texture || owner.surface !== surface)) {
            // A real new/unknown image cannot later resurrect a previous timestamp's visual authority.
            frontOwner = null
            return null
        }
        val image = slots.visible ?: return null
        return image.takeIf {
            slots.front == slot && owner.slot == slot && owner.stamp == stamp && image.stamp == stamp &&
                image.generation == owner.bufferGeneration && image.presentationSlot == slot &&
                owner.texture === texture && owner.surface === surface &&
                owner.producerGeneration == producerGenerations[slot] &&
                owner.attachmentEpoch == diagnosticAttachmentEpoch && owner.windowEpoch == diagnosticWindowEpoch &&
                owner.sourceGeneration == sourceAuthority && image.frame.sourceGeneration == sourceAuthority &&
                image.frame.geometry == geometry
        }
    }

    private fun pinFrontOwner(slot: Int, image: ChartBufferSnapshot) {
        val texture = surfaceTextures[slot]
        val surface = surfaces[slot]
        frontOwner = if (texture != null && surface != null && textures[slot].surfaceTexture === texture &&
            producerGenerations[slot] >= 0) FrontOwner(slot, image.stamp, image.generation, texture, surface,
            producerGenerations[slot], diagnosticAttachmentEpoch, diagnosticWindowEpoch, image.frame.sourceGeneration)
        else null
    }
    /** Eight passive update/traversal samples per actual window/binding epoch, no ACK or redraw. */
    private fun diagnoseUpdated(slot: Int, texture: SurfaceTexture) {
        if (diagnosticUpdates>=diagnosticFrameCallbacks.size || surfaceTextures[slot]!==texture || texture.isReleased) return
        val index = diagnosticUpdates++
        val stamp = texture.timestamp
        val windowEpoch = diagnosticWindowEpoch;val attachmentEpoch = diagnosticAttachmentEpoch
        val expectedGeneration = generation
        traceConsumer("TEXTURE_UPDATED",slot,texture,stamp)
        postOnAnimation {
            if (windowEpoch==diagnosticWindowEpoch && attachmentEpoch==diagnosticAttachmentEpoch &&
                surfaceTextures[slot]===texture && !texture.isReleased)
                traceConsumer("TEXTURE_NEXT_ANIMATION",slot,texture,texture.timestamp)
        }
        if (android.os.Build.VERSION.SDK_INT>=29 && isHardwareAccelerated && viewTreeObserver.isAlive) {
            val committed = Runnable {
                post {
                    if (windowEpoch==diagnosticWindowEpoch && attachmentEpoch==diagnosticAttachmentEpoch) {
                        diagnosticFrameCallbacks[index] = null
                        val currentTexture = surfaceTextures[slot]===texture && !texture.isReleased
                        val currentStamp = if(currentTexture) texture.timestamp else -1L
                        val same = currentTexture && currentStamp==stamp && generation==expectedGeneration &&
                            windowVisibility==VISIBLE && chart.onDiagnosticBuffer(stamp)?.generation==expectedGeneration
                        traceConsumer(if(same)"TEXTURE_FRAME_COMMIT_MATCHED" else "TEXTURE_FRAME_COMMIT_STALE",slot,texture,stamp,currentStamp)
                    }
                }
            }
            diagnosticFrameCallbacks[index] = committed
            viewTreeObserver.registerFrameCommitCallback(committed)
        }
    }


    /** Source replacement revokes old pixels even when their camera buffer is reusable. */
    fun setSourceAuthority(next: Long) {
        if (sourceAuthority == next) return
        sourceAuthority = next
        sourceCoverRequired = true
        resetForSurfaceAttachment()
        attachmentCover.bringToFront()
        notifyVisiblePresentation()
    }

    private fun belongsToSource(image: ChartBufferSnapshot?): Boolean =
        image != null && (sourceAuthority == null || image.frame.sourceGeneration == sourceAuthority)

    /** Plain/unpaired rendering has no slot handshake; an exact source tag still gates its pixels. */
    fun observeSourceBuffer(timestamp: Long) {
        if (sourceCoverRequired && (mode == ChartBufferTransformMode.OFF || !usePairedPresentation))
            sample(0, timestamp, "SOURCE_BUFFER_ACQUIRED")
    }

    init {
        clipChildren = true
        for (slot in 0..1) {
            wrappers[slot].setBackgroundColor(Color.rgb(8, 27, 43))
            wrappers[slot].clipChildren = true
            textures[slot].isOpaque = false
            wrappers[slot].addView(textures[slot], LayoutParams(-1, -1))
            addView(wrappers[slot], LayoutParams(-1, -1))
        }
        attachmentCover.setBackgroundColor(Color.rgb(8, 27, 43))
        addView(attachmentCover, LayoutParams(-1, -1))
        wrappers[0].bringToFront()
        replacement.isClickable = false
        replacement.isOpaque = false
        replacement.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (replacementLoans.find(texture) != null && surfaceTextures[1] !== texture) return
                val surface = replacementLoans.create(texture) ?: return
                configureProducerTexture(texture, width, height)
                ChartSurfaceLifecycleTrace.record("AVAILABLE", 1, width=width, height=height, paired=usePairedPresentation)
                available(1, surface, width, height, texture, replacementLoans.generation(texture))
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                if (width > 0 && height > 0 && surfaceTextures[1] === texture) { configureProducerTexture(texture, width, height); resized(1, width, height) }
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val loan = replacementLoans.remove(texture) ?: return true
                destroyed(1, texture, loan) { replacementLoans.release(texture, loan) }; return false
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { if (surfaceTextures[1] === texture) updated(1, texture) }
        }
        chart.presentation = this
    }

    fun configure(value: ChartBufferTransformMode, nextGeneration: Long, geometry: ChartSurfaceGeometry?) {
        if (value == mode && generation == nextGeneration && geometry == fitted) return
        val preserveFront = mode != ChartBufferTransformMode.OFF && value != ChartBufferTransformMode.OFF &&
            geometry == fitted && slots.visible != null
        if (!preserveFront || value != mode) frontOwner = null
        mode = value; generation = nextGeneration; fitted = geometry
        if (!preserveFront) target = null
        pendingAcquire.fill(null); removeCallbacks(pollAcquire)
        slots.reset(generation, fitted, preserveFront)
        if (!preserveFront) {
            textures.forEach { it.setTransform(Matrix()); it.invalidate() }
            wrappers[0].bringToFront()
            if (mode != ChartBufferTransformMode.OFF && pairingSupported) attachmentCover.bringToFront()
        } else updateCamera(target)
        // OFF retains the untagged full-render control on slot 0.
        for (slot in 0..1) applyMatrix(slot)
        if (sourceCoverRequired) attachmentCover.bringToFront()
        invalidate()
        scheduleExistingAcquisitions()
        notifyVisiblePresentation()
    }

    /** A new EGL context always starts with producer front 0, even if the Android surfaces survived. */
    fun resetForSurfaceAttachment() {
        frontOwner = null
        chart.clearPresentationBasisForSurfaceAttachment()
        pendingAcquire.fill(null); removeCallbacks(pollAcquire)
        slots.reset(generation, fitted); target = null
        textures.forEach { it.setTransform(Matrix()); it.invalidate() }
        wrappers[0].bringToFront()
        if (sourceCoverRequired || (mode != ChartBufferTransformMode.OFF && pairingSupported)) attachmentCover.bringToFront()
        for (slot in 0..1) applyMatrix(slot)
        invalidate()
        scheduleExistingAcquisitions()
        notifyVisiblePresentation()
    }

    /** Fixed reserve buffers are created before EGL attachment, including OFF's static crop. */
    fun configureProducerTexture(texture: SurfaceTexture, width: Int, height: Int) {
        if (usePairedPresentation) {
            val raster = ChartRasterGeometry.reserve(ChartSurfaceGeometry.fit(width, height))
            texture.setDefaultBufferSize(raster.bufferWidth, raster.bufferHeight)
        } else texture.setDefaultBufferSize(width, height)
    }

    fun available(slot: Int, surface: Surface, width: Int, height: Int, texture: SurfaceTexture? = null, producerGeneration: Long = -1L) {
        if (frontOwner?.slot == slot && (surfaces[slot] !== surface || surfaceTextures[slot] !== texture ||
                producerGenerations[slot] != producerGeneration)) frontOwner = null
        surfaces[slot] = surface; surfaceTextures[slot] = texture; producerGenerations[slot] = producerGeneration
        resized(slot, width, height)
        notifyVisiblePresentation()
    }

    /** HWUI may restore visible-sized defaults when a retained TextureView's layer returns. */
    fun prepareSurfacesForAttachment(first: Surface, second: Surface, width: Int, height: Int): Boolean {
        val pair = availableSurfaces() ?: return false
        if (pair.first !== first || pair.second !== second || pair.width != width || pair.height != height) return false
        val firstTexture = surfaceTextures[0] ?: return false
        val secondTexture = surfaceTextures[1] ?: return false
        val raster = ChartRasterGeometry.reserve(ChartSurfaceGeometry.fit(width, height))
        // Always reapply immediately before EGL, including the same pair and
        // unchanged visible dimensions after STOP/START without new callbacks.
        firstTexture.setDefaultBufferSize(raster.bufferWidth, raster.bufferHeight)
        secondTexture.setDefaultBufferSize(raster.bufferWidth, raster.bufferHeight)
        ChartSurfaceLifecycleTrace.record("DEFAULT_BUFFER_PAIR", width=raster.bufferWidth, height=raster.bufferHeight, paired=true)
        return true
    }

    fun bindAttachmentOwner(first: Surface, second: Surface?, attachmentOwner: Any) {
        chart.bindSurfaceOwner(first, attachmentOwner)
        second?.let { replacementLoans.bind(it, attachmentOwner) }
    }

    internal fun resized(slot: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        surfaceWidths[slot] = width; surfaceHeights[slot] = height
        if (slot == 0) chart.resizeSurface(width, height)
        val first = surfaces[0]
        if (first != null && !usePairedPresentation) chart.onSurfaceReady(first, surfaceWidths[0], surfaceHeights[0])
        else availableSurfaces()?.let { pair -> onSurfacesReady(pair.first, pair.second, pair.width, pair.height) }
    }

    fun destroyed(slot: Int, texture: SurfaceTexture, loan: ChartSurfaceLoan, release: () -> Unit) {
        val current = surfaceTextures[slot] === texture && surfaces[slot] === loan.surface && producerGenerations[slot] == loan.generation
        ChartSurfaceLifecycleTrace.record("PAIR_DESTROYED", slot, current=current, paired=usePairedPresentation)
        if (current) {
            frontOwner = null
            surfaces[slot] = null; surfaceTextures[slot] = null; producerGenerations[slot] = -1L
            surfaceWidths[slot] = 0; surfaceHeights[slot] = 0
            pendingAcquire.fill(null); removeCallbacks(pollAcquire)
            slots.reset(generation, fitted)
            notifyVisiblePresentation()
        }
        onOwnedSurfaceRelease?.invoke(loan, release) ?: onSurfaceRelease(release)
    }

    fun availableSurfaces(): ChartPresentationSurfacePair? {
        val first = surfaces[0] ?: return null; val second = surfaces[1] ?: return null
        val width = surfaceWidths[0]; val height = surfaceHeights[0]
        if (width <= 0 || height <= 0 || width != surfaceWidths[1] || height != surfaceHeights[1]) return null
        return ChartPresentationSurfacePair(first, second, width, height)
    }

    fun updateCamera(camera: ChartViewport?) {
        target = camera
        camera?.let(::updateWorldCamera)
        for (slot in 0..1) applyMatrix(slot)
    }

    private fun applyMatrix(slot: Int) {
        val image = slots.image(slot)
        val geometry = fitted
        val cameraAffine = mode.transforms && image != null && target != null && geometry != null
        val staticRaster = if (!cameraAffine && usePairedPresentation && geometry != null) ChartRasterGeometry.reserve(geometry) else null
        val affine = if (cameraAffine)
            chartBufferCameraAffine(requireNotNull(image).frame.viewport, requireNotNull(target), requireNotNull(geometry), image.raster)
        else staticRaster?.let(::chartRasterCrop)
        val cropRaster = if (cameraAffine) image?.raster?.geometry else staticRaster
        if (cameraAffine && affine == null) {
            visualCropX[slot] = Double.NaN; visualCropY[slot] = Double.NaN
        } else if (cropRaster == null) {
            visualCropGeometry[slot] = null; visualCropX[slot] = 1.0; visualCropY[slot] = 1.0
        } else if (visualCropGeometry[slot] != cropRaster || !visualCropX[slot].isFinite()) {
            val crop = if (!cameraAffine) requireNotNull(affine) else chartRasterCrop(cropRaster)
            visualCropGeometry[slot] = cropRaster; visualCropX[slot] = crop.a; visualCropY[slot] = crop.d
        }
        val matrix = Matrix()
        affine?.let { matrix.setValues(it.matrixValues()) }
        textures[slot].setTransform(matrix)
        if (chart.inputSink()?.repaintTransforms != false) textures[slot].invalidate()
        notifyVisiblePresentation()
    }

    fun updated(slot: Int, texture: SurfaceTexture) {
        diagnoseUpdated(slot, texture)
        chart.recordTextureUpdate()
        notifyVisiblePresentation()
        if (mode == ChartBufferTransformMode.OFF && !sourceCoverRequired) return
        // TextureView invokes this during draw/applyUpdate. The wrapper order for that
        // traversal is already chosen, so revealing here and acknowledging that commit
        // would release the old slot one traversal too early. Promote before the next draw.
        if (slots.front != slot || slots.visible == null) {
            pendingAcquire[slot] = texture
            removeCallbacks(pollAcquire); postOnAnimation(pollAcquire)
        }
    }

    /** Generation/status delivery can follow an already acquired frame and its one update callback. */
    internal fun scheduleExistingAcquisitions(candidates: List<SurfaceTexture?> = textures.map { it.surfaceTexture }) {
        if ((!pairingSupported || mode == ChartBufferTransformMode.OFF) && !sourceCoverRequired) return
        candidates.take(if (pairingSupported) 2 else 1).forEachIndexed { slot, texture -> pendingAcquire[slot] = texture }
        if (pendingAcquire.any { it != null }) { removeCallbacks(pollAcquire); postOnAnimation(pollAcquire) }
    }

    internal fun pollAcquisitions() {
        var again = false
        for (slot in 0..1) {
            val texture = pendingAcquire[slot] ?: continue
            if (sample(slot, texture.timestamp, "NEXT_ANIMATION")) pendingAcquire[slot] = null else again = true
        }
        if (again) postOnAnimation(pollAcquire)
    }

    internal fun sample(slot: Int, tag: Long, phase: String): Boolean {
        val image = chart.onTaggedBuffer(tag)
        if (!belongsToSource(image)) return false
        if (mode == ChartBufferTransformMode.OFF || !pairingSupported || image?.presentationSlot == -1) {
            if (!sourceCoverRequired || slot != 0 || image?.producerCompleted != true ||
                image.generation != generation || image.frame.geometry != fitted) return false
            sourceCoverRequired = false
            pendingAcquire.fill(null)
            wrappers[0].bringToFront()
            invalidate()
            notifyVisiblePresentation()
            return true
        }
        val known = image != null && image.presentationSlot == slot && image.generation == generation && image.frame.geometry == fitted
        if (!slots.eligible(slot, image)) {
            val visible = known && slots.front == slot && slots.visible?.stamp == tag
            if (visible) chart.inputSink()?.texture(tag, true, phase)
            return visible
        }
        // Tell the producer which slot is about to become front before changing UI ordering.
        // Generation invalidation can cancel only packets that have not reached this handshake.
        if (!onAcquired(slot, tag)) return false
        val promoted = slots.acquire(slot, image) ?: return false
        pinFrontOwner(slot, promoted)
        sourceCoverRequired = false
        pendingAcquire.fill(null)
        chart.consumeTransformBuffer(promoted)
        chart.inputSink()?.texture(tag, true, "SLOT_ACQUIRED_REVEAL")
        applyMatrix(slot)
        wrappers[slot].bringToFront()
        invalidate()
        notifyVisiblePresentation()
        // Returning the old slot to the producer before this traversal commits would recreate the race.
        // This API29 hardware endpoint confirms window commitment, not optical display presentation.
        if (usePairedPresentation) viewTreeObserver.registerFrameCommitCallback {
            post {
                acknowledgeCommittedBuffer(slot, promoted.stamp, promoted.generation)
            }
        }
        return true
    }

    internal fun acknowledgeCommittedBuffer(slot: Int, stamp: Long, expectedGeneration: Long) {
        if (slots.committed(slot, stamp, expectedGeneration)) onPresented(slot, stamp)
    }

    fun refreshVisibleBuffer() {
        val current = slots.visible ?: return
        val updated = chart.onTaggedBuffer(current.stamp)
        if (!belongsToSource(updated)) return
        if (slots.refresh(updated)) chart.consumeTransformBuffer(updated)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ChartSurfaceLifecycleTrace.record("VIEW_ATTACHED", paired=usePairedPresentation)
    }
    override fun onDetachedFromWindow() {
        frontOwner = null
        cancelVisibleFrameWake()
        diagnosticWindowEpoch++;resetConsumerDiagnostics()
        ChartSurfaceLifecycleTrace.record("VIEW_DETACHED", current=false, paired=usePairedPresentation)
        pendingAcquire.fill(null); removeCallbacks(pollAcquire)
        slots.reset(generation, fitted)
        super.onDetachedFromWindow()
        notifyVisiblePresentation()
    }
}
