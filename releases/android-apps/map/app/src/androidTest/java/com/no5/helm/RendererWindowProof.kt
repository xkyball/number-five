package com.no5.helm

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.no5.helm.chart.ChartBufferSnapshot
import com.no5.helm.chart.ChartViewport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in instrumentation evidence, never attached to normal timed renderer arms.
 * One owned Bitmap at a time: Window -> compress/hash/recycle -> display composite.
 * No direct Surface readback, slot ACK, camera change or native frame attribution by "latest".
 */
internal class RendererWindowProof(
    private val activity: Activity,
    private val instrumentation: Instrumentation,
    outputParent: File,
    val runId: String = UUID.randomUUID().toString(),
) {
    private val main = Handler(Looper.getMainLooper())
    private val gate = Class.forName("com.no5.helm.ui.RendererWindowProofGate").getDeclaredConstructor().let {
        it.isAccessible = true; it.newInstance()
    }
    private val cancelled = AtomicBoolean()
    private val images = JSONArray()
    private val output = File(outputParent, UUID.fromString(runId).toString()).apply { check(mkdirs()) }
    private var imageSequence = 0
    private var finished = false

    private data class Observation(val presenter: View, val identity: Any)
    private data class WindowGeometry(val width: Int, val height: Int, val windowX: Int, val windowY: Int)
    private data class Copy(val bitmap: Bitmap, val observation: Observation, val geometry: WindowGeometry,
        val commitCallbackNs: Long, val beforeNs: Long, val afterNs: Long)

    fun peekBuffer(): ChartBufferSnapshot? = onMain { observation()?.takeIf { available(it.identity) }?.identity?.let(::buffer) }

    /** A settled real view only. Times are capture brackets, never a first-display/scanout timestamp. */
    @Synchronized fun capture(label: String, expectedViewport: ChartViewport? = null): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Image evidence must run off the main thread" }
        require(label.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        if (cancelled.get() || finished) return unavailable("CANCELLED")
        if ((invokeGate("copyAttempts") as Int) >= MAX_IMAGES) return unavailable("CAPACITY")
        val done = CountDownLatch(1)
        val abandoned = AtomicBoolean()
        val copied = AtomicReference<Copy?>()
        val reason = AtomicReference("UNAVAILABLE")
        val commit = AtomicReference<Pair<ViewTreeObserver, Runnable>?>()
        main.post {
            val initial = observation()
            if (initial == null || !available(initial.identity) || !expected(initial.identity, expectedViewport)) {
                reason.set(initial?.identity?.let { property(it, "unavailableReason").toString() } ?: "NO_PRESENTER")
                done.countDown(); return@post
            }
            val observer = activity.window.decorView.viewTreeObserver
            if (!observer.isAlive || !activity.window.decorView.isHardwareAccelerated) {
                reason.set("NO_HARDWARE_WINDOW"); done.countDown(); return@post
            }
            val committed = Runnable {
                val committedAt = System.nanoTime()
                // API29 commit means submitted Window content, explicitly not physical scanout.
                main.post commitRead@{
                    commit.set(null)
                    if (cancelled.get() || abandoned.get()) { reason.set("CANCELLED"); done.countDown(); return@commitRead }
                    val before = observation()
                    if (before == null || !stable(initial.identity, before.identity) || !expected(before.identity, expectedViewport)) {
                        reason.set("IDENTITY_CHANGED_BEFORE_COPY"); done.countDown(); return@commitRead
                    }
                    val geometry = windowGeometry()
                    if (geometry.width <= 0 || geometry.height <= 0 || geometry.width.toLong() * geometry.height > MAX_PIXELS) {
                        reason.set("BITMAP_BUDGET_UNAVAILABLE"); done.countDown(); return@commitRead
                    }
                    val started = System.nanoTime()
                    val ticket = invokeGate("begin", before.identity, started) as Long
                    if (ticket == 0L) { reason.set(property(gate, "lastReason").toString()); done.countDown(); return@commitRead }
                    val bitmap = try { Bitmap.createBitmap(geometry.width, geometry.height, Bitmap.Config.ARGB_8888) }
                    catch (_: OutOfMemoryError) {
                        invokeGate("complete", ticket, before.identity, false, System.nanoTime())
                        reason.set("ALLOCATION_UNAVAILABLE"); done.countDown(); return@commitRead
                    }
                    try {
                        PixelCopy.request(activity.window, bitmap, { status ->
                            val ended = System.nanoTime()
                            val after = observation()
                            val succeeded = status == PixelCopy.SUCCESS && after != null &&
                                windowGeometry() == geometry && !cancelled.get() && !abandoned.get()
                            val accepted = invokeGate("complete", ticket, after?.identity ?: before.identity, succeeded, ended) as Boolean
                            if (accepted && !cancelled.get() && !abandoned.get()) {
                                copied.set(Copy(bitmap, before, geometry, committedAt, started, ended)); reason.set("WINDOW_CAPTURED_WHILE_FULL_IDENTITY_STABLE")
                                // A timeout racing this callback has exactly one owner to recycle.
                                if (abandoned.get() || cancelled.get()) copied.getAndSet(null)?.bitmap?.recycle()
                            } else { bitmap.recycle(); reason.set(if (status == PixelCopy.SUCCESS) property(gate, "lastReason").toString() else "PIXEL_COPY_$status") }
                            done.countDown()
                        }, main)
                    } catch (_: IllegalArgumentException) {
                        bitmap.recycle(); invokeGate("complete", ticket, before.identity, false, System.nanoTime())
                        reason.set("PIXEL_COPY_UNAVAILABLE"); done.countDown()
                    }
                }
            }
            commit.set(observer to committed)
            observer.registerFrameCommitCallback(committed)
            // Deliberate evidence-only traversal; no producer, camera or slot mutation.
            activity.window.decorView.postInvalidateOnAnimation()
        }
        if (!done.await(COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            abandoned.set(true); copied.getAndSet(null)?.bitmap?.recycle()
            main.post { commit.getAndSet(null)?.let { (observer, callback) ->
                if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
            } }
            cancel()
            return unavailable("COPY_TIMEOUT")
        }
        val value = copied.getAndSet(null) ?: return unavailable(reason.get())
        if (cancelled.get()) { value.bitmap.recycle(); return unavailable("CANCELLED") }
        val index = imageSequence++
        val prefix = "${index.toString().padStart(2, '0')}-$label"
        val row = JSONObject().put("label", label).put("status", "WINDOW_CAPTURED_WHILE_FULL_IDENTITY_STABLE")
            .put("windowCommitCallbackMonoNs", value.commitCallbackNs)
            .put("captureStartedMonoNs", value.beforeNs).put("captureCompletedMonoNs", value.afterNs)
            .put("identity", identityJson(value.observation.identity)).put("physicalScanout", "NOT_MEASURED")
        val windowRect = rect(value.observation.identity, "windowRect")
        val chartRect = intArrayOf(windowRect[0] - value.geometry.windowX, windowRect[1] - value.geometry.windowY, windowRect[2], windowRect[3])
        val windowChartHash: String
        try {
            windowChartHash = pixelHash(value.bitmap, chartRect)
            row.put("windowChartArgbSHA256", windowChartHash)
            row.put("windowImage", save(value.bitmap, "$prefix-window.png"))
        } finally { value.bitmap.recycle() }
        // No Window Bitmap remains alive when UiAutomation allocates its screenshot.
        row.put("displayComposite", composite(value.observation.identity, windowChartHash, "$prefix-composite.png"))
        images.put(row)
        return row
    }

    private fun composite(identity: Any, windowChartHash: String, name: String): JSONObject {
        if (cancelled.get()) return unavailable("CANCELLED")
        val before = onMain { observation() }
        if (before == null || !stable(identity, before.identity)) return unavailable("IDENTITY_CHANGED_BEFORE_COMPOSITE")
        val withinBudget = onMain { val size = Point(); @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.getRealSize(size); size.x > 0 && size.y > 0 && size.x.toLong() * size.y <= MAX_PIXELS }
        if (!withinBudget) return unavailable("BITMAP_BUDGET_UNAVAILABLE")
        val started = System.nanoTime()
        val bitmap = try { instrumentation.uiAutomation.takeScreenshot() }
            catch (_: OutOfMemoryError) { null } catch (_: RuntimeException) { null }
            ?: return unavailable("COMPOSITE_UNAVAILABLE")
        val ended = System.nanoTime()
        try {
            val after = onMain { observation() }
            if (cancelled.get() || after == null || !stable(identity, after.identity)) return unavailable("IDENTITY_CHANGED_DURING_COMPOSITE")
            if (bitmap.width.toLong() * bitmap.height > MAX_PIXELS) return unavailable("BITMAP_BUDGET_UNAVAILABLE")
            val hash = pixelHash(bitmap, rect(identity, "screenRect"))
            return JSONObject().put("status", if (hash == windowChartHash) "WINDOW_CHART_PIXELS_MATCH_DISPLAY_COMPOSITE" else "PIXELS_DIFFERENT")
                .put("captureStartedMonoNs", started).put("captureCompletedMonoNs", ended)
                .put("chartArgbSHA256", hash).put("image", save(bitmap, name)).put("physicalScanout", "NOT_MEASURED")
        } finally { bitmap.recycle() }
    }

    fun cancel() { cancelled.set(true); invokeGate("cancel", System.nanoTime()) }

    @Synchronized fun finish(extra: JSONObject = JSONObject()): File {
        check(Looper.myLooper() != Looper.getMainLooper())
        finished = true
        val events = invokeGate("events") as LongArray
        val rows = JSONArray()
        for (offset in events.indices step 6) rows.put(JSONArray().apply { repeat(6) { put(events[offset + it]) } })
        return File(output, "proof.json").apply { writeText(JSONObject()
            .put("schema", "no5-renderer-window-proof/1").put("runId", runId)
            .put("scope", "SEPARATE_IMAGE_EVIDENCE_NOT_NORMAL_A_B_TIMING")
            .put("clock", "System.nanoTime / CLOCK_MONOTONIC")
            .put("maximumOwnedBitmaps", 1).put("maximumBitmapPixels", MAX_PIXELS)
            .put("maximumImageProofs", MAX_IMAGES).put("maximumPrimitiveEvents", 64)
            .put("copyAttempts", invokeGate("copyAttempts")).put("acceptedWindowCopies", invokeGate("acceptedCopies"))
            .put("eventColumns", JSONArray(listOf("kind", "monoNs", "ticket", "stamp", "cameraRevision", "reasonOrdinal")))
            .put("eventKinds", JSONObject().put("ARMED", 1).put("WINDOW_COPY_OBSERVED", 2).put("REJECTED", 3).put("CANCEL", 4))
            .put("reasonNames", JSONArray(Class.forName("com.no5.helm.ui.RendererWindowUnavailable").enumConstants!!.map { it.toString() }))
            .put("droppedEvents", invokeGate("droppedEvents")).put("events", rows).put("images", images)
            .put("physicalScanout", "NOT_MEASURED").put("firstDisplayTimestamp", JSONObject.NULL)
            .put("nauticalReferenceParity", "NOT_MEASURED").put("nativeFullPixelJoin", "NOT_MEASURED")
            .put("windowBufferId", "UNAVAILABLE_API29")
            .put("claim", "Window captured while the exact known FULL Texture/producer identity remained stable before and after PixelCopy. The Window pixels have not been joined to native FULL output pixels.")
            .put("limitations", "API29 exposes no Window buffer ID here. Stable Texture/producer identity plus a Window capture does not prove that PixelCopy contains that exact native FULL revision and supplies no per-revision first-display latency. The operator/test must leave the chart uncovered. Window/composite crop equality corroborates the captured visible content only, not native-output/reference parity or optical scanout. Capture/PNG/hash overhead invalidates normal A/B timing. No GPU/FPS inference.")
            .put("extra", extra).toString(2) + "\n") }
    }

    private fun observation(): Observation? {
        check(Looper.myLooper() == Looper.getMainLooper())
        val found = mutableListOf<View>()
        fun visit(view: View) {
            if (view.javaClass.name == "com.no5.helm.ui.ChartPresentationView" && view.isShown) found += view
            else if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(activity.window.decorView)
        val presenter = found.singleOrNull() ?: return null
        val getter = presenter.javaClass.declaredMethods.singleOrNull { it.name.startsWith("readWindowIdentity") && it.parameterCount == 0 } ?: return null
        getter.isAccessible = true
        return Observation(presenter, getter.invoke(presenter))
    }
    private fun windowGeometry(): WindowGeometry {
        val decor = activity.window.decorView; val origin = IntArray(2); decor.getLocationInWindow(origin)
        return WindowGeometry(decor.width, decor.height, origin[0], origin[1])
    }
    private fun available(identity: Any) = property(identity, "unavailableReason").toString() == "NONE"
    private fun stable(before: Any, after: Any) = invokeGate("stable", before, after) as Boolean
    private fun buffer(identity: Any) = property(identity, "buffer") as? ChartBufferSnapshot
    private fun expected(identity: Any, viewport: ChartViewport?) = viewport == null || buffer(identity)?.frame?.viewport == viewport
    private fun rect(identity: Any, name: String): IntArray = property(identity, name).let { value ->
        intArrayOf(property(value, "left") as Int, property(value, "top") as Int, property(value, "width") as Int, property(value, "height") as Int) }

    private fun identityJson(identity: Any): JSONObject {
        val exact = requireNotNull(buffer(identity))
        val view = exact.frame.viewport
        return JSONObject().put("stamp", exact.stamp).put("cameraRevision", exact.cameraRevision)
            .put("bufferGeneration", exact.generation).put("sourceGeneration", exact.frame.sourceGeneration)
            .put("contentGeneration", exact.frame.contentGeneration).put("producerCompleted", exact.producerCompleted)
            .put("stage", exact.stage.name).put("producerSlot", exact.presentationSlot)
            .put("presenterGeneration", property(identity, "presenterGeneration")).put("producerGeneration", property(identity, "producerGeneration"))
            .put("windowEpoch", property(identity, "windowEpoch")).put("attachmentEpoch", property(identity, "attachmentEpoch"))
            .put("frontSlot", property(identity, "frontSlot")).put("covered", property(identity, "covered"))
            .put("mode", property(identity, "mode").toString()).put("windowRect", JSONArray(rect(identity, "windowRect").toList()))
            .put("screenRect", JSONArray(rect(identity, "screenRect").toList()))
            .put("viewport", JSONObject().put("latitude", view.latitude).put("longitude", view.longitude)
                .put("metresToPixels", view.metresToPixels).put("rotationRadians", view.rotationRadians).put("width", view.width).put("height", view.height))
            .put("matrix", JSONObject().apply { val matrix = property(identity, "matrix")
                listOf("scaleX", "skewX", "translateX", "skewY", "scaleY", "translateY", "perspectiveX", "perspectiveY", "perspectiveScale")
                    .forEach { put(it, property(matrix, it)) } })
    }
    private fun save(bitmap: Bitmap, name: String): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        val file = File(output, name); val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(file.outputStream(), digest).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return JSONObject().put("file", name).put("bytes", file.length()).put("sha256", hex(digest.digest()))
    }
    private fun pixelHash(bitmap: Bitmap, area: IntArray): String {
        val (left, top, width, height) = area
        require(left >= 0 && top >= 0 && width > 0 && height > 0 && left + width <= bitmap.width && top + height <= bitmap.height)
        val pixels = IntArray(width); val bytes = ByteArray(width * 4); val digest = MessageDigest.getInstance("SHA-256")
        for (y in top until top + height) {
            bitmap.getPixels(pixels, 0, width, left, y, width, 1)
            pixels.forEachIndexed { index, pixel -> val offset = index * 4
                bytes[offset] = (pixel ushr 24).toByte(); bytes[offset + 1] = (pixel ushr 16).toByte()
                bytes[offset + 2] = (pixel ushr 8).toByte(); bytes[offset + 3] = pixel.toByte() }
            digest.update(bytes)
        }
        return hex(digest.digest())
    }
    private fun <T> onMain(read: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return read()
        val result = AtomicReference<T>(); val failed = AtomicReference<Throwable?>(); val done = CountDownLatch(1)
        main.post { try { result.set(read()) } catch (failure: Throwable) { failed.set(failure) } finally { done.countDown() } }
        check(done.await(COPY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Main-thread observation unavailable" }
        failed.get()?.let { throw it }; return result.get()
    }
    private fun property(value: Any, name: String): Any = value.javaClass.declaredMethods.single {
        it.parameterCount == 0 && it.name == "get" + name.replaceFirstChar(Char::uppercaseChar)
    }.let { it.isAccessible = true; it.invoke(value) ?: NULL_PROPERTY }
    private fun invokeGate(name: String, vararg arguments: Any): Any = gate.javaClass.declaredMethods.single {
        it.name == name && it.parameterCount == arguments.size
    }.let { it.isAccessible = true; it.invoke(gate, *arguments) ?: Unit }
    private fun unavailable(reason: String) = JSONObject().put("status", "UNAVAILABLE").put("reason", reason).put("physicalScanout", "NOT_MEASURED")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private val NULL_PROPERTY = Any()
        private const val MAX_IMAGES = 6
        private const val MAX_PIXELS = 3_000_000L
        private const val COPY_TIMEOUT_SECONDS = 10L
    }
}
