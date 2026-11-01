package com.no5.helm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.no5.helm.chart.*
import com.no5.preparation.ui.No5ControlTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Native Compose dialog at API29/density320. No GLES or device-rendering claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartStyleSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<No5ImmersiveActivity>
    private val busy = mutableStateOf(false)
    private val applied = mutableListOf<ChartPresentation>()
    private var dismissals = 0
    private var actualMapSettingsInk: Color? = null
    private var completion: ((Boolean) -> Unit)? = null

    private fun launch(active: ChartPresentation = ChartPresentation(), saved: PresentationProfiles = PresentationProfiles(), redHost: Boolean = false,
        confirmed: Boolean = false) {
        activity = Robolectric.buildActivity(No5ImmersiveActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
        activity.get().setContent {
            No5ControlTheme(redHost) {
                val ink = MaterialTheme.colorScheme.onSurface
                SideEffect { actualMapSettingsInk = ink }
                ChartStyleSettings(active, saved, busy.value, { dismissals++ }, { applied += it },
                    onApplyConfirmed = if (confirmed) ({ value, finished -> applied += value; completion = finished }) else null)
            }
        }
        focusHostInputWindow(activity.get(), compose)
    }

    private fun SemanticsNodeInteraction.performFocusedClick(): SemanticsNodeInteraction {
        focusHostInputWindow(activity.get(), compose)
        performClick()
        focusHostInputWindow(activity.get(), compose)
        return this
    }

    @After fun close() { if (::activity.isInitialized) activity.pause().stop().destroy() }

    @Test fun dirtyCloseWaitsForActualSaveConfirmationAndFailureKeepsDraft() {
        launch(confirmed = true)
        compose.onNodeWithTag("chart-style-gallery").performScrollToNode(hasTestTag("chart-style-prism"))
        compose.onNodeWithTag("chart-style-prism").performFocusedClick()
        compose.onNodeWithTag("chart-style-close").performFocusedClick()
        compose.onNodeWithTag("unsaved-changes-save").performFocusedClick()
        compose.runOnIdle { assertEquals(0, dismissals); assertEquals(1, applied.size) }
        compose.onNodeWithTag("unsaved-changes-save").assertIsNotEnabled()
        compose.runOnIdle { requireNotNull(completion).invoke(false) }
        compose.onNodeWithTag("unsaved-changes-error").assertExists()
        compose.runOnIdle { assertEquals(0, dismissals) }
        compose.onNodeWithTag("unsaved-changes-save").performFocusedClick()
        val stale = requireNotNull(completion)
        compose.runOnIdle { stale(true); stale(true) }
        compose.runOnIdle { assertEquals("A duplicate result cannot close twice", 1, dismissals) }
    }

    @Test fun discardIsExplicitAndContinueKeepsSelectedStyle() {
        launch()
        compose.onNodeWithTag("chart-style-gallery").performScrollToNode(hasTestTag("chart-style-prism"))
        compose.onNodeWithTag("chart-style-prism").performFocusedClick()
        compose.onNodeWithTag("chart-style-close").performFocusedClick()
        compose.onNodeWithTag("unsaved-changes-continue").performFocusedClick()
        compose.onNodeWithTag("chart-style-prism").assertIsSelected()
        compose.runOnIdle { assertEquals(0, dismissals); assertTrue(applied.isEmpty()) }
        compose.onNodeWithTag("chart-style-close").performFocusedClick()
        compose.onNodeWithTag("unsaved-changes-discard").performFocusedClick()
        compose.runOnIdle { assertEquals(1, dismissals); assertTrue(applied.isEmpty()) }
    }

    @Test fun gallerySelectionOnlyAppliesOnExplicitConfirmation() {
        launch()
        val dialog = compose.onNodeWithTag("chart-style-dialog").fetchSemanticsNode().boundsInRoot
        val safe = compose.onNodeWithTag("knob-safe-dialog-content").fetchSemanticsNode().boundsInRoot
        val gallery = compose.onNodeWithTag("chart-style-gallery").fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithTag("chart-style-detail").fetchSemanticsNode().boundsInRoot
        assertTrue("Dialog is limited to a finite safe surface", dialog.width > 0f && dialog.width < activity.get().window.decorView.width)
        assertEquals("Style surface fills the shared safe area, not the whole window", safe, dialog)
        assertTrue("Adaptive detail follows gallery without overlap", detail.left >= gallery.right || detail.top >= gallery.bottom)
        compose.onNodeWithTag("chart-style-gallery").performScrollToNode(hasTestTag("chart-style-lagoon-sculpt"))
        compose.onNodeWithTag("chart-style-lagoon-sculpt").performFocusedClick().assertIsSelected()
        assertTrue(applied.isEmpty())
        compose.onNodeWithTag("chart-style-symbol-r04").assertIsSelected()
        compose.onNodeWithTag("chart-style-land-relief").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("Schematische Farbvorschau").assertExists()
        assertFixedActionsWithinDialog()
        compose.onNodeWithTag("chart-style-apply").performFocusedClick()
        assertEquals(listOf(ChartPresentation("lagoon-sculpt", ChartSymbolSet.R04)), applied)
        save("chart-style-gallery-day")
    }

    @Test fun fixedActionsStayVisibleAfterScrollingToTheLastDayStyle() {
        launch()
        val before = listOf("chart-style-close", "chart-style-apply").map {
            compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        compose.onNodeWithTag("chart-style-gallery").performScrollToNode(hasTestTag("chart-style-prism"))
        compose.onNodeWithTag("chart-style-prism").performFocusedClick().assertIsSelected()
        compose.waitForIdle()
        assertFixedActionsWithinDialog()
        listOf("chart-style-close", "chart-style-apply").forEachIndexed { index, tag ->
            val after = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Gallery scrolling must not move the fixed action $tag", before[index].top, after.top, .1f)
            assertEquals(before[index].bottom, after.bottom, .1f)
        }
        compose.onNodeWithTag("chart-style-apply").performFocusedClick()
        assertEquals(listOf(ChartPresentation("prism")), applied)
        save("chart-style-gallery-last-style-safe-footer")
    }

    @Test fun redNightChartKeepsActualMapSettingsControlsAndIndependentSavedProfiles() {
        val saved = PresentationProfiles(day = ChartPresentation("prism", ChartSymbolSet.NO5, true),
            night = ChartPresentation("redline", ChartSymbolSet.BIG), gray = ChartPresentation("redwater-gray"))
        launch(saved.night, saved, redHost = true)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Kartenstil", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Menu inherits actual MAP Settings night ink independently of the chart draft", actualMapSettingsInk, layouts.single().layoutInput.style.color)
        compose.onNodeWithTag("chart-style-redline").assertIsSelected()
        save("chart-style-gallery-red-night")
        compose.onNodeWithTag("chart-style-mode-DAY").performFocusedClick()
        compose.onNodeWithTag("chart-style-selection").assertTextEquals("Auswahl: Prism · plastisch")
        compose.onNodeWithTag("chart-style-mode-GRAY_COMPARISON").performFocusedClick()
        compose.onNodeWithTag("chart-style-redwater-gray").assertIsSelected()
        compose.onNodeWithTag("chart-style-apply").performFocusedClick()
        assertEquals(listOf(saved.gray), applied)
        assertEquals(ChartPresentation("redline", ChartSymbolSet.BIG), saved.night)
    }

    @Test fun awaitingRendererConfirmationLocksDraftAndDismissal() {
        launch(ChartPresentation("redwater-focus"))
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithTag("chart-style-apply").assertIsNotEnabled()
        compose.onNodeWithTag("chart-style-close").assertIsNotEnabled()
        compose.onNodeWithTag("chart-style-mode-DAY").assertIsNotEnabled()
        compose.onNodeWithTag("chart-style-redline").assertIsNotEnabled()
        assertFixedActionsWithinDialog()
        assertTrue(applied.isEmpty())
        assertEquals(0, dismissals)
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithTag("chart-style-close").performFocusedClick()
        assertEquals(1, dismissals)
    }

    @Test @Config(qualifiers = "w600dp-h900dp-port-xhdpi")
    fun narrowWindowStacksControlsAndKeepsConfirmationVisible() {
        launch()
        compose.onNodeWithTag("chart-style-apply").assertIsDisplayed()
        compose.onNodeWithTag("chart-style-close").assertIsDisplayed()
        val gallery = compose.onNodeWithTag("chart-style-gallery").fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithTag("chart-style-detail").fetchSemanticsNode().boundsInRoot
        assertTrue("Narrow mode places detail after the gallery", detail.top >= gallery.bottom)
        assertFixedActionsWithinDialog()
        compose.onNodeWithTag("chart-style-gallery").performScrollToNode(hasTestTag("chart-style-prism"))
        compose.onNodeWithTag("chart-style-prism").performFocusedClick()
        assertFixedActionsWithinDialog()
        save("chart-style-gallery-narrow")
    }

    private fun assertFixedActionsWithinDialog() {
        val dialog = compose.onNodeWithTag("chart-style-dialog").fetchSemanticsNode().boundsInRoot
        val gallery = compose.onNodeWithTag("chart-style-gallery").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("chart-style-footer").fetchSemanticsNode().boundsInRoot
        assertTrue("Body gallery is above fixed actions", gallery.bottom <= footer.top + .1f)
        val actions = listOf("chart-style-close", "chart-style-apply").map { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.also { rect ->
                assertTrue("$tag left", rect.left >= dialog.left - .1f)
                assertTrue("$tag right", rect.right <= dialog.right + .1f)
                assertTrue("$tag top", rect.top >= footer.top - .1f)
                assertTrue("$tag bottom", rect.bottom <= dialog.bottom + .1f)
            }
        }
        assertTrue("Footer action touch regions do not overlap", actions[0].right <= actions[1].left)
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
