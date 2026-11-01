package com.no5.helm.engine

import java.io.File
import kotlinx.serialization.json.*

internal const val BOSTON_BUNDLE_ID = "noaa-boston-repo-2026-10-03/1"
internal const val BOSTON_BUNDLE_SHA256 = "9c9dcb4cebe049f832dba60ef69ea40b4cebc4515f58d6adfac305bd780795bc"
internal fun bundledBenchmarkPaths(dataDirectory: String) = BostonBenchmarkRecipe.cells.sorted().map {
    File(dataDirectory, "renderer-benchmark/boston-v1/$it.000").absolutePath
}
internal fun bundledBenchmarkDataset(paths: List<String>): JsonObject {
    val dataset = try { benchmarkDataset(paths) }
        catch (failure: Exception) { throw IllegalStateException("Mitgelieferte Boston-Testkarten fehlen oder sind beschädigt. App erneut öffnen oder die vollständige APK installieren.", failure) }
    check(dataset.getValue("contentSha256").jsonPrimitive.content == BOSTON_BUNDLE_SHA256) {
        "Boston-Testkarten stimmen nicht mit dem festen Testpaket überein. App erneut öffnen oder die vollständige APK installieren."
    }
    return buildJsonObject { dataset.forEach { (key, value) -> put(key, value) }; put("bundleId", BOSTON_BUNDLE_ID) }
}
