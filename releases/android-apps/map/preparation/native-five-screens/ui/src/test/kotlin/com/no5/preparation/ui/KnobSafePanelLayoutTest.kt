package com.no5.preparation.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/** Real Compose text/layout on a host Dialog; synthetic insets are not a physical IME claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KnobSafePanelLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private var originalFont = 1f
    private lateinit var originalLanguage: String

    @Before fun rememberConfiguration() {
        originalFont = RuntimeEnvironment.getFontScale()
        originalLanguage = UiLanguage.current(RuntimeEnvironment.getApplication())
    }

    @After fun close() {
        com.no5.ui.No5DialogDiagnostics.observe=null
        if (::activity.isInitialized) com.no5.keyboard.SpecialKeyboardGeometry.publish(activity.get(), activity.get().packageName, null)
        if (::activity.isInitialized) activity.pause().stop().destroy()
        UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), originalLanguage)
        RuntimeEnvironment.setFontScale(originalFont)
        syntheticInsets(false)
    }

    private fun launch(fontScale: Float) {
        // Dialog owns another AndroidComposeView: set the real host font config
        // before creating it, rather than overriding only an outer LocalDensity.
        RuntimeEnvironment.setFontScale(fontScale)
        activity = Robolectric.buildActivity(ComponentActivity::class.java)
        activity.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible().windowFocusChanged(true)
    }

    private fun focusDialogWindow() {
        compose.waitForIdle()
        compose.runOnIdle {
            val dialog = checkNotNull(ShadowDialog.getShownDialogs().lastOrNull { it.isShowing })
            checkNotNull(dialog.window).decorView.dispatchWindowFocusChanged(true)
        }
        compose.waitForIdle()
    }

    @Test fun translatedUnsavedActionsAndTitleFitAtNormalFontAndStayFixedWhileBodyScrolls() = matrix(1f)
    @Test fun translatedUnsavedActionsAndTitleFitAtLargeFontAndStayFixedWhileBodyScrolls() = matrix(1.25f)

    private fun matrix(fontScale: Float) {
        launch(fontScale)
        val context = RuntimeEnvironment.getApplication()
        val epoch = mutableStateOf(0)
        val night = mutableStateOf(false)
        val longError = "HTTP_503 · " + "Synthetic retained draft explanation. ".repeat(120)
        val ink=mutableListOf<com.no5.ui.No5DialogTextFit>()
        com.no5.ui.No5DialogDiagnostics.observe={ink+=it}
        activity.get().setContent {
            key(epoch.value) {
                No5ControlTheme(night.value) { No5UnsavedChangesDialog({}, {}, {}, error = longError) }
            }
        }
        for (language in listOf("de", "en", "af")) for (redNight in listOf(false, true)) {
            compose.runOnIdle {
                UiLanguage.acceptConfirmed(context, language)
                night.value = redNight
                epoch.value++
            }
            focusDialogWindow()
            compose.runOnIdle{
                ink.clear()
                val view=checkNotNull(ShadowDialog.getShownDialogs().lastOrNull{it.isShowing}?.window).decorView
                val image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(image));image.recycle()
            }
            val title=compose.onNodeWithTag("unsaved-changes-title")
            val titleBounds=title.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val heading=ink.last{it.tag=="unsaved-changes-title"}
            assertEquals(No5Text.text(language,"Änderungen behalten?"),heading.text)
            assertEquals(35f*fontScale,heading.size,0f)
            assertEquals(fontScale,heading.fontScale,0f)
            assertTrue(heading.bounds.width()<=heading.available+.01f)
            val panel = compose.onNodeWithTag("unsaved-changes-dialog").fetchSemanticsNode().boundsInRoot
            assertTrue(KnobGeometry.safe(panel.left, panel.top, panel.right, panel.bottom))
            val actionBounds = mutableMapOf<String, Rect>()
            for ((tag, source) in listOf("save" to "Speichern", "discard" to "Verwerfen", "continue" to "Zurück")) {
                val action = compose.onNodeWithTag("unsaved-changes-$tag").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertContained(panel, action)
                assertTrue("Touch target remains at least 48 Android dp", action.height / 2f >= 48f - .1f)
                assertTrue("Actions follow the fixed title", action.top >= titleBounds.bottom)
                actionBounds[tag] = action
                val label=ink.last{it.tag=="unsaved-changes-$tag"}
                assertEquals(No5Text.text(language,source),label.text)
                assertEquals(27f*fontScale,label.size,0f);assertEquals(fontScale,label.fontScale,0f)
                assertTrue("Fixed native action must fit without truncation: $label",label.bounds.width()<=label.available+.01f)
            }
            val body = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("unsaved-changes-error")))
            val bodyNode = body.fetchSemanticsNode()
            assertTrue("A long body must really scroll", bodyNode.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0)
            assertTrue(bodyNode.boundsInRoot.top >= titleBounds.bottom)
            assertTrue(bodyNode.boundsInRoot.bottom <= actionBounds.values.minOf { it.top })
            body.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 100000f)) }
            assertEquals("Title must not scroll", titleBounds, title.fetchSemanticsNode().boundsInRoot)
            for ((tag, bounds) in actionBounds) assertEquals("Action must not scroll: $tag", bounds,
                compose.onNodeWithTag("unsaved-changes-$tag").fetchSemanticsNode().boundsInRoot)
            assertEquals("Approved D03 dialog ink",if(redNight)0xffed0000.toInt()else 0xff07121b.toInt(),heading.color)
        }
    }

    @Test fun settledImeInsetsConstrainTheRealDialogAndRestoreItsDraftWithoutMovingKnobClearance() = keyboardCycle(useSpecial = false)
    @Test fun signedSpecialKeyboardIndependentlyConstrainsTheDialogAndRestoresItsDraft() = keyboardCycle(useSpecial = true)

    private fun keyboardCycle(useSpecial: Boolean) {
        launch(1.25f)
        syntheticInsets(true)
        UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), "af")
        val draft = mutableStateOf("retained coordinate draft")
        var dialogView: View? = null
        activity.get().setContent {
            No5ControlTheme(true) {
                No5PanelDialog({}, modifier = Modifier.testTag("editable-panel"),
                    title = { Text(No5Text.ui("Wegpunkt · WGS84 Dezimalgrad"), Modifier.testTag("editable-title")) },
                    text = {
                        dialogView = LocalView.current
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Synthetic body paragraph. ".repeat(250), Modifier.testTag("editable-copy"))
                            OutlinedTextField(draft.value, { draft.value = it }, Modifier.testTag("editable-field"))
                        }
                    }, confirmButton = { No5CompactAction("Speichern und schließen", {}, Modifier.testTag("editable-save")) })
            }
        }
        focusDialogWindow()
        val initial = compose.onNodeWithTag("knob-safe-dialog-content").fetchSemanticsNode().boundsInRoot
        val stage = compose.onNodeWithTag("knob-safe-dialog-window").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("editable-field").performScrollTo().performTextReplacement("edited coordinate draft")
        repeat(2) {
            if (useSpecial) deliverSpecialKeyboard(440) else deliverSettledIme(requireNotNull(dialogView), 440)
            val viewport = compose.onNodeWithTag("knob-safe-dialog-content").fetchSemanticsNode().boundsInRoot
            assertEquals(initial.left, viewport.left, .1f)
            assertEquals(initial.right, viewport.right, .1f)
            assertEquals(initial.top, viewport.top, .1f)
            assertEquals(initial.bottom - 440f, viewport.bottom, .1f)
            assertEquals("IME does not resize or rescale the 2000×1200 stage", stage,
                compose.onNodeWithTag("knob-safe-dialog-window").fetchSemanticsNode().boundsInRoot)
            assertTrue(KnobGeometry.safe(viewport.left, viewport.top, viewport.right, viewport.bottom))
            val panel = compose.onNodeWithTag("editable-panel").fetchSemanticsNode().boundsInRoot
            assertContained(viewport, panel)
            val title = compose.onNodeWithTag("editable-title").assertIsDisplayed()
            val save = compose.onNodeWithTag("editable-save").assertIsDisplayed()
            val fixedTitle = title.fetchSemanticsNode().boundsInRoot
            val fixedSave = save.fetchSemanticsNode().boundsInRoot
            assertContained(viewport, fixedSave)
            assertTextLayout(title, 16f, 1.25f)
            assertTextLayout(compose.onNodeWithText(No5Text.ui("Speichern und schließen"), useUnmergedTree = true), 14f, 1.25f)
            val field = compose.onNodeWithTag("editable-field").performScrollTo().assertTextEquals("edited coordinate draft")
            val fieldBounds = field.fetchSemanticsNode().boundsInRoot
            assertTrue(fieldBounds.top >= fixedTitle.bottom && fieldBounds.bottom <= fixedSave.top)
            assertEquals(fixedTitle, title.fetchSemanticsNode().boundsInRoot)
            assertEquals(fixedSave, save.fetchSemanticsNode().boundsInRoot)
            if (useSpecial) deliverSpecialKeyboard(0) else deliverSettledIme(requireNotNull(dialogView), 0)
            assertEquals(initial, compose.onNodeWithTag("knob-safe-dialog-content").fetchSemanticsNode().boundsInRoot)
            field.assertTextEquals("edited coordinate draft")
        }
    }

    private fun deliverSpecialKeyboard(bottom: Int) {
        compose.runOnIdle {
            com.no5.keyboard.SpecialKeyboardGeometry.publish(activity.get(), activity.get().packageName,
                if (bottom == 0) null else android.graphics.Rect(353, 1200 - bottom, 1656, 1200))
        }
        compose.waitForIdle()
    }

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun assertTextLayout(node: SemanticsNodeInteraction, expectedSp: Float, fontScale: Float) {
        val layout = textLayout(node)
        assertFalse("Fixed text may not overflow vertically", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse("Fixed text may not ellipsize", layout.isLineEllipsized(line))
            assertTrue("Fixed text must fit horizontally", layout.getLineLeft(line) >= -.1f && layout.getLineRight(line) <= layout.size.width + .1f)
        }
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        assertEquals("No auto-shrink repair", expectedSp, layout.layoutInput.style.fontSize.value, 0f)
        assertEquals(2f, layout.layoutInput.density.density, 0f)
        assertEquals(fontScale, layout.layoutInput.density.fontScale, 0f)
    }

    private fun assertContained(outer: Rect, inner: Rect) {
        assertTrue("Inner rectangle must fit: $inner in $outer", inner.left >= outer.left - .1f && inner.right <= outer.right + .1f &&
            inner.top >= outer.top - .1f && inner.bottom <= outer.bottom + .1f)
    }

    private fun syntheticInsets(enabled: Boolean) {
        val type = Class.forName("androidx.compose.foundation.layout.WindowInsetsHolder")
        val companion = type.getField("Companion").get(null)
        companion.javaClass.getMethod("setUseTestInsets", Boolean::class.javaPrimitiveType).invoke(companion, enabled)
    }

    private fun deliverSettledIme(view: View, bottom: Int) {
        compose.runOnIdle {
            val builder = WindowInsetsCompat.Builder()
            if (android.os.Build.VERSION.SDK_INT < 30) builder.setSystemWindowInsets(Insets.of(0, 0, 0, bottom)).setStableInsets(Insets.NONE)
            else builder.setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom)).setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
            val insets = builder.build()
            if (android.os.Build.VERSION.SDK_INT < 30) {
                // Robolectric's API29 animation does not deliver settled typed
                // IME insets. Inject Foundation's real settled-holder state only
                // on this host, as in the existing shell-insets tests.
                val type = Class.forName("androidx.compose.foundation.layout.WindowInsetsHolder")
                val companion = type.getField("Companion").get(null)
                val lookup = companion.javaClass.getDeclaredMethod("getOrCreateFor", View::class.java).apply { isAccessible = true }
                val holder = lookup.invoke(companion, view)
                holder.javaClass.getMethod("update", WindowInsetsCompat::class.java, Int::class.javaPrimitiveType).invoke(holder, insets, 0)
            } else ViewCompat.dispatchApplyWindowInsets(ShadowDialog.getLatestDialog().window!!.decorView, insets)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }
}
