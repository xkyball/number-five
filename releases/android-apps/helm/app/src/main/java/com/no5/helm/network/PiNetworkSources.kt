package com.no5.helm.network

import com.no5.domain.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Each feature owns its own poll loop, deadline, transport, and failure state. */
internal class PiPollingFeed<T>(
    initial: T,
    private val path: String,
    private val transport: PiHttpTransport,
    scheduler: PiPollScheduler,
    policy: PiNetworkPolicy,
    private val now: () -> Long,
    private val decode: (String) -> T,
    private val timestamp: (T) -> Long?,
    private val age: (T) -> T,
    private val session: (T) -> String? = { null },
    private val onAccepted: (T) -> Unit = { },
    private val failure: (TelemetryStatus, T) -> T,
    /** Called on the existing single poll worker before any network I/O. */
    private val shouldPoll: () -> Boolean = { true },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val feed = MutableSourceFeed(initial)
    val snapshots: SourceFeed<T> get() = feed
    private var acceptedTimestamp: Long? = null
    private var acceptedSession: String? = null
    private val retiredSessions = LinkedHashSet<String>()
    private var receivedValidPayload = false
    private val job = try { scheduler.schedule(policy.pollIntervalMs) { poll() } } catch (failure: RuntimeException) {
        feed.close()
        transport.close()
        throw failure
    }

    private fun poll() {
        if (closed.get() || !shouldPoll()) return
        val requestGeneration = generation.get()
        val value = try {
            val decoded = decode(transport.get(path))
            val stamp = timestamp(decoded) ?: throw PiProtocolException("Missing source timestamp")
            val currentTime = now()
            if (stamp.toDouble() - currentTime.toDouble() > 5_000) throw PiProtocolException("Source timestamp is in the future")
            val decodedSession = session(decoded)
            val sessionChanged = decodedSession != acceptedSession
            if (sessionChanged && decodedSession in retiredSessions) throw PiProtocolException("Retired publisher session")
            if (!sessionChanged && acceptedTimestamp?.let { stamp < it } == true) throw PiProtocolException("Source timestamp moved backwards")
            val aged = age(decoded)
            onAccepted(decoded)
            if (sessionChanged) {
                acceptedSession?.let { retiredSessions.add(it) }
                if (retiredSessions.size > 16) retiredSessions.remove(retiredSessions.first())
                acceptedSession = decodedSession
                acceptedTimestamp = null
            }
            // Even tolerated clock skew must not poison the ordering watermark.
            if (stamp <= currentTime) {
                acceptedTimestamp = stamp
            }
            receivedValidPayload = true
            aged
        } catch (_: PiProtocolException) {
            failure(TelemetryStatus.INVALID, feed.value)
        } catch (_: Exception) {
            failure(if (receivedValidPayload) TelemetryStatus.LOST else TelemetryStatus.UNAVAILABLE, feed.value)
        }
        if (closed.get() || generation.get() != requestGeneration) return
        // close() closes the feed too, so a reply racing the final check still cannot publish.
        // A failed observer is removed by MutableSourceFeed; it must not stop future polls.
        // Deduplicate only the complete aged snapshot, after ordering and field-watermark
        // validation. New timestamps, sessions and quality transitions remain observable.
        if (value != feed.value) try { feed.emit(value) } catch (_: RuntimeException) { }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        feed.close()
        try { job.close() } finally { transport.close() }
    }
}

