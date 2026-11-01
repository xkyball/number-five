package com.no5.helm

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.ChartEngine
import com.no5.helm.client.HelmClient
import com.no5.helm.library.ChartLibrary
import com.no5.helm.library.ChartSelection
import com.no5.helm.library.ImportActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit user-requested persistent import. Requires both opt-in and a staged
 * private ZIP; never bundles charts, clears app data or restores a deleted package. */
class PermanentPiImportEmulatorTest {
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 120_000
        while (!condition()) {
            assertTrue(message, SystemClock.uptimeMillis() < end)
            SystemClock.sleep(50)
        }
    }
    @Test fun importAndLeaveSouthAfricaActiveInProductionApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires an explicit persistent-import request", arguments.getString("permanentPiImport") == "true")
        assertTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        assertEquals("com.no5.map", context.packageName)
        val input = File(requireNotNull(arguments.getString("piImportPath"))).canonicalFile
        require(input.isFile && input.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val controllerField = MainActivity::class.java.getDeclaredField("charts").apply { isAccessible = true }
        await("Production controller must prepare") { controllerField.get(activity) != null }
        val controller = controllerField.get(activity) as PersistentChartController
        val engine = MainActivity::class.java.getDeclaredField("engine").apply { isAccessible = true }.get(activity) as ChartEngine
        await("Existing operation must finish") { !controller.state.value.busy }
        val library = ChartLibrary(File(context.filesDir, "chart-library-v1"))
        val before = runBlocking { library.snapshot() }
        val existingId = arguments.getString("existingPackageId")
        if (existingId == null) {
            instrumentation.runOnMainSync { controller.importPackage(Uri.fromFile(input)) }
            await("Private ZIP import must complete") { !controller.state.value.busy }
            assertEquals(ImportActivity.COMPLETED, controller.state.value.importProgress?.activity)
        } else {
            // A repeated verification must not duplicate the user's installed package.
            assertTrue(before.packages.any { it.id == existingId && it.piCache != null && it.cells.size == 56 })
        }
        val imported = runBlocking { library.snapshot() }
        assertTrue(before.packages.all { it in imported.packages })
        val pack = imported.packages.single { it.piCache != null && it.cells.size == 56 }
        assertTrue(pack.cells.all { it.catalogMetadata != null })
        val selection = ChartSelection(pack.id, pack.cells.map { it.name })
        instrumentation.runOnMainSync { controller.activate(selection) }
        await("All private cells must be durably activated") { !controller.state.value.busy && library.snapshot().active == selection && engine.status.value.residentCameraReady }
        engine.setViewport(engine.status.value.viewport.copy(latitude = -33.88803983852352,
            longitude = 18.416841792709647, metresToPixels = .06322578031788625, rotationRadians = 0.0))
        await("Table Bay detail must be rendered") {
            val status = engine.status.value
            val resident = runBlocking { engine.readResidency() }.status
            resident?.complete == true && resident.missing == 0 && resident.loading == 0 &&
                status.confirmedFrame != null && status.loadedChartCells.contains("OC-27-050004") &&
                status.viewport.metresToPixels > .06
        }
        val status = engine.status.value
        val output = File(context.filesDir, "chart-reload-permanent-evidence").apply { mkdirs() }
        File(output, "result.json").writeText(JSONObject().put("success", true).put("packageId", pack.id)
            .put("privateCells", pack.cells.size).put("active", JSONArray(selection.cells))
            .put("loaded", JSONArray(status.loadedChartCells)).put("sourceGeneration", status.chartSourceGeneration)
            .put("viewport", JSONObject().put("latitude", status.viewport.latitude).put("longitude", status.viewport.longitude)
                .put("ppm", status.viewport.metresToPixels)).put("previousPackagesPreserved", true)
            .put("persistent", true).toString(2))
        println("PERMANENT_PI_IMPORT ${pack.id} ${pack.cells.size} cells")
        // The live gateway client can own TLS sockets. Close them off the UI
        // thread before the instrumentation runner destroys MainActivity.
        val client = MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.get(activity) as HelmClient
        runBlocking(Dispatchers.IO) { client.close() }
        // Android instrumentation stops the process afterwards; durable selection stays.
    }
}
