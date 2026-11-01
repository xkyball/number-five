package com.no5.helm.chart

import kotlinx.coroutines.flow.StateFlow

/** Public inputs must be lawfully available plaintext, not decrypted licensed charts. */
const val PI_CACHE_PROVIDER = "no5-pi-map-cache-v1"
const val BACKGROUND_CACHE_PROVIDER = "natural-earth-background-v1"

sealed interface ChartSource {
    data class PublicFiles(val paths: List<String>) : ChartSource
    /** Private library files from a verified Pi export. Provenance is not a new Android licence. */
    data class PiCacheFiles(val paths: List<String>, val licenseHost: String, val exportedUtc: String,
        val backgroundPaths: List<String> = emptyList()) : ChartSource
    /** Opaque package location; never routed into the public on-disk SENC cache. */
    data class OChartsPackage(val location: String) : ChartSource
}

enum class LicensedProviderLifecycle { BLOCKED, CLOSED }
enum class HelperInstallation { NOT_INSTALLED }
enum class LicensedChartBlocker {
    HELPER_DISTRIBUTION_TERMS_PENDING,
    APP_IDENTITY_APPROVAL_PENDING,
    HELPER_NOT_INSTALLED,
    PROTECTED_STREAM_ADAPTER_NOT_IMPLEMENTED,
    LICENSED_DEVICE_TEST_BLOCKED,
}

data class LicensedProviderStatus(
    val lifecycle: LicensedProviderLifecycle = LicensedProviderLifecycle.BLOCKED,
    val helperInstallation: HelperInstallation = HelperInstallation.NOT_INSTALLED,
    val blockers: List<LicensedChartBlocker> = LicensedChartBlocker.entries.toList(),
) {
    fun diagnostics(): String = "o-charts: $lifecycle\nHelper: $helperInstallation\n" +
        blockers.joinToString("\n") { it.name }
}

/** Preparation boundary only. No activation, helper IPC or protected stream contract is assumed. */
interface LicensedChartProvider : AutoCloseable {
    val status: StateFlow<LicensedProviderStatus>
    suspend fun open(id: String, source: ChartSource.OChartsPackage): EngineResult
}
