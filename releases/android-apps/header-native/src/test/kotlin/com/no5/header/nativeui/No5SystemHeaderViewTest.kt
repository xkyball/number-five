package com.no5.header.nativeui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import com.no5.header.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual native Canvas and MotionEvent tests, not an HTML screenshot or a model-only renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class No5SystemHeaderViewTest {
    private val now = 120_000L

    private fun publication(pkg: String = HeaderApps.HELM) = HeaderPublication(pkg,
        "fixture-producer", 4, 7, "fixture-client-source-session", 8, 1_000, 4_000)

    private fun measurement(value: Double?, unit: HeaderUnit, ageMs: Long = 1_000,
        quality: HeaderQuality = HeaderQuality.LIVE) = HeaderMeasurement(value, unit,
        "fixture-source", now - ageMs, quality, HeaderProvenance.SIMULATED)

    private fun marine(theme: HeaderTheme = HeaderTheme.DAY) = HeaderSnapshotV1(
        publication(), HeaderPage.AUDIO, theme, HeaderProfile.MARINE_SAFETY,
        HeaderContext("AUDIO", pageStatus = "SIMULATED"),
        HeaderSource("NETWORK SIMULATION", "LIVE · RECEIVING", "fixture-source",
            "fixture-backend-session", HeaderTransport.READY, HeaderQuality.LIVE, HeaderProvenance.SIMULATED),
        now - 1_000,
        HeaderExtras(measurement(0.0, HeaderUnit.RPM), measurement(2_450.0, HeaderUnit.RPM, 8_000),
            measurement(0.0, HeaderUnit.KNOTS)),
    )

    private fun brand(theme: HeaderTheme = HeaderTheme.DAY) = marine(theme).copy(
        pageId = HeaderPage.HELM, profile = HeaderProfile.BRAND_SOURCE,
        context = HeaderContext("EXPLORE FURTHER", "BY WATER"), extras = HeaderExtras())

    private fun map(theme: HeaderTheme = HeaderTheme.DAY) = MapHeaderSnapshotBuilder.build(
        publication(HeaderApps.MAP), HeaderPage.CHART, theme,
        MapHeaderSourceInput("fixture-server", "LIVE · RECEIVING", HeaderTransport.READY,
            "fixture-source", now - 1_000, HeaderQuality.LIVE, HeaderProvenance.SIMULATED, true))

    private fun home(theme: HeaderTheme = HeaderTheme.DAY) = HomeHeaderSnapshotBuilder.build(
        publication(HeaderApps.HOME), theme)

    private fun state(snapshot: HeaderSnapshotV1?) = HeaderConsumerState(snapshot,
        if (snapshot == null) HeaderClearReason.PRODUCER_DEAD else HeaderClearReason.NONE)

    private fun view(snapshot: HeaderSnapshotV1? = marine(), width: Int = 2_000,
        baseTheme: HeaderTheme = HeaderTheme.DAY): No5SystemHeaderView {
        return No5SystemHeaderView(RuntimeEnvironment.getApplication()).apply {
            render(state(snapshot), baseTheme, now)
            size(this, width)
        }
    }

    private fun size(v: View, width: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    private fun bitmap(v: View): Bitmap = Bitmap.createBitmap(v.width, v.height,
        Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    private fun send(v: View, action: Int, x: Float, y: Float): Boolean {
        val event = MotionEvent.obtain(100, 110, action, x, y, 0)
        return try { v.onTouchEvent(event) } finally { event.recycle() }
    }

    private fun tap(v: View, x: Float, y: Float) {
        assertTrue(send(v, MotionEvent.ACTION_DOWN, x, y))
        assertTrue(send(v, MotionEvent.ACTION_UP, x, y))
    }

    private fun text(v: No5SystemHeaderView, id: String) = v.renderModel.texts.first { it.id == id }.value

    @Test fun nativeDayNightFixtureExportHasOriginalContextAndRealPixels() {
        val cases: List<Pair<String, (HeaderTheme) -> HeaderSnapshotV1>> = listOf(
            "home" to ::home, "map" to ::map, "helm" to ::brand, "audio" to ::marine)
        cases.forEach { (name, create) ->
            HeaderTheme.entries.forEach { theme ->
                val v = view(create(theme))
                val b = bitmap(v)
                assertEquals(2_000, b.width); assertEquals(130, b.height)
                assertEquals(listOf(SystemNavigationAction.LOGO_HOME, SystemNavigationAction.BACK,
                    SystemNavigationAction.RECENTS), v.renderModel.navigation.map { it.action })
                assertTrue("Original No5 logo must really be painted", containsColor(b, Color.parseColor(
                    if (theme == HeaderTheme.RED_NIGHT) "#d40000" else "#071022"),
                    Rect(24, 4, 262, 108)))
                assertOpaque(b)
                if (theme == HeaderTheme.RED_NIGHT) assertRedOnly(b)
                assertEquals(theme == HeaderTheme.DAY, v.renderModel.usesCloudBackground)
                export("${name}-${theme.name.lowercase()}", v, b)
                b.recycle(); v.close()
            }
        }
    }

    @Test fun mapDayUsesOriginalBitmapCropAndNightHasNoOceanPixels() {
        val day = view(map()); val night = view(map(HeaderTheme.RED_NIGHT))
        val pixels = bitmap(day)
        val colors = HashSet<Int>()
        for (y in 5 until 95 step 2) for (x in 700 until 1_300 step 2) colors += pixels.getPixel(x, y)
        assertTrue("Native original ocean image should have actual pixel variation", colors.size > 100)
        assertRedOnly(bitmap(night))
        assertEquals("LOKALE SIMULATION", text(day, "source-label"))
        assertEquals("EXPLORE FURTHER", text(day, "context-title"))
        assertEquals("BY WATER", text(day, "context-subtitle"))
        pixels.recycle(); day.close(); night.close()
    }

    @Test fun allAppContextsShareCloudPixelsAndBrandGeometryWithoutReplacingTheirData() {
        val snapshots = listOf(home(), brand(), map(), marine())
        val views = snapshots.map { view(it) }
        val pixels = views.map(::bitmap)
        // Blank center sky has no app text or controls: every native consumer must use one crop.
        for (y in 4 until 108 step 3) for (x in 800 until 1_200 step 3) {
            // AUDIO's safety readouts intentionally own this region.
            assertEquals(pixels[0].getPixel(x, y), pixels[1].getPixel(x, y))
            assertEquals(pixels[0].getPixel(x, y), pixels[2].getPixel(x, y))
        }
        val brands = views.map { it.renderModel.texts.filter { t -> t.id in setOf("brand", "brand-subtitle") } }
        brands.drop(1).forEach { assertEquals(brands.first(), it) }
        assertEquals("NO5 · HOME", text(views[0], "context-title"))
        assertEquals("EXPLORE FURTHER", text(views[1], "context-title"))
        assertEquals("LOKALE SIMULATION", text(views[2], "source-label"))
        assertEquals("0", text(views[3], "port-rpm-value"))
        pixels.forEach { it.recycle() }; views.forEach { it.close() }
    }

    @Test fun halfWidthRendersAtMatchingHeightAndAllThreeActionsUseSameScale() {
        val v = view(map(), 1_000)
        val calls = ArrayList<SystemNavigationAction>()
        v.onSystemNavigationAction = calls::add
        assertEquals(65, v.height)
        tap(v, 65f, 22f); tap(v, 900f, 22f); tap(v, 954f, 22f)
        assertEquals(listOf(SystemNavigationAction.LOGO_HOME, SystemNavigationAction.BACK,
            SystemNavigationAction.RECENTS), calls)
        val b = bitmap(v)
        assertTrue(containsColor(b, Color.parseColor("#071022"), Rect(12, 2, 131, 54)))
        assertOpaque(b)
        export("map-day-half-width", v, b)
        b.recycle(); v.close()
    }

    @Test fun actualZeroIsShownButMissingObservationOrSourceNeverBecomesZero() {
        val s = marine()
        val v = view(s)
        assertEquals("0", text(v, "port-rpm-value")); assertEquals("0.0", text(v, "sog-value"))
        assertEquals("2450*", text(v, "starboard-rpm-value"))
        assertEquals("SIMULATED · STALE", text(v, "starboard-rpm-quality"))
        val missing = s.copy(extras = s.extras.copy(portRpm = s.extras.portRpm!!.copy(sourceObservedAtEpochMs = null),
            sog = s.extras.sog!!.copy(sourceId = null)))
        v.render(state(missing), nowEpochMs = now)
        assertEquals("—", text(v, "port-rpm-value")); assertEquals("—", text(v, "sog-value"))
        assertEquals("—", text(v, "port-rpm-quality"))
        assertEquals(HeaderQuality.UNKNOWN, v.renderModel.fields.first { it.id == "port-rpm" }.quality)
        export("audio-unknown-fields", v, bitmap(v))
        v.close()
    }

    @Test fun ageDegradesOriginalFieldsWithoutRenewingSourceOrPublicationTime() {
        val s = marine().copy(extras = HeaderExtras(measurement(0.0, HeaderUnit.RPM),
            measurement(20.0, HeaderUnit.RPM), measurement(0.0, HeaderUnit.KNOTS)))
        val v = view(s)
        v.render(state(s.copy(publication = s.publication.copy(sequence = 9,
            publishedAtElapsedMs = 2_000))), nowEpochMs = now + 6_000)
        assertEquals("0*", text(v, "port-rpm-value")); assertEquals("0.0*", text(v, "sog-value"))
        assertEquals(now - 1_000, v.renderModel.fields.first().sourceObservedAtEpochMs)
        assertEquals(2_000L, v.renderModel.snapshot!!.publication.publishedAtElapsedMs)
        v.render(state(s), nowEpochMs = now + 31_000)
        assertEquals("—", text(v, "port-rpm-value")); assertEquals("SIMULATED · LOST", text(v, "port-rpm-quality"))
        assertEquals(now - 1_000, v.renderModel.snapshot!!.extras.portRpm!!.sourceObservedAtEpochMs)
        assertEquals(1_000L, v.renderModel.snapshot!!.publication.publishedAtElapsedMs)
        assertEquals(0.0, s.extras.portRpm!!.value!!, 0.0) // Original immutable snapshot still has its real zero.
        v.close()
    }

    @Test fun sourceDetailCannotKeepClaimingLiveAfterAgeLoss() {
        val s = brand()
        val v = view(s)
        assertEquals("LIVE · RECEIVING", text(v, "source-detail"))
        v.render(state(s), nowEpochMs = now + 6_000)
        assertEquals("STALE · RECEIVING", text(v, "source-detail"))
        v.render(state(s), nowEpochMs = now + 31_000)
        assertEquals("LOST", text(v, "source-detail"))
        assertEquals(now - 1_000, v.renderModel.snapshot!!.sourceObservedAtEpochMs)
        val legacy = map().copy(sourceObservedAtEpochMs = null,
            source = map().source.copy(quality = HeaderQuality.UNKNOWN, detail = "Kein Helm-Snapshot"))
        v.render(state(legacy), nowEpochMs = now)
        assertEquals("Kein Helm-Snapshot", text(v, "source-detail"))
        assertEquals(HeaderQuality.UNKNOWN, v.renderModel.snapshot!!.source.quality)
        v.close()
    }

    @Test fun terminalQualityAndAbsentExtrasRemainUnknownInsteadOfInventedData() {
        val v = view()
        listOf(HeaderQuality.UNKNOWN, HeaderQuality.LOST, HeaderQuality.INVALID, HeaderQuality.UNAVAILABLE).forEach { q ->
            val s = marine().copy(extras = HeaderExtras(measurement(0.0, HeaderUnit.RPM, quality = q)))
            v.render(state(s), nowEpochMs = now)
            assertEquals("—", text(v, "port-rpm-value")); assertEquals("—", text(v, "starboard-rpm-value"))
            assertEquals("—", text(v, "sog-value")); assertEquals("—", text(v, "sog-unit"))
        }
        v.close()
    }

    @Test fun nullSnapshotClearsContextAndExtrasWhileBrandAndSystemActionsSurvive() {
        val v = view()
        v.render(state(null), HeaderTheme.RED_NIGHT, now)
        assertEquals(HeaderProfile.BASE_SYSTEM, v.renderModel.profile)
        assertTrue(v.renderModel.fields.isEmpty())
        assertEquals(listOf("brand", "brand-subtitle"), v.renderModel.texts.map { it.id })
        assertEquals("NO 5", text(v, "brand")); assertEquals("STELLENBOSCH", text(v, "brand-subtitle"))
        assertEquals(HeaderClearReason.PRODUCER_DEAD, v.renderModel.clearReason)
        val calls = ArrayList<SystemNavigationAction>(); v.onSystemNavigationAction = calls::add
        tap(v, 130f, 44f); tap(v, 1_800f, 44f); tap(v, 1_908f, 44f)
        assertEquals(listOf(3, 4, 187), calls.map { it.keyCode })
        val b = bitmap(v); assertRedOnly(b); export("base-cleared-night", v, b)
        b.recycle(); v.close()
    }

    @Test fun navigationFiresOnlyOnMatchingUpAndBoundaryHasOneOwner() {
        val v = view()
        val calls = ArrayList<SystemNavigationAction>(); v.onSystemNavigationAction = calls::add
        assertTrue(send(v, MotionEvent.ACTION_DOWN, 130f, 44f)); assertTrue(calls.isEmpty())
        assertTrue(send(v, MotionEvent.ACTION_UP, 130f, 44f)); assertEquals(listOf(SystemNavigationAction.LOGO_HOME), calls)
        assertFalse(send(v, MotionEvent.ACTION_UP, 130f, 44f)); assertEquals(1, calls.size)
        tap(v, 1_852f, 44f); assertEquals(SystemNavigationAction.RECENTS, calls.last())
        tap(v, 1_851.9f, 44f); assertEquals(SystemNavigationAction.BACK, calls.last())
        listOf(262f to 44f, 130f to 108f, 1_963f to 44f, -1f to 44f, 130f to -1f).forEach { (x, y) ->
            assertFalse(send(v, MotionEvent.ACTION_DOWN, x, y))
            assertFalse(send(v, MotionEvent.ACTION_UP, x, y))
        }
        assertEquals(3, calls.size)
        v.close()
    }

    @Test fun movingOutCrossingControlsCancellationAndSecondPointerNeverNavigate() {
        val v = view()
        val calls = ArrayList<SystemNavigationAction>(); v.onSystemNavigationAction = calls::add
        send(v, MotionEvent.ACTION_DOWN, 130f, 44f)
        send(v, MotionEvent.ACTION_MOVE, 600f, 44f)
        send(v, MotionEvent.ACTION_MOVE, 130f, 44f)
        send(v, MotionEvent.ACTION_UP, 130f, 44f)
        send(v, MotionEvent.ACTION_DOWN, 1_800f, 44f)
        send(v, MotionEvent.ACTION_UP, 1_908f, 44f)
        send(v, MotionEvent.ACTION_DOWN, 130f, 44f)
        send(v, MotionEvent.ACTION_CANCEL, 130f, 44f)
        send(v, MotionEvent.ACTION_UP, 130f, 44f)
        send(v, MotionEvent.ACTION_DOWN, 130f, 44f)
        val props = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(2) { i -> MotionEvent.PointerCoords().apply { x = 130f + i * 5; y = 44f; pressure = 1f; size = 1f } }
        val multi = MotionEvent.obtain(100, 110, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { assertTrue(v.onTouchEvent(multi)) } finally { multi.recycle() }
        send(v, MotionEvent.ACTION_UP, 130f, 44f)
        assertTrue(calls.isEmpty())
        v.close()
    }

    @Test fun pageProducerForegroundSourceThemeAndClearTransitionsDiscardHeldGesture() {
        val original = marine()
        val changed = listOf(
            original.copy(publication = original.publication.copy(pageGeneration = 8)),
            original.copy(publication = original.publication.copy(producerSession = "next-fixture-producer")),
            original.copy(publication = original.publication.copy(foregroundEpoch = 5)),
            original.copy(publication = original.publication.copy(sourceSessionId = "next-fixture-source")),
            original.copy(pageId = HeaderPage.SONAR, context = HeaderContext("SONAR")),
            original.copy(theme = HeaderTheme.RED_NIGHT),
            home(), null,
        )
        changed.forEach { replacement ->
            val v = view(original); val calls = ArrayList<SystemNavigationAction>(); v.onSystemNavigationAction = calls::add
            send(v, MotionEvent.ACTION_DOWN, 130f, 44f)
            v.render(state(replacement), nowEpochMs = now)
            send(v, MotionEvent.ACTION_UP, 130f, 44f)
            assertTrue(calls.isEmpty()); v.close()
        }
    }

    @Test fun ordinaryTelemetryUpdatesKeepHeldSystemGestureValid() {
        val s = marine(); val v = view(s); val calls = ArrayList<SystemNavigationAction>()
        v.onSystemNavigationAction = calls::add
        send(v, MotionEvent.ACTION_DOWN, 1_800f, 44f)
        v.render(state(s.copy(publication = s.publication.copy(sequence = 9),
            extras = s.extras.copy(portRpm = measurement(50.0, HeaderUnit.RPM)))), nowEpochMs = now)
        send(v, MotionEvent.ACTION_UP, 1_800f, 44f)
        assertEquals(listOf(SystemNavigationAction.BACK), calls)
        assertEquals("50", text(v, "port-rpm-value"))
        v.close()
    }

    @Test fun resizeFocusLossDisableCallbackChangeAndCloseDiscardHeldGesture() {
        val v = view(); val calls = ArrayList<SystemNavigationAction>(); v.onSystemNavigationAction = calls::add
        send(v, MotionEvent.ACTION_DOWN, 130f, 44f); size(v, 1_000); send(v, MotionEvent.ACTION_UP, 65f, 22f)
        send(v, MotionEvent.ACTION_DOWN, 65f, 22f); v.onWindowFocusChanged(false); send(v, MotionEvent.ACTION_UP, 65f, 22f)
        send(v, MotionEvent.ACTION_DOWN, 65f, 22f); v.isEnabled = false; v.isEnabled = true; send(v, MotionEvent.ACTION_UP, 65f, 22f)
        send(v, MotionEvent.ACTION_DOWN, 65f, 22f); v.onSystemNavigationAction = calls::add; send(v, MotionEvent.ACTION_UP, 65f, 22f)
        send(v, MotionEvent.ACTION_DOWN, 65f, 22f); v.close(); send(v, MotionEvent.ACTION_UP, 65f, 22f)
        assertTrue(calls.isEmpty())
        assertFalse(send(v, MotionEvent.ACTION_DOWN, 65f, 22f))
    }

    @Test fun accessibilityHasExactlyThreeNativeActionsAndMatchingHalfWidthBounds() {
        val v = view(null, 1_000); val calls = ArrayList<SystemNavigationAction>(); v.onSystemNavigationAction = calls::add
        val provider = v.accessibilityNodeProvider
        val host = provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID)!!
        assertEquals(3, host.childCount)
        val back = provider.createAccessibilityNodeInfo(2)!!
        val bounds = Rect(); back.getBoundsInParent(bounds)
        assertEquals(Rect(870, 2, 926, 54), bounds)
        assertEquals("Zurück", back.contentDescription.toString())
        (1..3).forEach { assertTrue(provider.performAction(it, AccessibilityNodeInfo.ACTION_CLICK, null)) }
        assertEquals(listOf(SystemNavigationAction.LOGO_HOME, SystemNavigationAction.BACK, SystemNavigationAction.RECENTS), calls)
        assertFalse(provider.performAction(4, AccessibilityNodeInfo.ACTION_CLICK, null))
        v.isEnabled = false; assertFalse(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        host.recycle(); back.recycle(); v.close()
    }

    private fun containsColor(bitmap: Bitmap, color: Int, bounds: Rect): Boolean {
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right)
            if (bitmap.getPixel(x, y) == color) return true
        return false
    }

    private fun assertOpaque(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Header must cover its full measured surface", pixels.all { Color.alpha(it) == 255 })
    }

    private fun assertRedOnly(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("RED_NIGHT must not leak blue/day/ocean pixels", pixels.all { Color.green(it) == 0 && Color.blue(it) == 0 })
    }

    /** Exported images are SYNTHETIC fixtures from the actual native View, never backend evidence. */
    private fun export(name: String, v: No5SystemHeaderView, b: Bitmap) {
        val output = File(System.getProperty("no5.headerNative.outputDir")
            ?: System.getenv("NO5_HEADER_NATIVE_FIXTURES")
            ?: "/Users/chris/projects/lumina/app-candidates/no5-control-v2-20261010/header-native/verification/rendered")
        check(output.isDirectory || output.mkdirs()) { "Cannot create native header fixture output" }
        val stem = "$name-api${Build.VERSION.SDK_INT}"
        File(output, "$stem.png").outputStream().use { check(b.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val m = v.renderModel
        val json = JSONObject().put("kind", "SYNTHETIC_NATIVE_VIEW_FIXTURE")
            .put("androidApi", Build.VERSION.SDK_INT).put("widthPx", v.width).put("heightPx", v.height)
            .put("designWidth", 2000).put("designHeight", 130).put("scale", No5HeaderGeometry.scale(v.width))
            .put("theme", m.theme.name).put("profile", m.profile.name).put("usesCloudBackground", m.usesCloudBackground)
            .put("clearReason", m.clearReason.name)
        json.put("texts", JSONArray().apply { m.texts.forEach { t -> put(JSONObject()
            .put("id", t.id).put("value", t.value).put("x", t.x).put("baseline", t.baseline)
            .put("size", t.size).put("font", t.font.name).put("stretch", t.scaleX)
            .put("maxWidth", if (t.maxWidth == Float.MAX_VALUE) JSONObject.NULL else t.maxWidth)
            .put("align", t.align.name).put("renderer", t.renderer.name)) } })
        json.put("fields", JSONArray().apply { m.fields.forEach { f -> put(JSONObject()
            .put("id", f.id).put("displayed", f.displayed).put("quality", f.quality.name)
            .put("sourceId", f.sourceId ?: JSONObject.NULL)
            .put("originalObservedAtEpochMs", f.sourceObservedAtEpochMs ?: JSONObject.NULL)) } })
        json.put("navigation", JSONArray().apply { m.navigation.forEach { n ->
            val r = n.bounds; val scale = No5HeaderGeometry.scale(v.width)
            put(JSONObject().put("action", n.action.name).put("originalKeyCode", n.action.keyCode)
                .put("description", n.description).put("virtualId", n.virtualId)
                .put("designBoundsHalfOpen", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
                .put("actualTouchBoundsHalfOpen", JSONArray(listOf(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale))))
        } })
        m.snapshot?.let { s -> json.put("sourceObservedAtEpochMs", s.sourceObservedAtEpochMs ?: JSONObject.NULL)
            .put("publishedAtElapsedMs", s.publication.publishedAtElapsedMs).put("fixtureSequence", s.publication.sequence) }
        File(output, "$stem.json").writeText(json.toString(2) + "\n")
    }
}
