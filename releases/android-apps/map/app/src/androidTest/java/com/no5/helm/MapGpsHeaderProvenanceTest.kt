package com.no5.helm

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.header.HeaderProvenance
import com.no5.header.HeaderQuality
import com.no5.header.MapHeaderSourceInput
import com.no5.helm.client.HelmClient
import com.no5.helm.model.ConnectionState
import com.no5.helm.model.PositionChoice
import com.no5.helm.model.PositionStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * One owned normal Activity start, then observation of its existing client/controller. No navigation,
 * preference/source command, extra network client, GPS query or header publication is performed.
 * Proof files are private and contain no coordinates. A retained header model is not an IPC ACK.
 */
class MapGpsHeaderProvenanceTest {
    @Test fun existingGpsSnapshotsRetainIndependentHeaderProvenance() {
        assumeTrue("Explicit read-only provenance opt-in required", InstrumentationRegistry.getArguments()
            .getString("no5.gps.headerProvenance") == "true")
        val proof = RendererReleaseAcceptanceSupport()
        val samples = JSONArray()
        val reasons = LinkedHashMap<String, Int>()
        var attempts = 0
        var previousFix: String? = null
        var observedForMs = 0L
        val launchStarted = SystemClock.elapsedRealtime()
        var launchCompleted = 0L
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        lateinit var ownedActivity: MainActivity
        scenario.onActivity { ownedActivity = it }
        launchCompleted = SystemClock.elapsedRealtime()
        val started = launchCompleted
        val deadline = started + 20_000
        val mainReader = ReadOnlyMainReader(deadline)
        try {
        while (samples.length() < 2 && SystemClock.elapsedRealtime() < deadline && attempts < 200) {
            attempts++
            val sample = mainReader.read {
                val activity = MainActivity.benchmarkHost.get() ?: return@read Observation("NO_EXISTING_RESUMED_ACTIVITY")
                if (activity !== ownedActivity) return@read Observation("ACTIVITY_REPLACED")
                observe(activity)
            }
            val value = sample?.value
            if (value != null) {
                val fix = value.getString("backendSessionSha256") + ":" + value.getLong("sourceGeneration") + ":" +
                    value.getString("selectedSourceId") + ":" + value.getLong("originalFixEpochMs")
                if (fix != previousFix) { samples.put(value); previousFix = fix }
            } else {
                val reason = sample?.reason ?: "MAIN_READ_UNAVAILABLE"
                if (reason in reasons || reasons.size < 16) reasons[reason] = (reasons[reason] ?: 0) + 1
                if (reason == "ACTIVITY_REPLACED") break
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (samples.length() < 2 && remaining > 0) Thread.sleep(remaining.coerceAtMost(100))
        }
        } finally { mainReader.close() }
        observedForMs = SystemClock.elapsedRealtime() - started
        }
        proof.write("result.json", JSONObject().put("schema", "no5.map-gps-header-provenance/1")
            .put("run", proof.run).put("identity", proof.identity)
            .put("status", if (samples.length() == 2) "PASS" else "NOT_ESTABLISHED")
            .put("scope", "TWO_DISTINCT_EXISTING_SOURCE_FIXES_AND_RETAINED_HEADER_MODEL_ONLY")
            .put("ownedNormalActivityStart", true).put("ownedNormalActivityClose", true)
            .put("normalActivityLaunchMs", launchCompleted - launchStarted)
            .put("normalLifecycleSideEffects", "EXISTING_PRODUCTION_STARTUP_AND_CLOSE;NO_TEST_SOURCE_OR_PREFERENCE_COMMAND")
            .put("observationBudgetMs", 20_000).put("observedForMs", observedForMs)
            .put("attempts", attempts).put("samples", samples).put("unavailableReasons", JSONObject(reasons as Map<*, *>))
            .put("coordinatesRecorded", false).put("rawSessionIdentifiersRecorded", false)
            .put("additionalNetworkClients", 0).put("gpsQueries", 0).put("explicitTestUiOrSourceWrites", 0)
            .put("headerTransportAcknowledgement", "NOT_MEASURED")
            .put("timedOutMainReadPolicy", "RETIRED_CALLBACK;CONTINUE_SAME_DEADLINE;ONE_CALLBACK_IN_FLIGHT")
            .put("gps8PublisherAcceptance", "NOT_ESTABLISHED_BY_THIS_OBSERVER")
            .put("movingMultiReceiverAccuracy", "NOT_MEASURED").put("retry", false).toString(2), 64 * 1024)
    }

