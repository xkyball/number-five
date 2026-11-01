package com.no5.gateway

import android.os.Build

/** Fixed device assignment, never a network-discovery preference. */
object GatewayEnvironment {
    const val LAB_HOST = "no5-marineos-lab.local"
    fun isEmulator(fingerprint: String, model: String, hardware: String, product: String): Boolean =
        hardware.lowercase() in setOf("goldfish", "ranchu") ||
            product.contains("sdk", true) && (model.contains("sdk", true) || fingerprint.startsWith("generic") || fingerprint.contains("emulator", true)) ||
            model.contains("emulator", true) && fingerprint.contains("emulator", true)
    val emulator: Boolean get() = isEmulator(Build.FINGERPRINT, Build.MODEL, Build.HARDWARE, Build.PRODUCT)
    fun accepts(origin: GatewayOrigin, emulator: Boolean = this.emulator): Boolean =
        if (emulator) origin.host == LAB_HOST else origin.host != LAB_HOST
    val label: String get() = if (emulator) "Virtueller Pi · Emulator" else "Echter Pi · Gerät"
}
