package com.no5.helm.acceptance

import android.content.Intent
import android.os.Process
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.no5.helm.chart.ChartResidentBinding
import com.no5.helm.library.ChartLibrary
import com.no5.helm.library.ChartLibrarySnapshot
import com.no5.helm.library.ChartSelection
import com.no5.helm.library.ImportActivity
import com.no5.helm.library.ImportPhase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Separate APK/root, real Controller + native validation/resident owner + GLES Surface.
 * The ordinary suite observes and continues K1/K2. Only processKillRecipe requests an
 * external process kill. Compilation alone never proves any device acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ChartLifecycleAcceptanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<LifecycleAcceptanceActivity>
    private lateinit var activity: LifecycleAcceptanceActivity
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Before fun launchOwnActivity() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(LifecycleAcceptanceContext.APPLICATION_ID, target.packageName)
        val externalPhase = arguments.getString("lifecyclePhase")
        val runId = if (externalPhase == null) LifecycleAcceptanceContext.newRunId()
            else requireNotNull(arguments.getString("lifecycleRunId")) { "External phase needs the same explicitly pinned run UUID" }
        require(runId.matches(LifecycleAcceptanceContext.RUN_ID))
        scenario = ActivityScenario.launch(Intent(target, LifecycleAcceptanceActivity::class.java)
            .putExtra(LifecycleAcceptanceContext.RUN_EXTRA, runId))
        scenario.onActivity { activity = it }
        awaitPrepared()
        assertEquals(runId, activity.acceptanceContext.runId)
        assertEquals(LifecycleAcceptanceContext.APPLICATION_ID, activity.acceptanceContext.packageName)
        assertTrue(File(activity.acceptanceContext.runRoot, "ISOLATED_TEST_RUN.json").isFile)
    }

    @After fun closeOwnActivity() {
        if (!::scenario.isInitialized) return
        // Observation tests release their synthetic wait. Never auto-delete the evidence/run root.
        if (::activity.isInitialized && activity.prepared) activity.engine.barrier.value?.let { event ->
            scenario.onActivity { it.engine.continueAfterObservation(event.token) }
            compose.waitUntil(60_000) { !activity.controller.state.value.busy }
        }
        scenario.close()
    }

    @Test fun originalPartialUpdatePublishesNewPinsAndDeletesOnlyReleasedOldPackages() {
        val before = seedAndActivate()
        val oldId = before.active!!.packageId
        val oldDirectory = packageDirectory(oldId)
        val previousPose = activity.engine.status.value.viewport
        val previousPresentation = activity.engine.status.value.presentation
        val previousSource = binding()
        val ddBefore = sourceFileHashes(previousSource, "US5BOSDD")
        scenario.onActivity { it.updateNoaaFixture() }
        awaitIdle(); awaitCurrentFrame()
        val after = snapshot()
        assertNotEquals(oldId, after.active?.packageId)
        assertEquals(setOf("US5BOSCC", "US5BOSDD"), after.active?.cells?.toSet())
        assertEquals(2, after.selectionVersion)
        assertFalse(after.pendingCleanup)
        assertFalse("Old payload retained after a real release ACK", oldDirectory.exists())
        assertEquals(1, after.packages.size)
        val current = binding()
        assertUpdatedSources(current)
        assertEquals(ddBefore, sourceFileHashes(current, "US5BOSDD"))
        assertEquals(previousPose, activity.engine.status.value.viewport)
        assertEquals(previousPresentation, activity.engine.status.value.presentation)
        assertTrue(current.sourceGeneration > previousSource.sourceGeneration)
        evidence("partial-update-complete")
    }

    @Test fun missingUpdateIsRejectedWithoutChangingSelectionNativePinsOrExistingBytes() {
        seedAndActivate()
        val before = snapshot()
        val id = requireNotNull(before.active).packageId
        val durable = durableFiles()
        val bytes = packageFiles(id)
        val previousBinding = binding()
        scenario.onActivity { it.updateNoaaFixture(LifecycleFixture.GAP) }
        awaitIdle()
        assertEquals(ImportActivity.FAILED, activity.controller.state.value.importProgress?.activity)
        assertEquals(before.active, snapshot().active)
        assertEquals(before.activeRelease, snapshot().activeRelease)
        assertEquals(durable, durableFiles())
        assertEquals(bytes, packageFiles(id))
        assertEquals(previousBinding.activation.catalog, binding().activation.catalog)
        assertEquals(previousBinding.sourceGeneration, binding().sourceGeneration)
        assertEquals(1, snapshot().packages.size)
        evidence("negative-gap-preserved")
    }

    @Test fun partialAndLastDeactivationRetainPayloadAndExplicitEmptyStateSurvivesRecreation() {
        val before = seedAndActivate()
        val id = before.active!!.packageId
        val files = packageFiles(id)
        scenario.onActivity { it.controller.deactivate(ChartSelection(id, listOf("US5BOSCC"))) }
        awaitIdle(); awaitCurrentFrame()
        assertEquals(listOf("US5BOSDD"), snapshot().active?.cells)
        assertEquals(listOf("US5BOSDD"), binding().activation.catalog.sources.map { it.name })
        assertEquals(files, packageFiles(id))
        scenario.onActivity { it.controller.deactivate(ChartSelection(id, listOf("US5BOSDD"))) }
        awaitIdle()
        assertNull(snapshot().active)
        assertEquals(2, snapshot().selectionVersion)
        assertFalse(activity.engine.status.value.chartLoaded)
        assertTrue(ChartLibrary(activity.acceptanceContext.libraryRoot).hasPersistedSelection())
        assertEquals(files, packageFiles(id))
        val runId = activity.acceptanceContext.runId
        scenario.recreate(); scenario.onActivity { activity = it }; awaitPrepared()
        assertEquals(runId, activity.acceptanceContext.runId)
        assertNull(snapshot().active)
        assertFalse(activity.engine.status.value.chartLoaded)
        assertEquals(files, packageFiles(id))
        evidence("last-deactivation-recreated")
    }

    @Test fun inactiveWsvDeletionPreservesActiveNoaaAndActiveLastDeletionStaysEmpty() {
        val seeded = seedAndActivate()
        val noaaId = seeded.active!!.packageId
        val noaaBytes = packageFiles(noaaId)
        scenario.onActivity { it.importFixture(LifecycleFixture.WSV) }
        awaitIdle()
        assertEquals(ImportActivity.COMPLETED, activity.controller.state.value.importProgress?.activity)
        val wsv = snapshot().packages.single { it.cells.any { cell -> cell.name == "1W7BRW02" } }
        assertEquals("wsv-inland-s57", wsv.cells.single().provider)
        assertEquals(8, wsv.cells.single().edition)
        val previousPins = binding().activation.catalog.sources
        scenario.onActivity { it.controller.deletePackage(wsv.id) }
        awaitIdle(); awaitCurrentFrame()
        assertFalse(packageDirectory(wsv.id).exists())
        assertEquals(seeded.active, snapshot().active)
        assertEquals(previousPins, binding().activation.catalog.sources)
        assertEquals(noaaBytes, packageFiles(noaaId))
        scenario.onActivity { it.controller.deletePackage(noaaId) }
        awaitIdle()
        assertTrue(snapshot().packages.isEmpty())
        assertNull(snapshot().active)
        assertFalse(snapshot().pendingCleanup)
        assertFalse(packageDirectory(noaaId).exists())
        assertFalse(activity.engine.status.value.chartLoaded)
        scenario.recreate(); scenario.onActivity { activity = it }; awaitPrepared()
        assertNull(snapshot().active)
        assertTrue(snapshot().packages.isEmpty())
        assertFalse(activity.engine.status.value.chartLoaded)
        evidence("delete-last-recreated")
    }

    @Test fun cancellationDuringActualUriReadWaitsForCleanupAndPreservesActiveSources() {
        seedAndActivate()
        val before = snapshot()
        val id = requireNotNull(before.active).packageId
        val bytes = packageFiles(id)
        val durable = durableFiles()
        val native = binding()
        scenario.onActivity { it.importFixture(LifecycleFixture.SLOW_SEED) }
        compose.waitUntil(30_000) {
            val state = activity.controller.state.value
            state.busy && state.importProgress?.phase == ImportPhase.READING &&
                (state.importProgress?.processedBytes ?: 0) > 0 && state.importProgress?.canCancel == true
        }
        scenario.onActivity {
            it.controller.cancelImport()
            assertTrue(it.controller.state.value.busy)
            assertEquals(ImportActivity.CANCELLING, it.controller.state.value.importProgress?.activity)
        }
        awaitIdle()
        assertEquals(ImportActivity.CANCELLED, activity.controller.state.value.importProgress?.activity)
        assertEquals(before.active, snapshot().active)
        assertEquals(before.activeRelease, snapshot().activeRelease)
        assertEquals(1, snapshot().packages.size)
        assertEquals(durable, durableFiles())
        assertEquals(bytes, packageFiles(id))
        assertEquals(native.activation.catalog, binding().activation.catalog)
        assertEquals(native.sourceGeneration, binding().sourceGeneration)
        assertTrue(File(activity.acceptanceContext.libraryRoot, "staging").listFiles().orEmpty().isEmpty())
        evidence("cancelled-reading-preserved")
    }

    @Test fun k1ObservesRealNativeCommitBeforeLibraryPublicationThenContinues() =
        observeBarrier(LifecycleCrashPoint.SOURCE_COMMITTED_BEFORE_SELECTION)

    @Test fun k2ObservesRealReleaseAfterPublicationBeforePayloadRemovalThenContinues() =
        observeBarrier(LifecycleCrashPoint.SELECTION_PUBLISHED_RELEASE_ACKED_BEFORE_FILES)

    /** Run this single method twice using the README's external arm/kill/relaunch/verify recipe.
     * Never calls killProcess/force-stop itself, never treats recreation as a process crash.
     */
    @Test fun processKillRecipe() {
        val phase = arguments.getString("lifecyclePhase")
        assumeTrue("External process-kill recipe only", phase != null)
        val point = when (phase) {
            "arm-k1", "verify-k1" -> LifecycleCrashPoint.SOURCE_COMMITTED_BEFORE_SELECTION
            "arm-k2", "verify-k2" -> LifecycleCrashPoint.SELECTION_PUBLISHED_RELEASE_ACKED_BEFORE_FILES
            else -> error("Unknown lifecyclePhase")
        }
        if (phase!!.startsWith("arm-")) {
            assertTrue("Arm requires a fresh isolated run root", snapshot().packages.isEmpty())
            seedAndActivate()
            scenario.onActivity { it.beginBarrierUpdate(point) }
            val event = awaitBarrier(point)
            verifyBarrier(event, point)
            // File is fsynced before barrier visibility. The external owner has at most 30 seconds
            // from that event to kill this *isolated* target process without Activity teardown.
            compose.waitUntil(35_000) { activity.engine.barrier.value == null }
            fail("External process kill did not occur before the bounded test barrier expired")
        } else {
            val directory = File(activity.acceptanceContext.filesDir, "lifecycle-events")
            val file = directory.listFiles().orEmpty().single { it.name.endsWith("-${point.name}.json") }
            val checkpoint = JSONObject(file.readText())
            assertNotEquals("Recreation is not process-kill evidence", checkpoint.getInt("pid"), Process.myPid())
            assertEquals(activity.acceptanceContext.runId, checkpoint.getString("runId"))
            assertTrue(checkpoint.getJSONObject("actualNativeResult").getBoolean("success"))
            awaitCurrentFrame()
            val recovered = snapshot()
            assertEquals(2, recovered.selectionVersion)
            val selectedPack = recovered.packages.single { it.id == recovered.active?.packageId }
            if (point == LifecycleCrashPoint.SOURCE_COMMITTED_BEFORE_SELECTION) {
                assertEquals(2, selectedPack.cells.single { it.name == "US5BOSCC" }.update)
                assertEquals(checkpoint.getString("previousDurableDigest"), durableDigest())
                val saved = checkpoint.getJSONObject("selection")
                assertEquals(saved.getString("packageId"), recovered.active?.packageId)
                val restored = binding()
                assertEquals(2, restored.activation.catalog.sources.single { it.name == "US5BOSCC" }.update)
                val released = checkpoint.getJSONObject("release").getJSONArray("cells")
                assertEquals((0 until released.length()).associate { index -> released.getJSONObject(index).let {
                    it.getString("name") to it.getString("sourceKey")
                } }, restored.activation.catalog.sources.associate { it.name to it.sourceKey })
                assertTrue(restored.activation.catalog.sources.all { source -> source.files.all { pin ->
                    pin.sha256 == ChartLibrary.sha256(activity.acceptanceContext.requireOwned(File(pin.path)))
                } })
                // Validated but unpublished replacement packages may remain. They are never
                // silently selected or globally removed by this acceptance harness.
            } else {
                assertEquals(4, selectedPack.cells.single { it.name == "US5BOSCC" }.update)
                assertUpdatedSources(binding())
                assertFalse(recovered.pendingCleanup)
                val oldId = checkpoint.getJSONObject("previousSelection").getString("packageId")
                assertFalse(packageDirectory(oldId).exists())
                assertEquals(1, recovered.packages.size)
            }
            evidence("${phase}-real-process-recovery")
        }
    }

    private fun observeBarrier(point: LifecycleCrashPoint) {
        val before = seedAndActivate()
        val oldId = before.active!!.packageId
        val originalFiles = packageFiles(oldId)
        val previousDurable = durableFiles()
        scenario.onActivity { it.beginBarrierUpdate(point) }
        val event = awaitBarrier(point)
        verifyBarrier(event, point)
        assertEquals(originalFiles, packageFiles(oldId))
        assertUpdatedSources(binding())
        if (point == LifecycleCrashPoint.SOURCE_COMMITTED_BEFORE_SELECTION) {
            assertEquals(before.active, snapshot().active)
            assertEquals(before.activeRelease, snapshot().activeRelease)
            assertEquals(previousDurable, durableFiles())
        } else {
            assertNotEquals(before.active, snapshot().active)
            assertTrue(snapshot().pendingCleanup)
            assertNotEquals(previousDurable, durableFiles())
        }
        scenario.onActivity { it.engine.continueAfterObservation(event.token) }
        awaitIdle(); awaitCurrentFrame()
        assertUpdatedSources(binding())
        assertFalse(snapshot().pendingCleanup)
        assertFalse(packageDirectory(oldId).exists())
        evidence("${point.name.lowercase().replace('_', '-')}-continued")
    }

    private fun verifyBarrier(event: LifecycleBarrierEvent, point: LifecycleCrashPoint) {
        assertEquals(point, event.point)
        activity.acceptanceContext.requireOwned(event.file)
        val json = JSONObject(event.file.readText())
        assertEquals(event.token, json.getString("barrierToken"))
        assertEquals(point.name, json.getString("phase"))
        assertTrue(json.getJSONObject("actualNativeResult").getBoolean("success"))
        assertEquals(2, json.getJSONArray("actualNativeSources").length())
        assertEquals(binding().activation.catalog.generation, json.getString("expectedCatalog"))
        assertEquals(binding().sourceGeneration, json.getLong("expectedSourceGeneration"))
        assertTrue(json.getBoolean("syntheticWait"))
        assertFalse(json.getBoolean("physicalDisplayLatencyMeasured"))
    }
    private fun awaitBarrier(point: LifecycleCrashPoint): LifecycleBarrierEvent {
        compose.waitUntil(120_000) { activity.engine.barrier.value?.point == point }
        return requireNotNull(activity.engine.barrier.value)
    }
    private fun awaitPrepared() {
        compose.waitUntil(120_000) { activity.prepared || activity.failure != null }
        assertNull(activity.failure)
        assertTrue(activity.prepared)
        compose.waitUntil(30_000) { activity.engine.status.value.surfaceAttached }
    }
    private fun awaitIdle() {
        compose.waitUntil(120_000) { !activity.controller.state.value.busy }
        assertNull(activity.failure)
    }
    private fun awaitCurrentFrame() {
        compose.waitUntil(120_000) {
            val state = activity.engine.status.value
            val residency = activity.controller.state.value.residency
            state.chartLoaded && state.surfaceAttached && state.residentCameraReady && !state.presentationPending &&
                state.confirmedFrame?.sourceGeneration == state.chartSourceGeneration &&
                state.confirmedFrame?.contentGeneration == state.chartContentGeneration &&
                residency?.complete == true && residency.missing == 0 && residency.loading == 0
        }
    }
    private fun seedAndActivate(): ChartLibrarySnapshot {
        assertTrue(snapshot().packages.isEmpty())
        scenario.onActivity { it.importFixture(LifecycleFixture.SEED) }
        awaitIdle()
        assertEquals(ImportActivity.COMPLETED, activity.controller.state.value.importProgress?.activity)
        assertEquals(1, snapshot().packages.size)
        val pack = snapshot().packages.single()
        assertEquals(setOf("US5BOSCC", "US5BOSDD"), pack.cells.map { it.name }.toSet())
        assertEquals(2, pack.cells.single { it.name == "US5BOSCC" }.update)
        scenario.onActivity { it.activateNoaaFixture() }
        awaitIdle(); awaitCurrentFrame()
        assertEquals(2, snapshot().selectionVersion)
        return snapshot()
    }
    private fun snapshot() = activity.library().snapshot()
    private fun binding(): ChartResidentBinding = runBlocking { requireNotNull(activity.engine.readResidentBinding()) }
    private fun assertUpdatedSources(binding: ChartResidentBinding) {
        val sources = binding.activation.catalog.sources
        assertEquals(2, sources.size)
        assertTrue(LifecycleAcceptanceFixtures.updatedNoaa(activity.acceptanceContext).all { expected ->
            sources.singleOrNull { it.name == expected.name }?.let(expected::matches) == true
        })
        sources.flatMap { it.files }.forEach { pin ->
            val file = activity.acceptanceContext.requireOwned(File(pin.path))
            assertEquals(pin.sha256, ChartLibrary.sha256(file))
        }
    }
    private fun sourceFileHashes(binding: ChartResidentBinding, name: String) =
        binding.activation.catalog.sources.single { it.name == name }.files.associate { pin ->
            File(pin.path).name to ChartLibrary.sha256(activity.acceptanceContext.requireOwned(File(pin.path)))
        }
    private fun packageDirectory(id: String) = activity.acceptanceContext.requireOwned(
        File(activity.acceptanceContext.libraryRoot, "packages/$id"))
    private fun packageFiles(id: String): Map<String, String> {
        val directory = packageDirectory(id)
        assertTrue(directory.isDirectory)
        return directory.walkTopDown().filter { it.isFile }.associate {
            activity.acceptanceContext.requireOwned(it)
            it.relativeTo(directory).invariantSeparatorsPath to ChartLibrary.sha256(it)
        }
    }
    private fun durableFiles() = listOf("lifecycle-state-v1.json", "active.json").associateWith { name ->
        val file = activity.acceptanceContext.requireOwned(File(activity.acceptanceContext.libraryRoot, name))
        if (file.isFile) ChartLibrary.sha256(file) else "ABSENT"
    }
    private fun durableDigest() = durableFiles().entries.joinToString(":") { "${it.key}=${it.value}" }
    private fun evidence(label: String) = runBlocking { activity.engine.recordSnapshot(label) }
}
