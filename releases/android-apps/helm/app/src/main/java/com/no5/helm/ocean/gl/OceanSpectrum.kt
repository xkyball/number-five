package com.no5.helm.ocean.gl

import java.util.Random
import kotlin.math.*

/** Deterministic decorative Phillips spectrum. No claim of measured sea state. */
internal object OceanSpectrum {
    const val SIZE = 128
    const val LENGTH = 32f
    fun initial(): FloatArray {
        val data = FloatArray(SIZE * SIZE * 4)
        val random = Random(0x4e4f354c)
        var energy = 0.0
        for (z in 0 until SIZE) for (x in 0 until SIZE) {
            if ((x == 0 && z == 0) || x == SIZE/2 || z == SIZE/2) continue
            val kx = 2 * PI * (if (x < SIZE/2) x else x-SIZE) / LENGTH
            val kz = 2 * PI * (if (z < SIZE/2) z else z-SIZE) / LENGTH
            val k2 = kx*kx+kz*kz
            // Broad directional spread; suppress waves longer than the decorative fetch.
            val alignment = (.8*kx+.6*kz) / sqrt(k2)
            val density = exp(-1.0/(k2*16)) / (k2*k2) *
                (.18+.82*alignment*alignment) * exp(-k2*.025)
            val a = random.nextGaussian()*sqrt(density*.5)
            val b = random.nextGaussian()*sqrt(density*.5)
            val i = (z*SIZE+x)*4
            data[i] = a.toFloat(); data[i+1] = b.toFloat()
            energy += a*a+b*b
        }
        // Unnormalised inverse FFT; expected RMS height 14 cm at neutral amplitude.
        val scale = (.14 / sqrt(2*energy)).toFloat()
        for (i in data.indices) data[i] *= scale
        return data
    }
}