class NetworkPiHelmSource(
    override val identity: SourceIdentity,
    transport: PiHttpTransport,
    now: () -> Long = System::currentTimeMillis,
    scheduler: PiPollScheduler = BackgroundPiPollScheduler("helm"),
    policy: PiNetworkPolicy = PiNetworkPolicy.HELM,
) : HelmSource {
    init { require(identity.mode == SourceMode.PI) { "Network HELM requires a PI session" } }
    private val clock = now
    private val instrumentCadence = AtomicBoolean(true)
    private val lastBackgroundPollAt = AtomicLong(Long.MIN_VALUE)
    private val fieldWatermarks = mutableMapOf<String, Long>()
    private var fieldSession: String? = null
    private val polling = PiPollingFeed(
        initial = missing(TelemetryStatus.UNAVAILABLE), path = "/v1/helm", transport = transport,
        scheduler = scheduler, policy = policy, now = now, decode = { body ->
            PiV1Decoder.helm(body).also { state ->
                val current = now()
                if (state.telemetryFields.values.any { (it.quality.timestampEpochMs ?: current).toDouble() - current.toDouble() > 5_000 })
                    throw PiProtocolException("Field observation timestamp is in the future")
            }
        },
        timestamp = { it.quality.timestampEpochMs },
        session = { it.sourceSessionId },
        onAccepted = { state ->
            val sameSession = fieldSession == state.sourceSessionId
            for ((name, field) in state.telemetryFields) {
                val stamp = field.quality.timestampEpochMs
                if (sameSession && field.value != null && stamp != null && fieldWatermarks[name]?.let { stamp < it } == true)
                    throw PiProtocolException("Field observation moved backwards: $name")
            }
            if (!sameSession) fieldWatermarks.clear()
            fieldSession = state.sourceSessionId
            val current = now()
            for ((name, field) in state.telemetryFields) {
                val stamp = field.quality.timestampEpochMs
                if (field.value != null && stamp != null && stamp <= current) fieldWatermarks[name] = stamp
            }
        },
        age = { state ->
            val status = QualityEvaluator.evaluate(state.quality, now(), freshnessPolicy)
            if (status in setOf(TelemetryStatus.LOST, TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE))
                unavailableLike(state, status)
            else state.copy(quality = state.quality.copy(status = status))
        },
        failure = { status, previous -> unavailableLike(previous, status) },
        shouldPoll = ::pollRequired,
    )
    override val snapshots: SourceFeed<HelmState> get() = polling.snapshots
    override fun setUiDemand(instrumentCadence: Boolean) {
        this.instrumentCadence.set(instrumentCadence)
        // A return to the instrument surface must not inherit a one-second background wait.
        if (instrumentCadence) lastBackgroundPollAt.set(Long.MIN_VALUE)
    }
    override fun advance(elapsedMillis: Long) = Unit
    override fun close() = polling.close()
    private fun pollRequired(): Boolean {
        if (instrumentCadence.get()) return true
        val current = clock()
        while (true) {
            val previous = lastBackgroundPollAt.get()
            if (previous != Long.MIN_VALUE && current - previous in 0 until BACKGROUND_POLL_INTERVAL_MS) return false
            if (lastBackgroundPollAt.compareAndSet(previous, current)) return true
        }
    }
    private fun unavailableLike(previous: HelmState, status: TelemetryStatus) = HelmState.unavailable(source = previous.quality.source).copy(
        quality = previous.quality.copy(status = status),
        sourceSessionId = previous.sourceSessionId,
        simulationRunId = previous.simulationRunId,
        simulationElapsedSeconds = previous.simulationElapsedSeconds,
        simulationTimeScale = previous.simulationTimeScale,
        telemetryFields = previous.telemetryFields.mapValues { (_, field) -> TelemetryValue(null, field.quality.copy(status = status)) },
    )
    private fun missing(status: TelemetryStatus) = HelmState.unavailable(source = "NO5_GATEWAY").copy(
        quality = TelemetryQuality(status, source = "NO5_GATEWAY"),
    )
    private companion object { const val BACKGROUND_POLL_INTERVAL_MS = 1_000L }
}

class NetworkPiWeatherSource(
    override val identity: SourceIdentity,
    transport: PiHttpTransport,
    now: () -> Long = System::currentTimeMillis,
    scheduler: PiPollScheduler = BackgroundPiPollScheduler("weather"),
    policy: PiNetworkPolicy = PiNetworkPolicy.WEATHER,
) : WeatherSourcePort {
    init { require(identity.mode == SourceMode.PI) { "Network weather requires a PI session" } }
    private val polling = PiPollingFeed(
        initial = missing(TelemetryStatus.UNAVAILABLE), path = "/v1/weather", transport = transport,
        scheduler = scheduler, policy = policy, now = now,
        decode = { PiV1Decoder.weather(it).copy(receivedAtEpochMs = now()) },
        timestamp = { it.timestampEpochMs },
        age = { snapshot ->
            age(snapshot, now())
        },
        failure = { status, previous ->
            if (status == TelemetryStatus.LOST && previous.provider?.kind == WeatherProviderKind.FORECAST &&
                previous.status in setOf(TelemetryStatus.LIVE, TelemetryStatus.STALE)) {
                age(previous.copy(status = TelemetryStatus.STALE, error = "Gateway weather request failed; cached forecast retained"), now())
            } else previous.copy(status = status, values = WeatherValues(), error = "Gateway weather request failed")
        },
    )
    override val snapshots: SourceFeed<WeatherSnapshot> get() = polling.snapshots
    override val freshnessPolicy: FreshnessPolicy get() = WeatherFreshness.policyFor(snapshots.value)
    override fun advance(elapsedMillis: Long) = Unit
    override fun close() = polling.close()
    private fun missing(status: TelemetryStatus) = WeatherSnapshot.unavailable("Gateway weather is not available").copy(status = status)
    private fun age(snapshot: WeatherSnapshot, now: Long): WeatherSnapshot {
        val status = WeatherFreshness.evaluate(snapshot, now, WeatherFreshness.policyFor(snapshot))
        return snapshot.copy(status = status, values = if (status in setOf(TelemetryStatus.LOST, TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE)) WeatherValues() else snapshot.values)
    }
}
