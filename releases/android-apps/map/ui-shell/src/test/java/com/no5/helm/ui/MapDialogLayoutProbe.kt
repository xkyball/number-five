package com.no5.helm.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Tests actual production body layout; no reference images or measurement estimates. */
internal class MapDialogLayoutProbe(
    private val compose: ComposeTestRule,
    private val activity: Activity,
    private val outputCase: String,
    private val language: String,
    private val fontScale: Float,
    private val redNight: Boolean,
) {
    private val measurements = linkedMapOf<String, JSONObject>()

    fun verify(title: String, fixedActionLabels: List<String>, allowedDynamicTexts: Set<String> = emptySet(), expectedTitleSp: Float = 16f): JSONObject {
        focusHostInputWindow(activity, compose)
        val titleNode = compose.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().last()
        val root = ancestors(titleNode).first { it.config.getOrNull(SemanticsProperties.TestTag) == "knob-safe-dialog-content" }
        val viewport = root.boundsInRoot
        assertTrue("Dialog is in calibrated knob corridor: $outputCase", authoritativeKnobSafe(viewport))
        val titleBounds = titleNode.boundsInRoot
        assertText(titleNode, emptySet(), expectedTitleSp)
        val actions = fixedActionLabels.associateWith { label ->
            val text = nodes(root.id).last { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
            assertText(text, emptySet(), 14f)
            val action = ancestors(text).first { it.config.contains(SemanticsActions.OnClick) }
            assertContained(viewport, action.boundsInRoot)
            assertTrue("Fixed action is a full48dp target: $label / ${action.boundsInRoot}", action.boundsInRoot.height >= 96f && action.boundsInRoot.width >= 96f)
            action.id to action.boundsInRoot
        }
        for ((_, action) in actions.values) assertTrue(titleBounds.bottom <= action.top)
        collectText(root.id, allowedDynamicTexts)
        val scrollers = nodes(root.id).filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy) }.map { it.id }.distinct()
        var scrollSteps = 0
        for (id in scrollers) {
            var steps = 0
            while (steps++ < 64) {
                val current = node(id) ?: break
                val range = current.config[SemanticsProperties.VerticalScrollAxisRange]
                if (range.value() >= range.maxValue()) break
                compose.onNode(SemanticsMatcher("exact scroller$id") { it.id == id }, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, (current.boundsInRoot.height * .8f).coerceAtLeast(80f)) }
                compose.waitForIdle()
                scrollSteps++
                collectText(root.id, allowedDynamicTexts)
                assertEquals("Title cannot scroll with the body: $outputCase", titleBounds, node(titleNode.id)!!.boundsInRoot)
                for ((label, pair) in actions) assertEquals("Fixed action cannot scroll: $label", pair.second, node(pair.first)!!.boundsInRoot)
            }
            val current = node(id) ?: continue
            val range = current.config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("Every body must reach its end within bounded scroll steps: $outputCase", range.value() >= range.maxValue())
        }
        val dialog = ShadowDialog.getShownDialogs().last { it.isShowing }
        val view = checkNotNull(dialog.window).decorView
        var imageSha = ""
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val bytes = ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
                imageSha = sha256(bytes)
                if (language == "af" && fontScale == 1.25f && redNight) outputDirectory()?.let { directory ->
                    File(directory, "$outputCase-$language-$fontScale-night.png").writeBytes(bytes)
                }
            } finally { bitmap.recycle() }
        }
        val result = JSONObject().put("schema", "no5.actual-map-dialog-body-layout/1").put("case", outputCase)
            .put("language", language).put("fontScale", fontScale).put("redNight", redNight)
            .put("status", "PASS").put("actualTextLayouts", JSONArray(measurements.values.toList()))
            .put("scrollers", scrollers.size).put("scrollSteps", scrollSteps).put("nativeCanvasDrawn", true)
            .put("nativePngSha256", imageSha).put("physicalDevice", "NOT_RUN")
        outputDirectory()?.let { File(it, "$outputCase-$language-$fontScale-${if(redNight)"night"else"day"}.json").writeText(result.toString(2)+"\n") }
        return result
    }

    private fun collectText(rootId: Int, allowedDynamicTexts: Set<String>) {
        for (node in nodes(rootId)) if (node.config.contains(SemanticsActions.GetTextLayoutResult) && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f) {
            assertText(node, allowedDynamicTexts)
        }
    }

    private fun assertText(node: SemanticsNode, allowedDynamicTexts: Set<String>, expectedSp: Float? = null) {
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        for (layout in results) {
            val text = layout.layoutInput.text.text
            assertEquals("No design-density override: $outputCase", 2f, layout.layoutInput.density.density, 0f)
            assertEquals("Accessibility scale remains active: $outputCase", fontScale, layout.layoutInput.density.fontScale, 0f)
            if (expectedSp != null) assertEquals("No font shrinking: $text", expectedSp, layout.layoutInput.style.fontSize.value, 0f)
            else assertTrue("Only unchanged No5ControlTheme font tokens: $text", layout.layoutInput.style.fontSize.value in setOf(11f,12f,14f,16f,20f))
            val editable = node.config.contains(SemanticsActions.SetText) || ancestors(node).any { it.config.contains(SemanticsActions.SetText) }
            val horizontallyAccessible = text in allowedDynamicTexts && ancestors(node).any {
                it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue()?.let { maximum -> maximum > 0f } == true
            }
            if (!editable && !horizontallyAccessible) {
                assertFalse("Text may not be vertically truncated: $outputCase / $text", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) {
                    assertFalse("Fixed text may not ellipsize: $outputCase / $text", layout.isLineEllipsized(line))
                    assertTrue("Fixed glyphs must fit: $outputCase / $text", layout.getLineLeft(line) >= -.1f && layout.getLineRight(line) <= layout.size.width + .1f)
                }
                if (layout.lineCount > 0) assertTrue("Full fixed text must be retained: $outputCase / $text",
                    layout.getLineEnd(layout.lineCount-1, visibleEnd=true) >= text.trimEnd().length)
            }
            val key = text+"|"+layout.layoutInput.style.fontSize+"|"+layout.size
            measurements[key] = JSONObject().put("text", text).put("fontSp", layout.layoutInput.style.fontSize.value)
                .put("lineCount", layout.lineCount).put("widthPx", layout.size.width).put("heightPx", layout.size.height)
                .put("dynamicAllowed", text in allowedDynamicTexts).put("editable", editable)
        }
    }

    private fun nodes(rootId: Int) = compose.onAllNodes(SemanticsMatcher("dialog subtree$rootId") { candidate ->
        candidate.id == rootId || ancestors(candidate).any { it.id == rootId }
    }, useUnmergedTree = true).fetchSemanticsNodes()
    private fun node(id: Int) = compose.onAllNodes(SemanticsMatcher("exact node$id") { it.id == id }, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
    private fun ancestors(node: SemanticsNode) = generateSequence(node.parent) { it.parent }
    private fun assertContained(outer: Rect, inner: Rect) = assertTrue("Actual target remains in dialog viewport", inner.left >= outer.left && inner.right <= outer.right && inner.top >= outer.top && inner.bottom <= outer.bottom)
    private fun outputDirectory() = System.getProperty("no5.dialogEvidenceDir")?.let { File(it).apply { mkdirs() } }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun authoritativeKnobSafe(bounds: Rect): Boolean {
        // The geometry belongs to native-screens and is Kotlin-internal. Read
        // its actual implementation; do not duplicate or widen its constants.
        val type = Class.forName("com.no5.preparation.ui.KnobGeometry")
        val owner = type.getField("INSTANCE").get(null)
        val safe = type.methods.single { it.name.startsWith("safe") && it.parameterCount == 4 }
        return safe.invoke(owner, bounds.left, bounds.top, bounds.right, bounds.bottom) as Boolean
    }
}
