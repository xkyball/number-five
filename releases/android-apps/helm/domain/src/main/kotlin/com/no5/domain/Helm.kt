package com.no5.domain

import java.time.LocalTime
import kotlin.math.sin

enum class EngineSide { PORT, STARBOARD }
enum class ElectricalBus { STARTER_PORT, STARTER_STARBOARD, HOUSE }
enum class FuelTank { PORT, STARBOARD }
/** Immutable adapter snapshot. Default values form the explicitly labeled demo fixture. */
data class HelmState(
    val portRpm: Double = 3_400.0,
    val starboardRpm: Double = 3_420.0,
    val portTrim: Double = 72.0,
    val starboardTrim: Double = 68.0,
    val heading: Double = 0.0,
    val speed: Double = 21.8,
    val depth: Double? = 5.2,
    val waterTemp: Double? = 18.4,
    val portVoltage: Double = 13.8,
    val starboardVoltage: Double = 13.7,
    val houseVoltage: Double = 12.8,
    val portFuel: Double = 68.0,
    val starboardFuel: Double = 68.0,
    val time: LocalTime = LocalTime.of(14, 23),
    val timeTick: Long = 0,
    val running: Boolean = true,
    val telemetryAgeMs: Long = 0,
    val theme: Theme = Theme.DAY,
    val quality: TelemetryQuality = TelemetryQuality(TelemetryStatus.LIVE, source = "LOCAL_HELM_DEMO", demo = true),
    val portCoolantC: Double? = 82.0,
    val starboardCoolantC: Double? = 83.0,
    val portOilBar: Double? = 4.2,
    val starboardOilBar: Double? = 4.1,
    val portFuelFlowLph: Double? = 46.2,
    val starboardFuelFlowLph: Double? = 47.1,
    val totalFuelFlowLph: Double? = 93.3,
    val portGear: String? = "F",
    val starboardGear: String? = "F",
    val latitudeDegrees: Double? = -34.00205,
    val longitudeDegrees: Double? = 18.874266666666667,
    val fuelRemainingLiters: Double? = 420.0,
    val courseOverGroundDegrees: Double? = 0.0,
    val tripDistanceNm: Double? = null,
    val tripDurationSeconds: Double? = null,
    val fuelUsedLiters: Double? = null,
    val efficiencyNmPerLiter: Double? = null,
    val simulationPhase: String? = null,
    /** Per-field observations from the gateway. Missing wire values never become numeric zero. */
    val telemetryFields: Map<String, TelemetryValue> = emptyMap(),
    val sourceSessionId: String? = null,
    /** Optional simulator continuity metadata; never used as a source freshness timestamp. */
    val simulationRunId: String? = null,
    val simulationElapsedSeconds: Double? = null,
    val simulationTimeScale: Double? = null,
) {
    val mode: OperatingMode get() = when {
        quality.simulated -> OperatingMode.DEMO
        quality.status == TelemetryStatus.UNAVAILABLE || quality.source.isNullOrBlank() -> OperatingMode.UNAVAILABLE
        else -> OperatingMode.LIVE
    }

    companion object {
        /** No retained demo telemetry may become a Pi snapshot. Quality hides all scalars. */
        fun unavailable(theme: Theme = Theme.DAY, source: String? = null) = HelmState(
            portRpm = 0.0, starboardRpm = 0.0, portTrim = 0.0, starboardTrim = 0.0,
            heading = 0.0, speed = 0.0, depth = null, waterTemp = null,
            portVoltage = 0.0, starboardVoltage = 0.0, houseVoltage = 0.0,
            portFuel = 0.0, starboardFuel = 0.0, time = LocalTime.MIDNIGHT,
            running = false, theme = theme,
            quality = TelemetryQuality(TelemetryStatus.UNAVAILABLE, source = source, demo = false),
            portCoolantC = null, starboardCoolantC = null, portOilBar = null, starboardOilBar = null,
            portFuelFlowLph = null, starboardFuelFlowLph = null, totalFuelFlowLph = null,
            portGear = null, starboardGear = null, latitudeDegrees = null, longitudeDegrees = null,
            fuelRemainingLiters = null, courseOverGroundDegrees = null,
        )
    }
}
sealed interface HelmInput {
    data class Rpm(val side: EngineSide, val value: Double) : HelmInput
    data class Trim(val side: EngineSide, val value: Double) : HelmInput
    data class Heading(val value: Double) : HelmInput
    data class Speed(val value: Double) : HelmInput
    data class Depth(val value: Double) : HelmInput
    data class WaterTemperature(val value: Double) : HelmInput
    data class Voltage(val bus: ElectricalBus, val value: Double) : HelmInput
    data class Fuel(val tank: FuelTank, val value: Double) : HelmInput
    data class TimeTick(val seconds: Long = 1) : HelmInput
    data class SetTime(val time: LocalTime) : HelmInput
    data class SetTheme(val theme: Theme) : HelmInput
    data class SetRunning(val running: Boolean) : HelmInput
    data class SetQuality(val status: TelemetryStatus) : HelmInput
    data class SetTelemetryAge(val ageMs: Long) : HelmInput
}
data class HelmUpdate(val accepted: Boolean, val state: HelmState, val error: String? = null)
interface HelmRepository { val state: HelmState; fun update(input: HelmInput): HelmUpdate }
class DemoHelmRepository(initial: HelmState = HelmState()) : HelmRepository {
    override var state: HelmState = initial
        private set
    init { require(initial.quality.demo) { "Demo repository requires explicitly labeled simulation" }; validate(initial) }
    fun reset(): HelmState { state = HelmState(theme = state.theme); return state }
    fun tick(seconds: Long = 1): HelmUpdate = if (state.running) update(HelmInput.TimeTick(seconds)) else HelmUpdate(true, state)
    override fun update(input: HelmInput): HelmUpdate {
        return try {
            val next = when (input) {
                is HelmInput.Rpm -> {
                    checkNumber(input.value, 0.0..7_000.0)
                    // These coefficients belong only to the local simulator, never to a renderer or Pi adapter.
                    val changed = if (input.side == EngineSide.PORT) state.copy(portRpm = input.value, portFuelFlowLph = input.value * (46.2 / 3_400.0))
                    else state.copy(starboardRpm = input.value, starboardFuelFlowLph = input.value * (47.1 / 3_420.0))
                    changed.copy(totalFuelFlowLph = changed.portFuelFlowLph?.let { port -> changed.starboardFuelFlowLph?.let { port + it } })
                }
                is HelmInput.Trim -> { checkNumber(input.value, 0.0..100.0); if (input.side == EngineSide.PORT) state.copy(portTrim = input.value) else state.copy(starboardTrim = input.value) }
                is HelmInput.Heading -> { require(input.value.isFinite() && input.value >= 0 && input.value <= 360); val value = if (input.value == 360.0) 0.0 else input.value; state.copy(heading = value, courseOverGroundDegrees = value) }
                is HelmInput.Speed -> { checkNumber(input.value, 0.0..200.0); state.copy(speed = input.value) }
                is HelmInput.Depth -> { checkNumber(input.value, 0.0..12_000.0); state.copy(depth = input.value) }
                is HelmInput.WaterTemperature -> { checkNumber(input.value, -5.0..60.0); state.copy(waterTemp = input.value) }
                is HelmInput.Voltage -> { checkNumber(input.value, 0.0..60.0); when (input.bus) { ElectricalBus.STARTER_PORT -> state.copy(portVoltage = input.value); ElectricalBus.STARTER_STARBOARD -> state.copy(starboardVoltage = input.value); ElectricalBus.HOUSE -> state.copy(houseVoltage = input.value) } }
                is HelmInput.Fuel -> {
                    checkNumber(input.value, 0.0..100.0)
                    val changed = if (input.tank == FuelTank.PORT) state.copy(portFuel = input.value) else state.copy(starboardFuel = input.value)
                    // Fixture-only volume scale. No vessel tank capacity has been established.
                    changed.copy(fuelRemainingLiters = (changed.portFuel + changed.starboardFuel) / 136.0 * 420.0)
                }
                is HelmInput.TimeTick -> { require(input.seconds in 0..86_400); state.copy(time = state.time.plusSeconds(input.seconds), timeTick = Math.addExact(state.timeTick, input.seconds)) }
                is HelmInput.SetTime -> state.copy(time = input.time)
                is HelmInput.SetTheme -> state.copy(theme = input.theme)
                is HelmInput.SetRunning -> state.copy(running = input.running)
                is HelmInput.SetQuality -> state.copy(quality = state.quality.copy(status = input.status))
                is HelmInput.SetTelemetryAge -> { require(input.ageMs >= 0); state.copy(telemetryAgeMs = input.ageMs, quality = state.quality.copy(status = when { input.ageMs > 30_000 -> TelemetryStatus.LOST; input.ageMs > 5_000 -> TelemetryStatus.STALE; else -> TelemetryStatus.LIVE })) }
            }
            state = next
            HelmUpdate(true, state)
        } catch (e: IllegalArgumentException) { HelmUpdate(false, state, "Invalid simulation input: ${e.message ?: "out of range"}") }
          catch (e: ArithmeticException) { HelmUpdate(false, state, "Simulation clock overflow") }
    }
    private fun checkNumber(value: Double, range: ClosedFloatingPointRange<Double>) { require(value.isFinite() && value in range) { "Expected $range" } }
    private fun validate(s: HelmState) {
        listOf(s.portRpm, s.starboardRpm).forEach { checkNumber(it, 0.0..7_000.0) }
        listOf(s.portTrim, s.starboardTrim, s.portFuel, s.starboardFuel).forEach { checkNumber(it, 0.0..100.0) }
        listOf(s.portVoltage, s.starboardVoltage, s.houseVoltage).forEach { checkNumber(it, 0.0..60.0) }
        checkNumber(s.heading, 0.0..359.999999); checkNumber(s.speed, 0.0..200.0); s.depth?.let { checkNumber(it, 0.0..12_000.0) }; s.waterTemp?.let { checkNumber(it, -5.0..60.0) }; require(s.timeTick >= 0)
        listOf(s.portCoolantC, s.starboardCoolantC).filterNotNull().forEach { checkNumber(it, -50.0..200.0) }
        listOf(s.portOilBar, s.starboardOilBar).filterNotNull().forEach { checkNumber(it, 0.0..100.0) }
        listOf(s.portFuelFlowLph, s.starboardFuelFlowLph, s.totalFuelFlowLph, s.fuelRemainingLiters).filterNotNull().forEach { require(it.isFinite() && it >= 0) }
        s.latitudeDegrees?.let { checkNumber(it, -90.0..90.0) }; s.longitudeDegrees?.let { checkNumber(it, -180.0..180.0) }
        s.courseOverGroundDegrees?.let { checkNumber(it, 0.0..359.999999) }
    }
}

