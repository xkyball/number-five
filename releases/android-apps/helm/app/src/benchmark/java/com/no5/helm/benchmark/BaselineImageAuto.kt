package com.no5.helm.benchmark
import com.no5.domain.*

import kotlin.math.*

/** One presentation-only controller. No depth detection or mutation of profile samples. */
class BaselineImageAuto {
    // Seven profile summaries reject a single clipped/impulsive ping; at 10 Hz a
    // lasting step replaces the majority within 400 ms. A 350 ms low-pass and
    // slew bounds then settle well inside the required three seconds.
    private val noiseWindow = DoubleArray(7)
    private val echoWindow = DoubleArray(7)
    private val scratch = DoubleArray(256)
    private val medianScratch = DoubleArray(7)
    private var head = 0
    private var count = 0
    private var lastTime: Long? = null
    private var lastValues = SonarImageValues(0.0, 1.0, 0.0)
    var state = SonarImageAutoState.WAITING; private set
    fun reset() { head = 0; count = 0; lastTime = null; state = SonarImageAutoState.WAITING }
    fun waiting(compatible: Boolean) { state = if (compatible) SonarImageAutoState.WAITING else SonarImageAutoState.INCOMPATIBLE }
    fun observe(frame: SonarFrame, fullScale: Int?, atEpochMs: Long, current: SonarImageValues, gainIsAuto: Boolean = true): SonarImageValues? {
        if (fullScale == null || fullScale !in 1..65535) { state = SonarImageAutoState.INCOMPATIBLE; return null }
        // Ignore the first eight bins and saturated ADC values. A bounded
        // subsample uses percentiles, rather than maxima or TX spikes.
        var n = 0
        var clipped = 0
        for (bin in 8 until SONAR_BINS step 4) {
            val raw = frame.sample(bin)
            if (raw >= fullScale) { clipped++; continue }
            scratch[n++] = raw / fullScale.toDouble()
        }
        if (n < 128 || clipped > 64) { state = SonarImageAutoState.WAITING; return null }
        java.util.Arrays.sort(scratch, 0, n)
        noiseWindow[head] = scratch[((n - 1) * .25).toInt()]
        echoWindow[head] = scratch[((n - 1) * .98).toInt()]
        head = (head + 1) % 7; count = min(7, count + 1)
        if (count < 5) { state = SonarImageAutoState.WARMING; return null }
        fun median(values: DoubleArray): Double {
            values.copyInto(medianScratch, 0, 0, count)
            java.util.Arrays.sort(medianScratch, 0, count)
            return medianScratch[count / 2]
        }
        val noise = median(noiseWindow)
        val echo = median(echoWindow)
        if (echo <= 1e-8) { state = SonarImageAutoState.WAITING; return null }
        val targetGain = (20 * log10(.82 / echo)).coerceIn(-24.0, 36.0)
        val multiplier = 10.0.pow((if (gainIsAuto) targetGain else current.gainDb) / 20)
        // Keep background visible below echoes; gamma is selected together
        // with gain/threshold from their common robust dynamic range.
        val targetThreshold = (noise * multiplier * .8).coerceIn(0.0, .65)
        val contrast = ((echo - noise) / echo).coerceIn(0.0, 1.0)
        val targetGamma = (1.0 + .5 * (1.0 - contrast)).coerceIn(.2, 3.0)
        val dt = lastTime?.let { (atEpochMs - it).coerceIn(0, 500).toDouble() / 1000 } ?: .1
        val prior = if (lastTime == null) current else lastValues
        val alpha = 1 - exp(-dt / .35)
        fun approach(old: Double, target: Double, perSecond: Double) = old +
            ((target - old) * alpha).coerceIn(-perSecond * dt, perSecond * dt)
        lastValues = SonarImageValues(if (gainIsAuto) approach(prior.gainDb, targetGain, 24.0).coerceIn(-24.0, 36.0) else current.gainDb,
            approach(prior.gamma, targetGamma, 2.0).coerceIn(.2, 3.0),
            approach(prior.threshold, targetThreshold, .8).coerceIn(0.0, .95))
        lastTime = atEpochMs; state = SonarImageAutoState.ACTIVE
        return lastValues
    }
}
