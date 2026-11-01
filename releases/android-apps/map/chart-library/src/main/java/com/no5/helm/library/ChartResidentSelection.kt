package com.no5.helm.library

import kotlinx.serialization.Serializable
import java.io.File

/** A release identifies immutable content; it never describes current geometry residency. */
@Serializable data class ChartReleasedCell(val name: String, val sourceKey: String)
@Serializable data class ChartReleasedSelection(val version: Int = 2, val packageId: String,
    val cells: List<ChartReleasedCell>) {
    fun selection() = ChartSelection(packageId, cells.map { it.name })
}

/** Metadata-only binding. Native admission verifies each source's actual bytes before ingest.
 * The library constructs this immutable snapshot under its IO lock; the app can use its
 * planning catalog and guarded data directory without paths(selection) or S57 byte scans. */
class ChartResidentSelection internal constructor(val selection: ChartSelection, val pack: ChartPackage,
    val planning: ChartPlanningCatalog, val dataDirectory: File, val release: ChartReleasedSelection)