/** Read-only vessel telemetry. No engine, steering, or autopilot command exists here. */
interface HelmSource {
    val freshnessPolicy: FreshnessPolicy get() = QualityEvaluator.SHARED
    val identity: SourceIdentity
    val snapshots: SourceFeed<HelmState>
    val demo: HelmDemoControls? get() = null
    /**
     * The instrument and dashboard need the source's full display cadence. Other HELM pages
     * do not consume live vessel values, so a network source may use its bounded background
     * cadence there. Implementations without a variable network cadence deliberately do nothing.
     */
    fun setUiDemand(instrumentCadence: Boolean) = Unit
    fun advance(elapsedMillis: Long)
    fun close()
}

/** This capability is present only on explicit local simulation adapters. */
interface HelmDemoControls {
    fun update(input: HelmInput): HelmUpdate
    fun reset(): HelmState
}

class MockHelmSource(
    override val identity: SourceIdentity,
    initial: HelmState = HelmState(),
    private val nowEpochMs: () -> Long = { 0L },
) : HelmSource {
    private val repository = DemoHelmRepository(initial)
    private var remainderMillis = 0L
    private var simulationRun = 0L
    private val feed = MutableSourceFeed(stamped(repository.state))
    private val lock = Any()
    private var closed = false
    override val snapshots: SourceFeed<HelmState> get() = feed
    override val demo: HelmDemoControls = object : HelmDemoControls {
        override fun update(input: HelmInput): HelmUpdate = synchronized(lock) {
            if (closed) return@synchronized HelmUpdate(false, feed.value, "HELM source is closed")
            val result = repository.update(input)
            if (result.accepted) feed.emit(stamped(result.state))
            result.copy(state = feed.value)
        }
        override fun reset(): HelmState = synchronized(lock) {
            if (!closed) {
                remainderMillis = 0
                simulationRun++
                feed.emit(stamped(repository.reset()))
            }
            feed.value
        }
    }

    init { require(identity.mode == SourceMode.DEMO) { "Mock HELM requires DEMO identity" } }

    override fun advance(elapsedMillis: Long) = synchronized(lock) {
        if (closed) return@synchronized
        require(elapsedMillis in 0..86_400_000) { "Advance must be between zero and one day" }
        if (!repository.state.running) {
            return@synchronized
        }
        val elapsed = remainderMillis + elapsedMillis
        val seconds = elapsed / 1_000
        remainderMillis = elapsed % 1_000
        if (elapsedMillis == 0L) return@synchronized
        if (seconds > 0L && !repository.tick(seconds).accepted) return@synchronized
        // The source generates fractional-second measurements; the visible vessel clock and
        // timeTick remain whole seconds. Rendering never predicts new telemetry on its own.
        val tick = repository.state.timeTick.toDouble() + remainderMillis / 1_000.0
        repository.update(HelmInput.Rpm(EngineSide.PORT, 3_400 + sin(tick / 5) * 140))
        repository.update(HelmInput.Rpm(EngineSide.STARBOARD, 3_420 + sin(tick / 5 + .15) * 140))
        repository.update(HelmInput.Heading((tick * .6) % 360))
        repository.update(HelmInput.Speed(21.8 + sin(tick / 7) * .6))
        feed.emit(stamped(repository.state))
        Unit
    }

    override fun close() = synchronized(lock) {
        closed = true
        feed.close()
    }

    private fun stamped(state: HelmState): HelmState = state.copy(
        quality = state.quality.copy(source = identity.label, timestampEpochMs = nowEpochMs(), demo = true),
        simulationRunId = "${identity.sessionId}:$simulationRun",
        simulationElapsedSeconds = state.timeTick.toDouble() + remainderMillis / 1_000.0,
        simulationTimeScale = 1.0,
    )
}

/** Placeholder adapter boundary. It performs no discovery, network requests, or writes. */
class UnavailablePiHelmSource(
    override val identity: SourceIdentity,
    theme: Theme = Theme.DAY,
) : HelmSource {
    private val feed = MutableSourceFeed(HelmState.unavailable(theme, identity.label))
    override val snapshots: SourceFeed<HelmState> get() = feed
    init { require(identity.mode == SourceMode.PI) { "Pi HELM requires PI identity" } }
    override fun advance(elapsedMillis: Long) = Unit
    override fun close() = feed.close()
}
