package com.no5.helm.library

import com.no5.helm.chart.*
import java.io.File
import java.util.Collections

/** Binds an immutable P1/P2 catalog to paths in one existing package data directory.
 * No activation, hash IO, geometry loading, index rebuild or persistence occurs here. */
object ChartResidentAdapter {
    fun catalog(planning: ChartPlanningCatalog, pack: ChartPackage, dataDirectory: File,
        budget: ChartResidentBudget): ChartResidentCatalog {
        require(ChartPlanningCatalog.fromPackage(pack, planning.cells.map { it.key.name }.toSet()).generation == planning.generation)
        val root = dataDirectory.toPath().toAbsolutePath().normalize()
        val sourceByName = pack.cells.associateBy { it.name }
        require(sourceByName.size == pack.cells.size)
        val sources = planning.cells.map { planned ->
            val source = sourceByName.getValue(planned.key.name)
            val background = source.provider == BACKGROUND_CACHE_PROVIDER
            val provenance = pack.piCache.takeUnless { background }
            val provider = ChartMetadataContract.provider(provenance, source.provider)
            require(!source.cancelled && (background || provenance != null || S57Profiles.supportedProvider(provider)) &&
                planned.key.provider == provider && ChartMetadataContract.sourceKey(source, provenance) == planned.key.sourceKey)
            val ordered = if (provenance != null || background) PiCachePackage.residentFiles(source) else
                source.files.entries.sortedBy { File(it.key).extension.toIntOrNull() ?: Int.MAX_VALUE }
            require(background || provenance != null || ordered.size == source.update + 1)
            val files = ordered.mapIndexed { index, entry ->
                val relative = File(entry.key).toPath()
                val path = root.resolve(relative).normalize()
                require(!relative.isAbsolute && path.startsWith(root) &&
                    (background || provenance != null || File(entry.key).name == "%s.%03d".format(source.name, index)) &&
                    entry.value.matches(Regex("[a-f0-9]{64}")))
                ChartResidentFile(path.toString(), entry.value)
            }
            require(ordered.first().key == source.base && (background || provenance != null || files.map { File(it.path).parent }.distinct().size == 1))
            ChartResidentSource(planned.key.provider, source.name, planned.key.sourceKey, source.edition,
                source.update, planned.order, Collections.unmodifiableList(files), provenance?.product, provenance?.licenseHost)
        }
        return ChartResidentCatalog(planning.generation, Collections.unmodifiableList(sources), budget)
    }

    /** The caller must use the current planner tag and the renderer's actual camera.
     * P4 chooses when to submit/advance; P3 does not add an automatic load loop. */
    fun demand(plan: ChartViewportPlan): ChartResidentDemand {
        require(plan.tag.selectionPolicyVersion == 1)
        val camera = plan.camera
        return ChartResidentDemand(plan.tag.catalogGeneration, plan.tag.cameraRevision, plan.tag.cameraKey,
            plan.tag.selectionPolicyKey, ChartViewport(camera.latitude, camera.longitude, camera.pixelsPerMetre,
                camera.rotation, camera.width, camera.height),
            Collections.unmodifiableList(plan.required.map { it.sourceKey }),
            Collections.unmodifiableList(plan.prefetch.map { it.sourceKey }))
    }
}
