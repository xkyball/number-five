package com.no5.helm.ocean

import kotlin.math.*

enum class BackgroundMode(val label: String) { IMAGE("Hintergrundbild"), ANIMATION("Animation"), NONE("Kein Hintergrund"), VIDEO("360° Video") }
enum class OceanQuality { HIGH, LOW, STATIC }
enum class OceanInputQuality { LIVE, STALE, LOST, INVALID, UNAVAILABLE }
/** Already evaluated by the independent provider freshness policy. No source timestamps are rewritten. */
data class OceanInputs(
    val sessionId: String,
    val headingDegrees: Double? = null,
    val headingQuality: OceanInputQuality = OceanInputQuality.UNAVAILABLE,
    val windSpeedKn: Double? = null,
    val weatherQuality: OceanInputQuality = OceanInputQuality.UNAVAILABLE,
    val windCapability: Boolean = false,
    val staleWeatherUsable: Boolean = false,
    val headingEpoch: Pair<String?, String?> = null to null,
    val windDirectionFromDegrees: Double? = null,
    val cloudCoverPercent: Double? = null,
    val visibilityMeters: Double? = null,
    val latitudeDegrees: Double? = null,
    val longitudeDegrees: Double? = null,
    val utcEpochMs: Long? = null,
)
data class OceanFrame(val timeSeconds: Float = 0f, val headingRadians: Float = 0f,
    val waveHeight: Float = .18f, val waveSpeed: Float = .35f,
    val quality: OceanQuality = OceanQuality.HIGH, val preparationRevision: Int = 0,
    val environment: OceanEnvironment = OceanEnvironment())
data class OceanVisibility(val helmVisible: Boolean, val resumed: Boolean, val night: Boolean,
    val reducedMotion: Boolean, val mode: BackgroundMode) {
    fun runs(quality: OceanQuality, shaderAvailable: Boolean) =
        helmVisible && resumed && !night && !reducedMotion && mode == BackgroundMode.ANIMATION &&
            quality != OceanQuality.STATIC && shaderAvailable
}

/** Local monotonic clock only. Neither UTC nor simulation elapsed/timeScale is accepted here. */
class OceanClock {
    private var lastNanos: Long? = null
    private var seconds = 0.0
    fun pause() { lastNanos = null }
    fun reset() { pause(); seconds = 0.0 }
    fun tick(nowNanos: Long, waveSpeed: Float): Pair<Float, Float> {
        val previous = lastNanos
        lastNanos = nowNanos
        val dt = if (previous == null || nowNanos < previous) 0.0 else
            ((nowNanos - previous).toDouble() / 1e9).coerceIn(0.0, .05)
        // One rate owner; shader temporal frequencies .25/.50/.75 preserve phase at 8 pi rebasing.
        val speed = if (waveSpeed.isFinite()) waveSpeed.coerceIn(0f, 1f) else .35f
        seconds = (seconds + dt * (.4 + .6 * speed)) % (8.0 * PI)
        return seconds.toFloat() to dt.toFloat()
    }
}

