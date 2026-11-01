package com.no5.helm

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** One real Android Dialog integration, not another locale/font matrix or real route save. */
class UiCleanupReleaseAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var identity: JSONObject
    private val gate = object : ExternalResource() {
        override fun before() {
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue("Explicit bounded UI-cleanup release acceptance only",
                arguments.getString("no5.uiCleanup.native") == "true")
            identity = UiCleanupReleaseFixture.identity(instrumentation.targetContext,
                instrumentation.context, arguments)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(gate).around(ui)

    private fun pixels(tag: String): IntArray {
        val image = ui.onNodeWithTag(tag).captureToImage()
        return IntArray(image.width * image.height).also { image.readPixels(it) }
    }
    private fun outsideTap() = ui.onNodeWithTag("knob-safe-dialog-window")
        .performTouchInput { click(Offset(4f, 4f)) }
    private fun actions() = listOf("unsaved-changes-save", "unsaved-changes-discard", "unsaved-changes-continue")
    private fun actionBounds() = actions().map { ui.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }

    @Test fun actualDialogCancelsTransientPressAndKeepsFailedSaveDraftUntilMatchingAcknowledgment() {
        val report = JSONObject(identity.toString()).put("schema", "no5.ui-cleanup-release-dialog/1")
            .put("scope", "Actual separate Android Dialog + synthetic reducer state; no real route persistence")
            .put("status", "IN_PROGRESS").put("phase", "WAIT_REAL_APP")
        var fixture: UiCleanupReleaseFixture? = null
        var failure: Throwable? = null
        try {
            ui.waitUntil(60_000) {
                ui.onAllNodesWithTag("no5-shell").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            assertTrue("Do not overlap another app-owned Dialog", ui.onAllNodesWithTag("knob-safe-dialog-window")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty())
            val originalRoots = ui.runOnIdle { UiCleanupReleaseFixture.roots() }
            ui.runOnIdle { fixture = UiCleanupReleaseFixture(ui.activity).also { it.mount() } }
            ui.waitUntil(3_000) { ui.onAllNodesWithTag("unsaved-changes-dialog")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1 }
            val window = ui.runOnIdle {
                UiCleanupReleaseFixture.roots().filter { current -> originalRoots.none { it === current } }.single()
            }
            ui.waitUntil(3_000) { window.isAttachedToWindow && window.isShown && window.hasWindowFocus() }
            assertFalse("Dialog owns real window focus", ui.activity.window.decorView.hasWindowFocus())
            report.put("separateAttachedFocusedDialogWindow", true).put("phase", "TRANSIENT_PRESS")
            val neutral = pixels("unsaved-changes-save")
            ui.onNodeWithTag("unsaved-changes-save").performTouchInput { down(center) }
            ui.waitUntil(2_000) { !neutral.contentEquals(pixels("unsaved-changes-save")) }
            report.put("actualPressedPixelsDifferFromCurrentNeutral", true)
            ui.onNodeWithTag("unsaved-changes-save").performTouchInput { cancel() }
            ui.waitUntil(2_000) { neutral.contentEquals(pixels("unsaved-changes-save")) }
            assertEquals(0, ui.runOnIdle { fixture!!.saveCallbacks })
            ui.onNodeWithTag("unsaved-changes-save").performTouchInput {
                down(center); moveTo(Offset(-20f, center.y)); up()
            }
            ui.waitForIdle()
            assertArrayEquals("Cancelled/drag-out press returns exact current neutral pixels", neutral,
                pixels("unsaved-changes-save"))
            assertEquals(0, ui.runOnIdle { fixture!!.saveCallbacks })
            assertTrue(ui.runOnIdle { fixture!!.originalDraftRetained() })
            report.put("cancelAndDragOutClearPressWithoutSave", true).put("phase", "BUSY_SAVE")
            ui.onNodeWithTag("unsaved-changes-save").performTouchInput { click(center) }
            ui.waitForIdle()
            assertNotNull(ui.runOnIdle { fixture!!.snapshot().saveRequest })
            assertEquals(1, ui.runOnIdle { fixture!!.saveCallbacks })
            actions().forEach { ui.onNodeWithTag(it).assertIsNotEnabled() }
            repeat(2) { outsideTap() }
            ui.onNodeWithTag("unsaved-changes-dialog").assertExists()
            assertEquals(1, ui.runOnIdle { fixture!!.saveCallbacks })
            assertTrue(ui.runOnIdle { fixture!!.originalDraftRetained() && fixture!!.snapshot().dirty })
            report.put("busyOutsideTapsCannotDiscardOrDuplicateSave", true).put("phase", "INJECTED_SAVE_FAILURE")
            ui.runOnIdle { fixture!!.failPendingSave() }
            ui.waitForIdle()
            ui.onNodeWithTag("unsaved-changes-dialog").assertIsDisplayed()
            val error = ui.onNodeWithTag("unsaved-changes-error").assertExists()
            assertNull(ui.runOnIdle { fixture!!.snapshot().saveRequest })
            assertTrue(ui.runOnIdle { fixture!!.originalDraftRetained() && fixture!!.snapshot().dirty })
            val injectedReason = ui.runOnIdle { checkNotNull(fixture!!.injectedFailureReason) }
            val errorText = error.fetchSemanticsNode().config[SemanticsProperties.Text]
                .joinToString(separator = "") { it.text }
            val errorLayouts = mutableListOf<TextLayoutResult>()
            error.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(errorLayouts)) }
            assertEquals(1, errorLayouts.size)
            val expectedLines = injectedReason.count { it == '\n' } + 1
            report.put("syntheticLongError", JSONObject()
                .put("injectedReasonCharacters", injectedReason.length)
                .put("explicitInputLines", expectedLines)
                .put("actualTextLayoutLines", errorLayouts.single().lineCount)
                .put("actualTextLayoutHeightPx", errorLayouts.single().size.height)
                .put("completeInjectedReasonPresent", errorText.endsWith(injectedReason)))
            assertTrue("Production semantics retains the complete synthetic failure reason", errorText.endsWith(injectedReason))
            assertTrue("The actual production Text lays out every explicit input line",
                errorLayouts.single().lineCount >= expectedLines)
            val fixedActions = actionBounds()
            val body = ui.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("unsaved-changes-error")))
            val bodyBeforeScroll = body.fetchSemanticsNode()
            val maximumScroll = bodyBeforeScroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
            report.getJSONObject("syntheticLongError")
                .put("actualBodyViewportHeightPx", bodyBeforeScroll.boundsInRoot.height)
                .put("actualMaximumScrollPx", maximumScroll)
            assertTrue("Injected long body actually overflows its bounded viewport",
                maximumScroll > 0f)
            body.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 100_000f)) }
            ui.waitForIdle()
            assertTrue("The real production body actually scrolled",
                body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
            assertEquals("Long error body cannot scroll fixed actions away", fixedActions, actionBounds())
            report.put("syntheticSaveFailureRetainsDraftAndDialog", true)
                .put("longErrorKeepsRealWindowActionsReachable", true).put("phase", "MATCHING_SYNTHETIC_ACK")
            ui.onNodeWithTag("unsaved-changes-save").performTouchInput { click(center) }
            ui.waitForIdle()
            assertEquals(2, ui.runOnIdle { fixture!!.saveCallbacks })
            val pending = ui.runOnIdle { fixture!!.snapshot().saveRequest }
            assertNotNull(pending)
            ui.runOnIdle { fixture!!.acknowledgePendingSave(matching = false) }
            ui.waitForIdle()
            assertEquals("An unrelated ACK cannot complete the pending request", pending,
                ui.runOnIdle { fixture!!.snapshot().saveRequest })
            assertTrue(ui.runOnIdle { fixture!!.originalDraftRetained() && fixture!!.snapshot().dirty })
            ui.onNodeWithTag("unsaved-changes-dialog").assertIsDisplayed()
            report.put("unmatchingSyntheticAcknowledgmentPreservesPendingDraft", true)
            ui.runOnIdle { fixture!!.acknowledgePendingSave() }
            ui.waitUntil(3_000) { ui.onAllNodesWithTag("unsaved-changes-dialog")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() }
            assertFalse(ui.runOnIdle { fixture!!.snapshot().dirty })
            assertNull(ui.runOnIdle { fixture!!.snapshot().draft })
            ui.waitUntil(3_000) { !window.isAttachedToWindow }
            report.put("onlyMatchingSyntheticAcknowledgmentCloses", true)
                .put("status", "PASS_SCOPED_NATIVE_DIALOG_INTEGRATION")
                .put("realPersistenceOrNetworkSaveAccepted", false)
                .put("hardwarePulseOrAudioVolumeAcceptance", "NOT_RUN")
                .put("physicalLocaleThemeFontCartesianAcceptance", "NOT_RUN_NOT_REQUIRED_BY_THIS_CHECK")
                .put("gpuDisplayLatencyOrFps", "NOT_MEASURED")
        } catch (error: Throwable) {
            failure = error
            report.put("status", "FAIL").put("failureType", error.javaClass.name)
            throw error
        } finally {
            try {
                ui.runOnIdle { fixture?.close() }
                report.put("testOnlyOverlayRemoved", true)
                UiCleanupReleaseFixture.write(instrumentation.targetContext, report)
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            }
        }
    }
}
