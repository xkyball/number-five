package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Deliberately unavailable until the vendor contract and real device adapter exist. */
internal class OChartsPreparationProvider : LicensedChartProvider {
    private val mutable = MutableStateFlow(LicensedProviderStatus())
    override val status = mutable.asStateFlow()

    override suspend fun open(id: String, source: ChartSource.OChartsPackage) =
        EngineResult(false, status.value.diagnostics())

    override fun close() {
        mutable.value = mutable.value.copy(lifecycle = LicensedProviderLifecycle.CLOSED)
    }
}

/** Reject the entire request before touching native state or the public disk cache. */
internal class ChartSourceLoader(
    private val licensed: LicensedChartProvider,
    private val loadPiCache: suspend (String, List<String>) -> EngineResult = { _, _ -> EngineResult(false, "Pi cache adapter is unavailable") },
    private val loadComposite: suspend (String, ChartSource.PiCacheFiles) -> EngineResult = { _, _ -> EngineResult(false, "Combined cache adapter is unavailable") },
    private val loadPublic: suspend (String, List<String>) -> EngineResult,
) {
    suspend fun load(id: String, source: ChartSource): EngineResult = when (source) {
        is ChartSource.OChartsPackage -> licensed.open(id, source)
        is ChartSource.PiCacheFiles -> {
            val paths = source.paths.toList()
            if (paths.isEmpty() || paths.any { it.isBlank() || '\u0000' in it || !it.endsWith(".senc") } ||
                !source.licenseHost.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) || source.exportedUtc.isBlank() ||
                source.backgroundPaths.distinct().size != source.backgroundPaths.size || !paths.containsAll(source.backgroundPaths))
                EngineResult(false, "Pi cache request is invalid")
            else if (source.backgroundPaths.isNotEmpty()) loadComposite(id, source)
            else loadPiCache(id, paths)
        }
        is ChartSource.PublicFiles -> {
            val paths = source.paths.toList()
            when {
                paths.isEmpty() || paths.any { it.isBlank() || '\u0000' in it } ->
                    EngineResult(false, "Public chart request is empty or invalid")
                paths.any { it.substringAfterLast('.').lowercase(Locale.ROOT) in protectedExtensions } ->
                    EngineResult(false, "Licensed chart input cannot use the public S57 cache.\n" + licensed.status.value.diagnostics())
                // Match the native plaintext reader exactly; suffix checks are not a license check.
                paths.any { it.substringAfterLast('.') !in publicExtensions } ->
                    EngineResult(false, "Public input must be .000, .senc or .S57; licensed packages require their separate provider")
                else -> loadPublic(id, paths)
            }
        }
    }

    private companion object {
        val publicExtensions = setOf("000", "senc", "S57")
        val protectedExtensions = setOf("oesenc", "oesu", "oeusenc", "oernc")
    }
}