/** One mapper per source session. Panorama function/seed never depends on session, location or time. */
class OceanPresentation(private val sessionId: String) {
    private var headingEpoch: Pair<String?, String?> = null to null
    private var heading: Double? = null
    private var targetHeading: Double? = null
    private var animationActive = false
    private val environment = OceanEnvironmentTransition()
    private var acceptedRoughness = .18f
    private var roughness = .18f
    fun accept(input: OceanInputs): Boolean {
        if (input.sessionId != sessionId) return false
        environment.accept(input)
        if (headingEpoch != input.headingEpoch) {
            headingEpoch = input.headingEpoch
            heading = null
            targetHeading = null
        }
        if (input.headingQuality == OceanInputQuality.LIVE && input.headingDegrees?.isFinite() == true && input.headingDegrees in 0.0..360.0) {
            targetHeading = normalizeDegrees(input.headingDegrees)
            if (heading == null) heading = targetHeading
        } else targetHeading = heading // STALE freezes the actual displayed orientation.
        val wind = input.windSpeedKn
        val usable = input.windCapability && wind != null && wind.isFinite() && wind in 0.0..250.0
        if (usable && input.weatherQuality == OceanInputQuality.LIVE) {
            // Decorative heuristic only, never metres or an asserted sea-state measurement.
            val candidate = (.08 + wind!! / 60.0).coerceIn(.08, .65).toFloat()
            if (abs(candidate - acceptedRoughness) >= .035f) acceptedRoughness = candidate
        } else {
            acceptedRoughness = .18f
        }
        return true
    }
    /** Reacquire on a visibility activation, never on an ordinary frame or HIGH/LOW change. */
    fun setAnimationActive(active: Boolean): Boolean {
        val activated = active && !animationActive
        animationActive = active
        if (activated) heading = targetHeading ?: heading
        return activated
    }
    fun frame(timeSeconds: Float, elapsedSeconds: Float, quality: OceanQuality): OceanFrame {
        val dt = if (elapsedSeconds.isFinite()) elapsedSeconds.coerceIn(0f, .05f) else 0f
        val h = heading; val t = targetHeading
        if (h != null && t != null) heading = normalizeDegrees(h + shortestDelta(h, t) * (1 - exp(-dt * 7.0)))
        roughness += (acceptedRoughness - roughness) * (1 - exp(-dt / 2.0)).toFloat()
        val time = if (timeSeconds.isFinite()) timeSeconds.coerceIn(0f, (8 * PI).toFloat()) else 0f
        return OceanFrame(time, Math.toRadians(heading ?: 0.0).toFloat(), roughness,
            (.20f+roughness*.8f).coerceIn(0f,1f), quality, environment=environment.frame(dt))
    }
    companion object {
        fun normalizeDegrees(value: Double) = ((value % 360.0) + 360.0) % 360.0
        fun shortestDelta(from: Double, to: Double) = (((to - from + 180.0) % 360.0 + 360.0) % 360.0) - 180.0
        fun landmarkScreenAngle(landmark: Double, heading: Double) = shortestDelta(heading, landmark)
    }
}

/** Feed measured frame pressure, not assumed shader cost. Instrument budget always wins. */
class OceanQualityGovernor {
    var quality = OceanQuality.HIGH; private set
    private var pressureSeconds = 0f
    private var healthySeconds = 0f
    fun pause() { pressureSeconds = 0f; healthySeconds = 0f }
    fun observe(elapsedSeconds: Float, overBudget: Boolean, severeThermal: Boolean = false,
        lowMemory: Boolean = false, severePressure: Boolean = false, healthy: Boolean = !overBudget) {
        if (!elapsedSeconds.isFinite() || elapsedSeconds <= 0) return
        val dt = elapsedSeconds.coerceAtMost(1f)
        if (severeThermal || lowMemory) { quality = OceanQuality.STATIC; pressureSeconds = 0f; healthySeconds = 0f; return }
        // Moderate sustained misses justify fewer details/updates, never a static photograph.
        // Only serious continued misses after LOW justify disabling the animation entirely.
        if (overBudget && (quality == OceanQuality.HIGH || severePressure)) {
            healthySeconds = 0f; pressureSeconds += dt
            val required = if (quality == OceanQuality.HIGH) 6f else 10f
            if (pressureSeconds >= required) { quality = when (quality) { OceanQuality.HIGH -> OceanQuality.LOW; else -> OceanQuality.STATIC }; pressureSeconds = 0f }
        } else if (healthy) {
            pressureSeconds = 0f; healthySeconds += dt
            if (healthySeconds >= 30f) { quality = when (quality) { OceanQuality.STATIC -> OceanQuality.LOW; else -> OceanQuality.HIGH }; healthySeconds = 0f }
        } else pause()
    }
}