    private data class Observation(val reason: String? = null, val value: JSONObject? = null)

    private fun observe(activity: MainActivity): Observation {
      return try {
        fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        val lazyRuntime = field(activity, "runtime\$delegate") as? Lazy<*>
        if (lazyRuntime == null || !lazyRuntime.isInitialized()) return Observation("EXISTING_RUNTIME_UNAVAILABLE")
        val runtime = lazyRuntime.value as? MapSessionViewModel ?: return Observation("EXISTING_RUNTIME_UNAVAILABLE")
        val client = field(activity, "client") as? HelmClient ?: return Observation("EXISTING_CLIENT_UNAVAILABLE")
        if (client !== runtime.client) return Observation("CLIENT_OWNER_CHANGED")
        val backend = client.state.value
        val position = runtime.positions.state.value
        val raw = backend.snapshot ?: return Observation("NO_EXISTING_SNAPSHOT")
        if (backend.connection != ConnectionState.LIVE || !raw.gatewayV1) return Observation("TRANSPORT_NOT_LIVE")
        val aggregate = raw.gatewayTelemetryProvenance ?: return Observation("GLOBAL_PROVENANCE_UNCONFIRMED")
        val sources = position.sources ?: return Observation("GPS_CATALOGUE_UNAVAILABLE")
        if (!sources.valid || sources.sourceKind != "REAL" || sources.simulated) return Observation("GPS_PROVENANCE_UNCONFIRMED")
        val fix = position.observedAtEpochMs ?: return Observation("NO_ORIGINAL_FIX_TIME")
        if (position.status != PositionStatus.LIVE || position.awaitingFreshFix || position.ownship == null)
            return Observation("SELECTED_FIX_NOT_USABLE")
        val now = System.currentTimeMillis()
        if (fix > now || now - fix > sources.liveForMs) return Observation("ORIGINAL_FIX_NOT_FRESH")
        val selected = position.selectedSourceId ?: return Observation("NO_SELECTED_SOURCE")
        val contributors = if (position.intent.choice == PositionChoice.COMBINED) {
            val combined = sources.combination ?: return Observation("COMBINATION_UNAVAILABLE")
            if (!sources.combinationAvailable || selected != "@combined" || combined.observedAtEpochMs != fix)
                return Observation("COMBINATION_CONTEXT_MISMATCH")
            combined.contributors.map { it.sourceId to it.observedAtEpochMs }
        } else listOf(selected to fix)
        if (contributors.isEmpty() || contributors.size > 64 || contributors.any { (id, time) ->
                sources.sources.none { it.sourceId == id && it.observedAtEpochMs == time } })
            return Observation("CONTRIBUTOR_ASSOCIATION_UNCONFIRMED")
        // Inspect the source input already captured by the actual publisher closure. Do not invoke
        // that closure, create a publication, call update(), or manufacture a transport receipt.
        val producerDelegate = field(activity, "systemHeader\$delegate") as? Lazy<*>
        if (producerDelegate == null || !producerDelegate.isInitialized()) return Observation("HEADER_PRODUCER_UNAVAILABLE")
        val producer = requireNotNull(producerDelegate.value)
        val builder = field(producer, "builder") ?: return Observation("NO_RETAINED_HEADER_MODEL")
        val capture = builder.javaClass.declaredFields.singleOrNull { it.type == MapHeaderSourceInput::class.java }
            ?: return Observation("HEADER_CAPTURE_SHAPE_UNAVAILABLE")
        val header = capture.apply { isAccessible = true }.get(builder) as MapHeaderSourceInput
        if (header.sourceId != selected || header.observedAtEpochMs != fix || header.quality != HeaderQuality.LIVE ||
            header.provenance != HeaderProvenance.REAL) return Observation("HEADER_NOT_CURRENT_SELECTED_GPS")
        if (backend !== client.state.value || position !== runtime.positions.state.value)
            return Observation("STATE_CHANGED_DURING_MAIN_READ")
        val sourceGeneration = (field(activity, "headerSourceGeneration\$delegate") as? androidx.compose.runtime.State<*>)
            ?.value as? Long ?: return Observation("SOURCE_GENERATION_UNAVAILABLE")
        Observation(value = JSONObject().put("backendSessionSha256", sha256(raw.serverId))
            .put("backendRevision", raw.revision).put("sourceGeneration", sourceGeneration)
            .put("positionGeneration", position.generation).put("choice", position.intent.choice.name)
            .put("selectedSourceId", selected).put("originalFixEpochMs", fix).put("originalFixAgeMs", now - fix)
            .put("selectedStatus", position.status.name).put("selectedGpsSourceKind", sources.sourceKind)
            .put("selectedGpsSimulated", sources.simulated).put("awaitingFreshFix", position.awaitingFreshFix)
            .put("globalHelmSource", aggregate.source).put("globalHelmSimulated", aggregate.simulated)
            .put("contributors", JSONArray(contributors.map { (id, time) ->
                JSONObject().put("sourceId", id).put("originalFixEpochMs", time) }))
            .put("headerSourceId", header.sourceId).put("headerOriginalFixEpochMs", header.observedAtEpochMs)
            .put("headerQuality", header.quality.name).put("headerProvenance", header.provenance.name)
            .put("headerLabel", header.sourceLabel).put("headerDetail", header.sourceDetail)
            .put("headerEvidence", "ACTUAL_RETAINED_PUBLISHER_INPUT;NOT_TRANSPORT_ACK"))
    } catch (_: ReflectiveOperationException) { Observation("READ_ONLY_FIELD_SHAPE_UNAVAILABLE") }
      catch (_: RuntimeException) { Observation("LOCAL_MODEL_READ_UNAVAILABLE") }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** One observer, one absolute budget and at most one queued/running callback. */
    private class ReadOnlyMainReader(private val deadline: Long) : AutoCloseable {
        private val handler = Handler(Looper.getMainLooper())
        private val pending = AtomicReference<Read?>()
        private var closed = false // Only the instrumentation worker calls read/close.
        private class Read {
            val phase = AtomicInteger(QUEUED)
            val active = AtomicBoolean(true)
            val result = AtomicReference<Observation?>()
            val done = CountDownLatch(1)
            lateinit var callback: Runnable
        }
        fun read(observe: () -> Observation): Observation? {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (closed || remaining <= 0) return null
            val previous = pending.get()
            if (previous != null) {
                // A retired callback already running must finish before another is posted.
                // Its late result remains discarded even if it finishes during this wait.
                previous.done.await(remaining.coerceAtMost(250), TimeUnit.MILLISECONDS)
                return null
            }
            val read = Read()
            read.callback = Runnable {
                if (!read.phase.compareAndSet(QUEUED, RUNNING)) return@Runnable
                try {
                    if (read.active.get() && SystemClock.elapsedRealtime() < deadline) {
                        val value = observe()
                        if (read.active.get() && SystemClock.elapsedRealtime() < deadline) read.result.set(value)
                    }
                } finally {
                    read.phase.set(FINISHED)
                    pending.compareAndSet(read, null)
                    read.done.countDown()
                }
            }
            if (!pending.compareAndSet(null, read)) return null
            if (!handler.post(read.callback)) { retire(read); return null }
            val complete = read.done.await((deadline - SystemClock.elapsedRealtime()).coerceIn(0, 250), TimeUnit.MILLISECONDS)
            if (!complete) retire(read)
            return if (complete && read.active.get() && SystemClock.elapsedRealtime() < deadline) read.result.get() else null
        }
        private fun retire(read: Read) {
            read.active.set(false)
            if (read.phase.compareAndSet(QUEUED, RETIRED)) {
                handler.removeCallbacks(read.callback)
                pending.compareAndSet(read, null)
                read.done.countDown()
            }
            // RUNNING remains in pending until its finally; never overlap it with another read.
        }
        override fun close() {
            closed = true
            pending.get()?.let(::retire)
        }
        companion object {
            private const val QUEUED = 0
            private const val RUNNING = 1
            private const val FINISHED = 2
            private const val RETIRED = 3
        }
    }
}
