package com.no5.helm.gpu
/** Separate fixture CMake exports only; absent from the production release. */
internal object SonarGpuNativeFaultProof {
    init { SonarGpuNative.resources() }
    external fun loseOwnContext(handle:Long)
    external fun fullColorRepaint(handle:Long,enabled:Boolean)
    external fun rasterCull(handle:Long,enabled:Boolean)
    external fun rasterPrimitiveCounts(handle:Long):LongArray
}
