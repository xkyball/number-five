package com.no5.helm.ui

import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.helm.library.*
import com.no5.preparation.ui.No5ControlTheme
import com.no5.preparation.ui.No5Panel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartRegionPickerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val measurement = mutableStateOf(false)
    private val night = mutableStateOf(false)
    private var parentInk: androidx.compose.ui.graphics.Color? = null
    private var activated: ChartSelection? = null
    private var residentMode = false
    private var holdActivation = false
    private var activationCalls = 0
    private val actions = object : ChartLibraryActions {
        override val state = MutableStateFlow(ChartLibraryUiState())
        override val supportsResidentSelection get() = residentMode
        override fun importPackage(uri: Uri) = Unit
        override fun activate(selection: ChartSelection) {
            activated = selection; activationCalls++
            if (holdActivation) state.value = state.value.copy(busy = true)
        }
        override fun cancelImport() = Unit
    }
    private fun cell(name: String, lat: Double = 42.2, lon: Double = -71.8, cancelled: Boolean = false) =
        ChartCell(name, 1, 0, "$name.000", "20261010", emptyMap(), cancelled, ChartSelectionExtent(lat, lat + .1, lon, lon + .1))
    private fun pack(cells: List<ChartCell>, id: String = "11111111-1111-1111-1111-111111111111") = ChartPackage(id, "NOAA · local", 1, cells)
    @Before fun before() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        // These selection fixtures represent already accepted NOAA packages.
        // Consent itself is exercised by NativeHelmScreenTest.
        activity.get().getSharedPreferences("chart-package-terms-v1", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("noaa.11111111-1111-1111-1111-111111111111", true)
            .putBoolean("noaa.22222222-2222-2222-2222-222222222222", true).commit()
        activity.get().setContent { No5ControlTheme(night.value) {
            val ink = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
            androidx.compose.runtime.SideEffect { parentInk = ink }
            No5Panel("KARTEN") { ChartLibraryPanel(actions, measurement.value) }
        } }
    }
    @After fun after() { activity.pause().stop().destroy() }
    private fun open(stored: ChartPackage, active: ChartSelection? = null) {
        compose.runOnIdle { actions.state.value = ChartLibraryUiState(ChartLibrarySnapshot(listOf(stored), active)) }
        compose.onNodeWithText("Zellen auswählen").performScrollTo().performClick()
        focusHostInputWindow(activity.get(), compose)
    }
    private fun tap(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (tag.startsWith("chart-zone-") && tag != "chart-zone-select" && tag != "chart-zone-deselect") node.performScrollTo()
        node.performClick()
    }
    @Test fun regionDialogInheritsTheActualNightShellInsteadOfForcingDayControls() {
        compose.runOnIdle { night.value = true }
        open(pack(listOf(cell("A"))))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("chart-region-draft-count", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(parentInk, layouts.single().layoutInput.style.color)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFD40000), parentInk)
        compose.onNodeWithTag("chart-region-close").assertIsDisplayed()
    }
    @Test fun allAndNoneHaveExplicitPackageScopeAndActivationOmitsCancelledCells() {
        val stored = pack(listOf(cell("A"), cell("B"), cell("C", cancelled = true)))
        open(stored); tap("chart-select-none")
        compose.onNodeWithTag("chart-package-activate").assertIsNotEnabled()
        tap("chart-select-all"); tap("chart-package-activate")
        assertEquals(ChartSelection(stored.id, listOf("A", "B")), activated)
    }
    @Test fun largerInventoryDisablesAllAndRendersOnlyChosenZonePage() {
        val stored = pack((0..100).map { cell("C%03d".format(it)) })
        open(stored)
        compose.onNodeWithTag("chart-select-all").assertIsNotEnabled()
        compose.onNodeWithTag("chart-cell-C000").assertDoesNotExist()
        tap("chart-zone-42:-72")
        compose.onNodeWithTag("chart-cell-C000").assertExists()
        compose.onNodeWithTag("chart-cell-C100").assertDoesNotExist()
        compose.onNodeWithTag("chart-package-activate").assertIsNotEnabled()
    }
    @Test fun residentEngineCanReleaseAll150CellsWithoutAnEager100CellSelectionLimit() {
        residentMode = true
        val stored = pack((0 until 150).map { cell("C%03d".format(it)) })
        open(stored); tap("chart-select-none")
        compose.onNodeWithTag("chart-select-all").assertIsEnabled()
        tap("chart-select-all")
        compose.onNodeWithTag("chart-region-draft-count").assertTextEquals("Entwurf: 150 Zellen")
        tap("chart-package-activate")
        assertEquals(150, activated!!.cells.size)
        assertEquals(stored.cells.map { it.name }, activated!!.cells)
    }
    @Test fun neighborsAddOneRingFromActiveSelectionInAnotherPackage() {
        val old = pack(listOf(cell("OLD")), "22222222-2222-2222-2222-222222222222")
        val stored = pack(listOf(cell("NEXT", lon = -70.8), cell("FAR", lon = -68.8)))
        compose.runOnIdle { actions.state.value = ChartLibraryUiState(ChartLibrarySnapshot(listOf(stored, old), ChartSelection(old.id, listOf("OLD")))) }
        compose.onAllNodesWithText("Zellen auswählen")[0].performScrollTo().performClick()
        tap("chart-select-none"); tap("chart-select-neighbors"); tap("chart-package-activate")
        assertEquals(ChartSelection(stored.id, listOf("NEXT")), activated)
    }
    @Test fun dirtyCloseKeepsDraftAndSaveWaitsForConfirmedActivation() {
        holdActivation = true
        val stored = pack(listOf(cell("A"), cell("B")))
        val original = ChartSelection(stored.id, listOf("A"))
        open(stored, original)
        compose.onNodeWithTag("chart-region-active-status").assertTextEquals("Aktiv: 1 Zelle")
        compose.onNodeWithTag("chart-region-next-step").assertTextEquals("Diese Auswahl ist aktiv. Fertig – du kannst schließen.")
        compose.onNodeWithTag("chart-package-activate").assertIsNotEnabled()
        tap("chart-select-none")
        compose.onNodeWithTag("chart-region-draft-count").assertTextEquals("Entwurf: 0/100 Zellen")
        compose.onNodeWithTag("chart-region-active-status").assertTextEquals("Aktiv: 1 Zelle")
        compose.onNodeWithTag("chart-cell-B").performScrollTo().performClick()
        tap("chart-region-close")
        compose.onNodeWithTag("unsaved-changes-continue").performClick()
        compose.onNodeWithTag("chart-region-draft-count").assertTextEquals("Entwurf: 1/100 Zellen")
        compose.onNodeWithTag("chart-region-next-step").assertTextEquals("Entwurf bereit. Auswahl aktivieren, dann schließen.")
        tap("chart-region-close")
        tap("unsaved-changes-save")
        assertEquals(ChartSelection(stored.id, listOf("B")), activated)
        compose.onNodeWithTag("chart-region-workspace").assertExists()
        compose.onNodeWithTag("unsaved-changes-save").assertIsNotEnabled()
        assertEquals(1, activationCalls)
        // An unrelated revision cannot confirm this selected package/cell set.
        compose.runOnIdle { actions.state.value = actions.state.value.copy(busy = false, activationRevision = 1, message = "Aktivierung fehlgeschlagen") }
        compose.onNodeWithTag("unsaved-changes-error").assertExists()
        compose.onNodeWithTag("chart-region-workspace").assertExists()
        tap("unsaved-changes-save")
        compose.runOnIdle { actions.state.value = actions.state.value.copy(busy = false, activationRevision = 2,
            snapshot = ChartLibrarySnapshot(listOf(stored), activated)) }
        compose.onNodeWithTag("chart-region-workspace").assertDoesNotExist()
        assertEquals(2, activationCalls)
    }
    @Test fun scrollingCellsKeepsStatusBulkActionsAndCompletionVisibleAndStationary() {
        val stored = pack((0..100).map { cell("C%03d".format(it)) })
        open(stored); tap("chart-zone-42:-72")
        val statusBefore = compose.onNodeWithTag("chart-region-fixed-status").fetchSemanticsNode().boundsInRoot
        val footerBefore = compose.onNodeWithTag("chart-region-fixed-footer").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("chart-cell-C011").performScrollTo()
        for (tag in listOf("chart-region-fixed-status", "chart-select-all", "chart-select-none", "chart-select-neighbors",
            "chart-zone-select", "chart-zone-deselect", "chart-region-next-step", "chart-package-activate", "chart-region-close")) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        assertEquals(statusBefore, compose.onNodeWithTag("chart-region-fixed-status").fetchSemanticsNode().boundsInRoot)
        assertEquals(footerBefore, compose.onNodeWithTag("chart-region-fixed-footer").fetchSemanticsNode().boundsInRoot)
        save("chart-region-zone-scrolled-fixed-actions")
    }
    @Test fun busyBlocksSelectionAndMeasurementBlocksActivation() {
        val stored = pack(listOf(cell("A")))
        open(stored)
        compose.runOnIdle { actions.state.value = actions.state.value.copy(busy = true) }
        compose.onNodeWithTag("chart-select-all").assertIsNotEnabled()
        compose.onNodeWithTag("chart-select-none").assertIsNotEnabled()
        compose.onNodeWithTag("chart-package-activate").assertIsNotEnabled()
        compose.runOnIdle { actions.state.value = actions.state.value.copy(busy = false); measurement.value = true }
        compose.onNodeWithTag("chart-package-activate").assertIsNotEnabled()
        compose.runOnIdle { measurement.value = false }
        compose.onNodeWithTag("chart-package-activate").assertIsEnabled()
    }
    private fun save(name: String) = compose.runOnIdle {
        val view = requireNotNull(requireNotNull(ShadowDialog.getLatestDialog()).window).decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val output = File("build/ui-evidence/$name.png").apply { parentFile!!.mkdirs() }
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
