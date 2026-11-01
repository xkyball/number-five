package com.no5.helm.library

import kotlinx.serialization.Serializable

/** A verified EDTN=0 receipt survives deletion of its original chart bytes. */
@Serializable data class ChartCancellationReceipt(
    val name: String, val edition: Int, val update: Int, val issueDate: String,
    val packageId: String, val sourceKey: String, val provider: String = "noaa-s57",
) {
    internal fun blocks(cell: ChartCell) = provider == cell.provider && name == cell.name && cell.edition <= edition
}

@Serializable internal data class ChartPayloadCleanup(val packageId: String, val paths: List<String>? = null)

/** One authoritative atomic file binds selection, status receipts and deferred physical cleanup.
 * The former active.json remains a preserved migration input, never a fallback after this exists. */
@Serializable internal data class ChartLifecycleState(
    val version: Int = 1, val revision: Long = 0, val selectionVersion: Int = 2,
    val active: ChartSelection? = null, val release: ChartReleasedSelection? = null,
    val cancellations: List<ChartCancellationReceipt> = emptyList(),
    val removedPackages: List<String> = emptyList(), val cleanup: List<ChartPayloadCleanup> = emptyList(),
)

/** Prepared against exact source pins. This contains no permission to delete live chart files. */
class ChartLibraryMutation internal constructor(
    internal val expectedRevision: Long, internal val expectedActive: ChartSelection?,
    internal val expectedRelease: ChartReleasedSelection?, internal val target: ChartLifecycleState,
    val description: String, val confirmedCancellation: Boolean = false,
) {
    val selection: ChartSelection? get() = target.active
    val release: ChartReleasedSelection? get() = target.release
    val revision: Long get() = target.revision
    val hasCleanup: Boolean get() = target.cleanup.isNotEmpty()
}

data class ChartCleanupResult(val complete: Boolean, val removedFiles: Int = 0, val message: String = "")
