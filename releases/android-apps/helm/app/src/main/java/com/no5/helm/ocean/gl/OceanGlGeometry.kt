package com.no5.helm.ocean.gl

import kotlin.math.*

/** Immutable world-space meshes, shared by the normal and mirrored camera. No camera translation. */
internal data class OceanMesh(val vertices: FloatArray, val indices: IntArray, val stride: Int = 2)
internal object OceanGlGeometry {
    const val WIDTH = 1200f
    const val HEIGHT = 704f
    const val LEFT = 400f
    const val FOV = 1.35f
    fun water(low: Boolean = false) = rings(if (low) 48 else 80, if (low) 128 else 256) { r ->
        if (r == 0f) 0f else .6f * (exp(r * ln(10001f)) - 1f)
    }
    fun terrain(low: Boolean = false): OceanMesh = OceanMassifs.mesh(low)

    private fun rings(rows: Int, columns: Int, radius: (Float) -> Float): OceanMesh {
        val vertices = FloatArray((rows + 1) * (columns + 1) * 2)
        for (y in 0..rows) for (x in 0..columns) {
            // Duplicate seam exactly, rather than sin(2PI) rounding, for a closed 360-degree world.
            val a = (x % columns).toDouble() / columns * 2 * PI
            val r = radius(y.toFloat() / rows)
            val i = (y * (columns + 1) + x) * 2
            vertices[i] = (sin(a) * r).toFloat(); vertices[i + 1] = (cos(a) * r).toFloat()
        }
        val indices = IntArray(rows * columns * 6)
        var i = 0
        for (y in 0 until rows) for (x in 0 until columns) {
            val a = y * (columns + 1) + x; val b = a + columns + 1
            indices[i++] = a; indices[i++] = b; indices[i++] = a + 1
            indices[i++] = a + 1; indices[i++] = b; indices[i++] = b + 1
        }
        return OceanMesh(vertices, indices)
    }
}
