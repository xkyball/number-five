package com.no5.domain

import org.HdrHistogram.IntCountsHistogram
import kotlin.math.*

enum class SonarImageMode { AUTO, MANUAL }
enum class SonarImageField { GAIN, GAMMA, THRESHOLD }
enum class SonarImageAutoState { WARMING, ACTIVE, WAITING, INCOMPATIBLE }
data class SonarImageValues(val gainDb: Double, val gamma: Double, val threshold: Double)

/** Single-owner, bounded source-time statistics. No samples or per-panel estimators are retained. */
class SonarImageAuto {
    private val buckets = Array(301) { IntCountsHistogram(1, 65535, 2) }
    private val seconds = LongArray(301) { Long.MIN_VALUE }
    private val total = IntCountsHistogram(1, 65535, 2)
    private var firstTime = Long.MIN_VALUE
    private var lastTime = Long.MIN_VALUE
    private var lastValidTime = Long.MIN_VALUE
    private var lastEvaluation = Long.MIN_VALUE
    private var lastExpiration = Long.MIN_VALUE
    private var validPings = 0
    private var lastValues: SonarImageValues? = null
    var evaluations: Long = 0; private set
    val estimatedBytes: Long get() = buckets.sumOf { it.estimatedFootprintInBytes.toLong() } + total.estimatedFootprintInBytes + seconds.size * 8L
    var state = SonarImageAutoState.WAITING; private set
    fun reset() {
        buckets.forEach { it.reset() }; seconds.fill(Long.MIN_VALUE); total.reset()
        firstTime = Long.MIN_VALUE; lastTime = Long.MIN_VALUE; lastValidTime = Long.MIN_VALUE; lastEvaluation = Long.MIN_VALUE
        lastExpiration = Long.MIN_VALUE; validPings = 0; lastValues = null; state = SonarImageAutoState.WAITING
    }
    fun reseed(values:SonarImageValues){lastValues=values}
    fun waiting(compatible: Boolean) { state = if (compatible) SonarImageAutoState.WAITING else SonarImageAutoState.INCOMPATIBLE }
    fun observe(frame: SonarFrame, fullScale: Int?, atEpochMs: Long, current: SonarImageValues,
                gainIsAuto: Boolean = true, historySeconds: Double = 120.0): SonarImageValues? {
        if (fullScale == null || fullScale !in 1..65535) { reset(); waiting(false); return null }
        if (lastTime != Long.MIN_VALUE && (atEpochMs < lastTime || atEpochMs - lastTime > 2000) || lastValidTime != Long.MIN_VALUE && atEpochMs - lastValidTime > 2000) reset()
        lastTime = atEpochMs
        val second = Math.floorDiv(atEpochMs, 1000L)
        val horizon = historySeconds.coerceIn(120.0, 300.0).toLong()
        // At most one bounded 301-bucket expiration scan per source second.
        if (lastExpiration != second) {
            lastExpiration = second
            for (i in buckets.indices) if (seconds[i] != Long.MIN_VALUE && seconds[i] < second - horizon + 1) {
                total.subtract(buckets[i]); buckets[i].reset(); seconds[i] = Long.MIN_VALUE
            }
        }
        var valid = 0; var clipped = 0
        for (bin in 8 until SONAR_BINS step 4) if (frame.sample(bin) >= fullScale) clipped++ else valid++
        if (valid < 128 || clipped > 64) { waiting(true); return null }
        lastValidTime = atEpochMs
        val index = Math.floorMod(second, 301L).toInt()
        if (seconds[index] != second) {
            if (seconds[index] != Long.MIN_VALUE) total.subtract(buckets[index])
            buckets[index].reset(); seconds[index] = second
        }
        val bucket = buckets[index]
        for (bin in 8 until SONAR_BINS step 4) {
            val raw = frame.sample(bin)
            if (raw < fullScale) {
                val normalized = ((raw.toLong() * 65535 + fullScale / 2) / fullScale).coerceIn(0, 65535)
                bucket.recordValue(normalized); total.recordValue(normalized)
            }
        }
        if (firstTime == Long.MIN_VALUE) firstTime = atEpochMs
        validPings++
        if (validPings < 10 || atEpochMs - firstTime < 1000) { state = SonarImageAutoState.WARMING; return null }
        if (lastEvaluation == second) return null
        val noise = total.getValueAtPercentile(25.0) / 65535.0
        val echo = total.getValueAtPercentile(98.0) / 65535.0
        if (echo <= 1e-8) { waiting(true); return null }
        val targetGain = (20 * log10(.82 / echo)).coerceIn(-24.0, 36.0)
        // Offset is deliberately absent: it is operator intent, never a feedback input.
        val multiplier = 10.0.pow((if (gainIsAuto) targetGain else current.gainDb) / 20)
        val targetThreshold = (noise * multiplier * .8).coerceIn(0.0, .65)
        val contrast = ((echo - noise) / echo).coerceIn(0.0, 1.0)
        val targetGamma = (1.0 + .5 * (1.0 - contrast)).coerceIn(.2, 3.0)
        val prior = lastValues ?: current
        val dt = if (lastEvaluation == Long.MIN_VALUE) 1.0 else (second - lastEvaluation).coerceIn(0, 2).toDouble()
        val alpha = 1 - exp(-dt / 30.0)
        fun approach(old: Double, target: Double, rate: Double) = old + ((target - old) * alpha).coerceIn(-rate * dt, rate * dt)
        val values = SonarImageValues(if (gainIsAuto) approach(prior.gainDb, targetGain, .25) else current.gainDb,
            approach(prior.gamma, targetGamma, .01), approach(prior.threshold, targetThreshold, .001))
        lastValues = values; lastEvaluation = second; evaluations++; state = SonarImageAutoState.ACTIVE
        return values
    }
}

/** Stored per-column base transfer; the explicit operator offset is applied at presentation time. */
fun SonarDisplay.imageValues() = SonarImageValues(gainDb, gamma, threshold)
fun SonarImageColumns.imageValuesAt(age: Int, display: SonarDisplay): SonarImageValues {
    val stored = imageMappingAt(age) ?: display.imageValues()
    return SonarImageValues(
        if (display.gainMode == SonarImageMode.AUTO) (stored.gainDb + display.autoGainOffsetDb).coerceIn(-24.0, 36.0) else display.gainDb,
        if (display.gammaMode == SonarImageMode.AUTO) stored.gamma else display.gamma,
        if (display.thresholdMode == SonarImageMode.AUTO) stored.threshold else display.threshold)
}
fun SonarImageValues.amplitude(linear: Double): Double =
    ((linear * 10.0.pow(gainDb / 20) - threshold) / (1 - threshold)).coerceIn(0.0, 1.0).pow(1 / gamma)
