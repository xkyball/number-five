package com.no5.helm.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartLibraryManagementTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val measurement = mutableStateOf(false)
    private val night = mutableStateOf(false)
    private var parentInk: androidx.compose.ui.graphics.Color? = null
    private var residentMode = true
    private var managementSupported = true
    private var activated: ChartSelection? = null
    private val deactivated = mutableListOf<ChartSelection>()
    private val deleted = mutableListOf<String>()
    private val updates = mutableListOf<Pair<String, Uri>>()
    private val actions = object : ChartLibraryActions {
        override val state = MutableStateFlow(ChartLibraryUiState())
        override val supportsResidentSelection get() = residentMode
        override val supportsLibraryManagement get() = managementSupported
        override fun importPackage(uri: Uri) = Unit
        override fun activate(selection: ChartSelection) { activated = selection }
        override fun cancelImport() = Unit
        override fun deactivate(selection: ChartSelection) { deactivated += selection }
        override fun deletePackage(packageId: String) {
            deleted += packageId
            state.value = state.value.copy(busy = true, progress = "Kartenpaket wird entfernt …")
        }
        override fun updatePackage(packageId: String, uri: Uri) { updates += packageId to uri }
    }
    private fun cell(name: String, cancelled: Boolean = false, located: Boolean = true) =
        ChartCell(name, 1, 0, "$name.000", "20261010", emptyMap(), cancelled,
            if (located) ChartSelectionExtent(42.2, 42.3, -71.8, -71.7) else null)
    private fun pack(cells: List<ChartCell>, id: String = "11111111-1111-1111-1111-111111111111") =
        ChartPackage(id, "NOAA · local", 1, cells)

    @Before fun before() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        activity.get().setContent {
            No5ControlTheme(night.value) {
                val ink = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                androidx.compose.runtime.SideEffect { parentInk = ink }
                No5Panel("KARTEN") { ChartLibraryPanel(actions, measurement.value) }
            }
        }
    }
    @After fun after() { activity.pause().stop().destroy() }
    private fun publish(stored: ChartPackage, active: ChartSelection? = null) = compose.runOnIdle {
        actions.state.value = ChartLibraryUiState(ChartLibrarySnapshot(listOf(stored), active))
    }
    private fun openSelection(stored: ChartPackage, active: ChartSelection? = null) {
        publish(stored, active)
        compose.onNodeWithText("Zellen auswählen").performScrollTo().performClick()
        focusHostInputWindow(activity.get(), compose)
    }
    private fun openManagement(stored: ChartPackage, active: ChartSelection? = null) {
        publish(stored, active)
        compose.onNodeWithTag("chart-package-manage-${stored.id}").performScrollTo().performClick()
        focusHostInputWindow(activity.get(), compose)
    }
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performClick()

    @Test fun managementDialogInheritsTheActualNightShellInsteadOfForcingDayControls() {
        compose.runOnIdle { night.value = true }
        openManagement(pack(listOf(cell("A"))))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("chart-management-status", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(parentInk, layouts.single().layoutInput.style.color)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFD40000), parentInk)
        compose.onNodeWithTag("chart-management-close").assertIsDisplayed()
    }

    @Test fun residentCanSelectThe101stIndividualCellAndLegacyStillStopsAt100() {
        val stored = pack((0..100).map { cell("C%03d".format(it)) })
        val active = ChartSelection(stored.id, stored.cells.take(100).map { it.name })
        openSelection(stored, active)
        compose.onNodeWithTag("chart-zone-42:-72").performScrollTo().performClick()
        repeat(8) { compose.onNodeWithText("Weiter").performScrollTo().performClick() }
        compose.onNodeWithTag("chart-cell-C100").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("chart-region-draft-count").assertTextEquals("Entwurf: 101 Zellen")
        tap("chart-noaa-terms")
        tap("chart-package-activate")
        assertEquals(101, activated!!.cells.size)
        tap("chart-region-close")
        tap("unsaved-changes-discard")
        compose.runOnIdle { residentMode = false; actions.state.value = actions.state.value.copy(activationRevision = 1) }
        // Re-enter with the actual 100-cell legacy selection, rather than retaining the 101-cell draft.
        val second = pack(stored.cells, "22222222-2222-2222-2222-222222222222")
        openSelection(second, ChartSelection(second.id, active.cells))
        compose.onNodeWithTag("chart-zone-42:-72").performScrollTo().performClick()
        repeat(8) { compose.onNodeWithText("Weiter").performScrollTo().performClick() }
        compose.onNodeWithTag("chart-cell-C100").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("chart-cell-C099").assertIsEnabled().performClick()
        compose.onNodeWithTag("chart-cell-C100").assertIsEnabled()
    }

    @Test fun residentAll400HasNoSilentSubsetAndUnknownCoordinatesAreSelectable() {
        val stored = pack((0 until 400).map { cell("C%03d".format(it), located = false) } + cell("CANCELLED", cancelled = true))
        openSelection(stored)
        tap("chart-select-none"); tap("chart-select-all")
        compose.onNodeWithTag("chart-region-draft-count").assertTextEquals("Entwurf: 400 Zellen")
        tap("chart-noaa-terms")
        tap("chart-package-activate")
        assertEquals(stored.cells.filterNot { it.cancelled }.map { it.name }, activated!!.cells)
        compose.onNodeWithTag("chart-zone-unlocated").performScrollTo().performClick()
        compose.onNodeWithTag("chart-cell-C000").performScrollTo().assertIsEnabled()
    }

    @Test fun selectedDeactivationUsesOnlyActiveIntersectionAndRemainsARequestUntilConfirmed() {
        val stored = pack(listOf(cell("A"), cell("B"), cell("C")))
        val active = ChartSelection(stored.id, listOf("A", "B"))
        openSelection(stored, active)
        tap("chart-select-none")
        compose.onNodeWithTag("chart-cell-B").performScrollTo().performClick()
        compose.onNodeWithTag("chart-cell-C").performClick()
        tap("chart-region-deactivate")
        assertEquals(listOf(ChartSelection(stored.id, listOf("B"))), deactivated)
        compose.onNodeWithTag("chart-region-active-status").assertTextEquals("Aktiv: 2 Zellen")
        compose.runOnIdle { actions.state.value = actions.state.value.copy(snapshot = ChartLibrarySnapshot(listOf(stored), ChartSelection(stored.id, listOf("A")))) }
        compose.onNodeWithTag("chart-region-active-status").assertTextEquals("Aktiv: 1 Zelle")
        compose.onNodeWithTag("chart-region-deactivate").assertIsNotEnabled()
    }

    @Test fun packageDeactivationKeepsStoredInventoryAndDoesNotOptimisticallyClearActiveStatus() {
        val stored = pack(listOf(cell("A"), cell("B")))
        val active = ChartSelection(stored.id, listOf("A", "B"))
        openManagement(stored, active)
        tap("chart-package-deactivate")
        assertEquals(listOf(active), deactivated)
        compose.onNodeWithTag("chart-management-status").assertTextEquals("2 gespeichert · 2 aktiv")
        compose.runOnIdle { actions.state.value = actions.state.value.copy(snapshot = ChartLibrarySnapshot(listOf(stored), null), message = "Auswahl deaktiviert. Dateien bleiben gespeichert.") }
        compose.onNodeWithTag("chart-management-status").assertTextEquals("2 gespeichert · 0 aktiv")
        compose.onNodeWithTag("chart-package-deactivate").assertIsNotEnabled()
        compose.onNodeWithTag("chart-management-result").assertTextEquals("Auswahl deaktiviert. Dateien bleiben gespeichert.")
    }

    @Test fun deletionRequiresPackageConfirmationAndShowsCommittedResultWithoutClosingStatus() {
        val stored = pack(listOf(cell("A"), cell("B")))
        openManagement(stored, ChartSelection(stored.id, listOf("A")))
        tap("chart-package-delete")
        compose.onNodeWithTag("chart-package-delete-summary").assertTextEquals("„${stored.label}“ wird aus dem gespeicherten Kartenbestand entfernt. Die aktive Zelle dieses Pakets wird dabei deaktiviert. Andere Pakete bleiben erhalten.")
        tap("chart-package-delete-cancel")
        assertTrue(deleted.isEmpty())
        tap("chart-package-delete"); tap("chart-package-delete-confirm")
        assertEquals(listOf(stored.id), deleted)
        compose.onNodeWithTag("chart-management-status").assertTextEquals("2 gespeichert · 1 aktiv")
        compose.onNodeWithTag("chart-package-delete").assertIsNotEnabled()
        compose.runOnIdle { actions.state.value = ChartLibraryUiState(ChartLibrarySnapshot(), message = "Paket gelöscht.") }
        compose.onNodeWithTag("chart-management-status").assertTextEquals("Nicht mehr gespeichert")
        compose.onNodeWithTag("chart-management-result").assertTextEquals("Paket gelöscht.")
        compose.onNodeWithTag("chart-management-close").assertIsDisplayed()
        compose.onNodeWithTag("chart-package-update").assertIsNotEnabled()
    }

    @Test fun updateUsesOpenDocumentAndKeepsTheTargetWhileOtherPackageStateChanges() {
        val stored = pack(listOf(cell("A")))
        openManagement(stored)
        tap("chart-package-update")
        val picker = shadowOf(activity.get()).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent.action)
        val another = pack(listOf(cell("B")), "22222222-2222-2222-2222-222222222222")
        val uri = Uri.parse("content://no5.test/chart-update/noaa.zip")
        compose.runOnIdle {
            actions.state.value = actions.state.value.copy(snapshot = ChartLibrarySnapshot(listOf(another, stored), ChartSelection(another.id, listOf("B"))))
            activity.get().activityResultRegistry.dispatchResult(picker.requestCode, Activity.RESULT_OK, Intent().setData(uri))
        }
        assertEquals(listOf(stored.id to uri), updates)
        tap("chart-package-update")
        val cancelled = shadowOf(activity.get()).nextStartedActivityForResult
        compose.runOnIdle { activity.get().activityResultRegistry.dispatchResult(cancelled.requestCode, Activity.RESULT_CANCELED, null) }
        assertEquals(1, updates.size)
    }

    @Test fun busyAndMeasurementBlockAllWritesWhileKeepingStatusVisible() {
        val stored = pack(listOf(cell("A")))
        openManagement(stored, ChartSelection(stored.id, listOf("A")))
        compose.runOnIdle { measurement.value = true }
        for (tag in listOf("chart-package-deactivate", "chart-package-update", "chart-package-delete"))
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        compose.onNodeWithTag("chart-management-next-step").assertTextEquals("Rendereraufnahme beenden, dann Kartenbestand ändern.")
        compose.runOnIdle { measurement.value = false; actions.state.value = actions.state.value.copy(busy = true, progress = "Dateien werden geprüft …") }
        for (tag in listOf("chart-package-deactivate", "chart-package-update", "chart-package-delete"))
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        compose.onNodeWithTag("chart-management-next-step").assertTextEquals("Dateien werden geprüft …")
        compose.onNodeWithTag("chart-management-close").assertIsDisplayed()
    }

    @Test fun actionsAndStatusStayFixedWhenManagementInstructionsScrollWithinKnobSafeDialog() {
        val stored = pack(listOf(cell("A")))
        openManagement(stored, ChartSelection(stored.id, listOf("A")))
        val header = compose.onNodeWithTag("chart-management-fixed-status").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("chart-management-fixed-actions").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Es findet keine Online-Aktualitätsprüfung statt. Alter, fehlende Koordinaten oder abgebrochene Ladejobs sind keine Abkündigung.").performScrollTo()
        for (tag in listOf("chart-management-status", "chart-package-deactivate", "chart-package-update", "chart-package-delete", "chart-management-close"))
            compose.onNodeWithTag(tag).assertIsDisplayed()
        assertEquals(header, compose.onNodeWithTag("chart-management-fixed-status").fetchSemanticsNode().boundsInRoot)
        assertEquals(footer, compose.onNodeWithTag("chart-management-fixed-actions").fetchSemanticsNode().boundsInRoot)
        val safe = compose.onNodeWithTag("knob-safe-dialog-content").fetchSemanticsNode().boundsInRoot
        assertTrue(header.left >= safe.left && header.right <= safe.right)
        assertTrue(footer.left >= safe.left && footer.right <= safe.right)
        assertTrue(footer.bottom <= safe.bottom)
        save("chart-library-management-fixed-actions")
    }

    @Test fun unsupportedControllerDoesNotOfferInoperativeManagementControls() {
        compose.runOnIdle { managementSupported = false }
        val stored = pack(listOf(cell("A")))
        publish(stored)
        compose.onNodeWithTag("chart-package-manage-${stored.id}").assertDoesNotExist()
        compose.onNodeWithText("Zellen auswählen").performScrollTo().performClick()
        focusHostInputWindow(activity.get(), compose)
        compose.onNodeWithTag("chart-region-deactivate").assertDoesNotExist()
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
