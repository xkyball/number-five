package com.no5.helm

import android.os.Handler
import android.os.Looper
import com.no5.domain.*
import com.no5.header.*
import com.no5.header.android.HeaderAdmission
import com.no5.header.android.HeaderAndroidClient

/** Reads the existing raw source. It owns subscriptions, never a source/session factory or ports. */
internal class HelmHeaderProducer internal constructor(
    private val client: Client,
    private val sources: Sources,
    private val main: Main,
    private val epochMs: () -> Long,
    initialNight: Boolean = false,
) : AutoCloseable {
    constructor(client: HeaderAndroidClient, model: SourceSessionViewModel, initialNight: Boolean) : this(
        AndroidClient(client), ModelSources(model), AndroidMain(), System::currentTimeMillis, initialNight)

    internal interface Client : AutoCloseable {
        fun onResume()
        fun onPause()
        fun onWindowFocusChanged(focused: Boolean)
        fun state(page: HeaderPage, generation: Long, sourceSessionId: String?)
        fun retryAdmission()
        fun nextPublication(scope: HeaderAdmission): HeaderPublication?
        fun publish(scope: HeaderAdmission, snapshot: HeaderSnapshotV1, result: (HeaderPublishResult) -> Unit): Boolean
    }
    internal data class Source(val token: Any, val sessionId: String, val feed: SourceFeed<HelmState>,
        val policy: FreshnessPolicy, val units: DisplayUnits)
    internal interface Sources {
        val changes: SourceFeed<Long>
        /** Null immediately after owner disposal; an old UI lease is not an available source. */
        fun current(): Source?
    }
    internal interface Main {
        fun isMainThread(): Boolean
        fun post(task: Runnable): Boolean
        fun postDelayed(task: Runnable, milliseconds: Long)
        fun remove(task: Runnable)
    }

    private data class Origin(val sourceSerial: Long, val contentRevision: Long, val page: HeaderPage?,
        val generation: Long, val sourceSessionId: String?, val night: Boolean, val units: DisplayUnits,
        val policy: FreshnessPolicy, val pageStatus: String?, val scope: HeaderAdmission?)
    private data class Captured(val origin: Origin, val raw: HelmState)
    private var source: Source? = null
    private var sourceSerial = 0L
    private var sourceSubscription: SourceSubscription? = null
    private var ownerSubscription: SourceSubscription? = null
    private var page: HeaderPage? = HeaderPage.HELM
    private var generation = 1L
    private var contentRevision = 0L
    private var sourceSessionId: String? = null
    private var night = initialNight
    private var pageStatus: String? = null
    private var resumed = false
    private var focused = false
    private var scope: HeaderAdmission? = null
    private var publicationSerial = 0L
    private var inFlight: Long? = null
    @Volatile private var closed = false
    @Volatile private var origin = Origin(0, 0, HeaderPage.HELM, 1, null, initialNight,
        DisplayUnits(), QualityEvaluator.SHARED, null, null)
    private val pendingLock = Any()
    private var pending: Captured? = null
    private var posted = false
    private val drain = Runnable { drainRaw() }
    private val pulse = object : Runnable {
        override fun run() {
            checkMain()
            if (!active() || scope == null) return
            sampleCurrent()
            if (active() && scope != null) main.postDelayed(this, 500)
        }
    }

    init {
        checkMain()
        synchronizeSource()
        // Ownership notifications are synchronous Main events, including service disposal.
        ownerSubscription = sources.changes.subscribe { checkMain(); if (!closed) synchronizeSource() }
    }

    /** Actual committed UI page/theme only, including the implemented DASHBOARD safety rail. */
    fun updateUi(pageName: String, night: Boolean, pageStatus: String? = null) {
        checkMain(); if (closed) return
        val next = HeaderPage.entries.firstOrNull { it.name == pageName }
            ?.takeIf { HeaderApps.profile(HeaderApps.HELM, it) != null }
        val switched = next != page
        val changed = switched || this.night != night || this.pageStatus != pageStatus
        if (!changed) return
        this.night = night; this.pageStatus = pageStatus
        contentRevision = advance(contentRevision)
        if (switched) {
            generation = advance(generation); page = next; scope = null
            refreshOrigin()
            main.remove(pulse)
            if (next == null) {
                client.onPause() // Unknown pages cannot reuse a prior page's admitted header.
                return
            }
            client.state(next, generation, sourceSessionId)
            if (resumed) client.onResume()
            client.onWindowFocusChanged(focused)
            if (resumed && focused) client.retryAdmission()
        } else refreshOrigin()
        sampleCurrent() // Fresh synchronous raw read; does not relabel an earlier async callback.
    }

    fun onResume() {
        checkMain(); if (closed) return
        resumed = true; refreshOrigin()
        if (page != null) { client.onWindowFocusChanged(focused); client.onResume() }
        restartPulse()
    }
    fun onPause() {
        checkMain(); resumed = false; scope = null
        contentRevision = advance(contentRevision); refreshOrigin(); clearPending(); main.remove(pulse)
        if (!closed) client.onPause()
    }
    fun onWindowFocusChanged(value: Boolean) {
        checkMain(); if (closed) return
        focused = value
        if (!value) { scope = null; contentRevision = advance(contentRevision); clearPending() }
        refreshOrigin()
        if (page != null) {
            client.onWindowFocusChanged(value)
            if (value && resumed) client.retryAdmission()
        }
        restartPulse()
    }

    /** Called from HeaderAndroidClient's Main callback, including server OS-focus re-admission. */
    fun onAdmission(value: HeaderAdmission?) {
        checkMain(); if (closed) return
        scope = value?.takeIf { active() && it.ready && it.pageId == page &&
            it.pageGeneration == generation && it.sourceSessionId == sourceSessionId }
        refreshOrigin()
        if (scope != null) sampleCurrent()
        restartPulse()
    }

    private fun synchronizeSource() {
        val next = sources.current()
        val replaced = next?.token !== source?.token || next?.sessionId != source?.sessionId
        val unitsChanged = next?.units != source?.units
        if (!replaced && !unitsChanged) return
        if (replaced) {
            sourceSerial = advance(sourceSerial)
            sourceSubscription?.close(); sourceSubscription = null
            source = next
            sourceSessionId = next?.let { availableSession(it, it.feed.value) }
            generation = advance(generation); scope = null; contentRevision = advance(contentRevision)
            clearPending(); refreshOrigin(); main.remove(pulse)
            page?.let { client.state(it, generation, sourceSessionId) }
            if (next != null) {
                val serial = sourceSerial
                sourceSubscription = next.feed.subscribe { raw -> captureRaw(serial, raw) }
            }
        } else {
            source = next; contentRevision = advance(contentRevision); refreshOrigin()
            sampleCurrent()
        }
    }

    private fun availableSession(source: Source, raw: HelmState): String? =
        source.sessionId.takeUnless { raw.quality.status == TelemetryStatus.UNAVAILABLE }

    private fun captureRaw(serial: Long, raw: HelmState) {
        val capturedOrigin = origin // Immutable grant/page/source/theme captured BEFORE Main posting.
        if (closed || serial != capturedOrigin.sourceSerial) return
        val captured = Captured(capturedOrigin, raw.copy(telemetryFields = raw.telemetryFields.toMap()))
        if (main.isMainThread()) { acceptRaw(captured); return }
        var needsPost = false
        synchronized(pendingLock) {
            if (closed) return
            pending = captured // One conflated raw callback, never unbounded Main posts.
            if (!posted) { posted = true; needsPost = true }
        }
        if (needsPost && !main.post(drain)) clearPending()
    }

    private fun drainRaw() {
        checkMain()
        val value = synchronized(pendingLock) { val v = pending; pending = null; posted = false; v }
        if (value != null && !closed) acceptRaw(value)
    }

    private fun acceptRaw(captured: Captured) {
        checkMain(); if (closed) return
        val key = captured.origin
        if (key.sourceSerial != sourceSerial || key.contentRevision != contentRevision ||
            key.generation != generation || key.page != page) return
        val current = source ?: return
        val available = availableSession(current, captured.raw)
        if (available != sourceSessionId) {
            sourceSessionId = available; generation = advance(generation); scope = null
            contentRevision = advance(contentRevision); refreshOrigin(); clearPending(); main.remove(pulse)
            page?.let { client.state(it, generation, available) }
            return // This old grant cannot be stamped with the new source generation.
        }
        val grant = key.scope ?: return
        if (!active() || grant != scope || inFlight != null) return
        val publication = client.nextPublication(grant) ?: return
        val snapshot = HelmHeaderSnapshotBuilder.build(publication, requireNotNull(key.page), captured.raw,
            epochMs(), if (key.night) HeaderTheme.RED_NIGHT else HeaderTheme.DAY,
            key.policy, key.units, key.pageStatus, HeaderTransport.UNKNOWN)
        val serial = advance(publicationSerial).also { publicationSerial = it }
        inFlight = serial
        val queued = client.publish(grant, snapshot) {
            checkMain()
            if (inFlight == serial) inFlight = null
        }
        if (!queued && inFlight == serial) inFlight = null
    }

    private fun sampleCurrent() {
        if (!active()) return
        val current = source ?: return
        // Sampling occurs NOW on Main from the same current raw feed; sensor clocks stay original.
        captureRaw(sourceSerial, current.feed.value)
    }
    private fun refreshOrigin() {
        origin = Origin(sourceSerial, contentRevision, page, generation, sourceSessionId, night,
            source?.units ?: DisplayUnits(), source?.policy ?: QualityEvaluator.SHARED,
            pageStatus, scope.takeIf { active() })
    }
    private fun restartPulse() {
        main.remove(pulse)
        if (active() && scope != null) main.postDelayed(pulse, 500)
    }
    private fun active() = !closed && resumed && focused && page != null
    private fun clearPending() = synchronized(pendingLock) { pending = null }
    private fun advance(value: Long): Long { check(value < Long.MAX_VALUE); return value + 1 }
    private fun checkMain() = check(main.isMainThread()) { "Header state requires Main" }

    override fun close() {
        checkMain(); if (closed) return
        closed = true; scope = null; refreshOrigin(); clearPending(); main.remove(drain); main.remove(pulse)
        ownerSubscription?.close(); ownerSubscription = null
        sourceSubscription?.close(); sourceSubscription = null
        source = null
        client.close() // Subscriptions only. Never close the borrowed ViewModel/source/session ports.
    }

    private class AndroidClient(private val client: HeaderAndroidClient) : Client {
        override fun onResume() = client.onResume()
        override fun onPause() = client.onPause()
        override fun onWindowFocusChanged(focused: Boolean) = client.onWindowFocusChanged(focused)
        override fun state(page: HeaderPage, generation: Long, sourceSessionId: String?) =
            client.onPageSourceChanged(page, generation, sourceSessionId)
        override fun retryAdmission() = client.retryAdmission()
        override fun nextPublication(scope: HeaderAdmission) = client.nextPublication(scope)
        override fun publish(scope: HeaderAdmission, snapshot: HeaderSnapshotV1, result: (HeaderPublishResult) -> Unit) =
            client.publish(scope, snapshot, result)
        override fun close() = client.close()
    }
    private class ModelSources(private val model: SourceSessionViewModel) : Sources {
        override val changes get() = model.changes
        override fun current(): Source? {
            if (!model.isSourceOwnerAvailable) return null
            val session = model.session
            return Source(session, session.identity.sessionId, session.helm.snapshots,
                session.helm.freshnessPolicy, model.config.displayUnits)
        }
    }
    private class AndroidMain : Main {
        private val handler = Handler(Looper.getMainLooper())
        override fun isMainThread() = Looper.myLooper() === handler.looper
        override fun post(task: Runnable) = handler.post(task)
        override fun postDelayed(task: Runnable, milliseconds: Long) { handler.postDelayed(task, milliseconds) }
        override fun remove(task: Runnable) { handler.removeCallbacks(task) }
    }
}
