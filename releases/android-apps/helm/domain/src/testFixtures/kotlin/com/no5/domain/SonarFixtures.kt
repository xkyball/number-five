package com.no5.domain

import kotlin.math.*
import java.util.concurrent.atomic.AtomicLong

private object FixtureSonarSessions {
    private val counter = AtomicLong()
    fun next(mode: SourceMode) = SourceIdentity("sonar-${mode.name.lowercase()}-${counter.incrementAndGet()}", mode, "SONAR ${mode.name}")
}

/** Optional fixture API. Production adapters need only implement SonarSourcePort. */
interface SonarDemoControls {
    fun loadFixture(fixture: SonarFixture, request: SonarAcquisitionRequest): SonarAcquisitionOutcome
}

/** An isolated deterministic source; it owns generation, timestamps, calibration and test samples. */
class DeterministicMockSonarSource(
    override val identity: SourceIdentity = FixtureSonarSessions.next(SourceMode.DEMO),
    private val now: () -> Long = { 1_000_000L },
) : SonarSourcePort, SonarDemoControls {
    init { require(identity.mode == SourceMode.DEMO) }
    private val mutableStatus = MutableSourceFeed(SonarSourceStatus(identity))
    private val mutableCapabilities = MutableSourceFeed(SonarSourceCapabilities(identity, canStart = true, canStop = true))
    private val mutableFrames = MutableSourceFeed<SonarSourceFrame?>(null)
    override val status: SourceFeed<SonarSourceStatus> get() = mutableStatus
    override val capabilities: SourceFeed<SonarSourceCapabilities> get() = mutableCapabilities
    override val frames: SourceFeed<SonarSourceFrame?> get() = mutableFrames
    private var closed = false
    private var lastRequestId = -1L
    private var calibratedFixture = false

    private fun validate(request: SonarAcquisitionRequest): SonarAcquisitionOutcome.Rejected? {
        val rejection = when {
            closed -> SonarRejection.CLOSED to "Sonar source is closed."
            request.sessionId != identity.sessionId -> SonarRejection.WRONG_SESSION to "Sonar session no longer matches."
            request.requestId <= lastRequestId -> SonarRejection.STALE_REQUEST to "Sonar request is stale."
            else -> null
        }
        if (rejection != null) return SonarAcquisitionOutcome.Rejected(identity.sessionId, request.requestId, rejection.first, rejection.second)
        lastRequestId = request.requestId
        return null
    }
    private fun ack(request: SonarAcquisitionRequest) = SonarAcquisitionOutcome.Acknowledged(identity.sessionId, request.requestId)

    override fun start(request: SonarAcquisitionRequest): SonarAcquisitionOutcome {
        validate(request)?.let { return it }
        if (!status.value.connected) seed(SonarFixture.UNCALIBRATED)
        else if (!status.value.running) {
            mutableStatus.emit(status.value.copy(running = true))
            tick()
        }
        return ack(request)
    }
    override fun stop(request: SonarAcquisitionRequest): SonarAcquisitionOutcome {
        validate(request)?.let { return it }
        mutableStatus.emit(status.value.copy(running = false))
        return ack(request)
    }
    override fun loadFixture(fixture: SonarFixture, request: SonarAcquisitionRequest): SonarAcquisitionOutcome {
        validate(request)?.let { return it }
        if (fixture == SonarFixture.DROPOUT) mutableStatus.emit(status.value.copy(connected = false, running = false))
        else seed(fixture)
        return ack(request)
    }
    private fun seed(fixture: SonarFixture) {
        val generation = status.value.generation + 1
        calibratedFixture = fixture == SonarFixture.CALIBRATED
        mutableFrames.emit(null)
        mutableCapabilities.emit(SonarSourceCapabilities(identity, canStart = true, canStop = true, bottomDetection = calibratedFixture))
        mutableStatus.emit(SonarSourceStatus(identity, generation))
        if (fixture == SonarFixture.CLEAR) return
        mutableStatus.emit(SonarSourceStatus(identity, generation, connected = true, running = true))
        val stamp = now()
        for (ping in 0 until 800) {
            if (closed || !status.value.running || status.value.generation != generation) break
            emitFrame(ping.toLong(), stamp - (799 - ping) * 100)
        }
    }
    private fun emitFrame(pingId: Long, timestamp: Long) {
        val frame = SonarFrame(sonarFixtureSamples((pingId % Int.MAX_VALUE).toInt()), pingId, timestamp)
        mutableFrames.emit(SonarSourceFrame(identity, status.value.generation, frame,
            calibration = if (calibratedFixture) Calibration(true, 20.0) else Calibration(),
            bottom = if (calibratedFixture) SonarBottom(530 + sin(pingId / 65.0) * 75, .94, .87) else SonarBottom()))
    }
    override fun tick() {
        if (closed || !status.value.connected || !status.value.running) return
        val previous = frames.value?.frame
        emitFrame((previous?.pingId ?: -1L) + 1, max(now(), previous?.timestampEpochMs ?: Long.MIN_VALUE))
    }
    override fun close() {
        if (closed) return
        closed = true
        try {
            mutableStatus.emit(status.value.copy(connected = false, running = false))
        } finally {
            try {
                mutableCapabilities.emit(SonarSourceCapabilities(identity))
            } finally {
                try {
                    mutableFrames.emit(null)
                } finally {
                    try { mutableStatus.close() } finally {
                        try { mutableCapabilities.close() } finally { mutableFrames.close() }
                    }
                }
            }
        }
    }
}


enum class SonarFixture { SIGNAL, UNCALIBRATED, CALIBRATED, DROPOUT, CLEAR }

fun SonarModel.fixture(name: SonarFixture, timestampEpochMs: Long = 100_000L): SonarSnapshot {
        if (name == SonarFixture.DROPOUT) { connect("TEST · deterministic binary fixture", demo = true); disconnect(); return snapshot() }
        resetAcquisition()
        if (name == SonarFixture.CLEAR) return snapshot()
        connect("TEST · deterministic binary fixture", demo = true); acquisition(true)
        val now = timestampEpochMs
        if (name == SonarFixture.CALIBRATED) setCalibration(Calibration(true, 20.0))
        for (p in 0 until 800) {
            if (name == SonarFixture.CALIBRATED) setBottom(SonarBottom(530 + sin(p / 65.0) * 75, .94, .87), capability = true)
            frame(SonarFrame(sonarFixtureSamples(p), p.toLong(), now - (799 - p) * 100))
        }
        return snapshot()
    }

fun sonarFixtureSamples(ping: Int): ShortArray = ShortArray(SONAR_BINS) { i ->
            val hash = ((i.toLong() * 16807 + ping.toLong() * 48271) % 2147483647) % 997
            val center = 530 + sin(ping / 65.0) * 75
            val ridge = exp(-((i - center) / 13).pow(2))
            val echo = exp(-((i - (285 + 35 * sin(ping / 38.0))) / 6).pow(2)) * if (ping > 270 && ping < 380) .6 else 0.0
            floor((.016 + hash / 997.0 * .035 + ridge * .85 + echo).coerceIn(0.0, 1.0) * 65535 + .5).toInt().toShort()
        }
