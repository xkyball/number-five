package com.no5.header.nativeui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import com.no5.header.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Lifecycle tests have no native metrics; native pixel/geometry tests remain in NATIVE mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class No5HeaderDeferredMetricsTest {
    @Test fun unattachedServiceHeaderCanOwnAndClearModelsWithoutPretendingToDrawRpm() {
        val view = No5SystemHeaderView(RuntimeEnvironment.getApplication())
        assertFalse(view.isAttachedToWindow)
        assertNull(view.onSystemNavigationAction)
        assertNull(view.renderModel.snapshot)
        val snapshot = HeaderSnapshotV1(HeaderPublication(HeaderApps.HELM, "fixture", 1, 1,
            "fixture-source-session", 1, 1_000, 4_000), HeaderPage.AUDIO, HeaderTheme.DAY,
            HeaderProfile.MARINE_SAFETY, HeaderContext("AUDIO"), extras = HeaderExtras(
                portRpm = HeaderMeasurement(0.0, HeaderUnit.RPM, "fixture", 10_000,
                    HeaderQuality.LIVE, HeaderProvenance.SIMULATED)))
        view.render(HeaderConsumerState(snapshot, HeaderClearReason.NONE), nowEpochMs = 10_000)
        assertEquals("0", view.renderModel.fields.first { it.id == "port-rpm" }.displayed)
        assertFalse(view.isAttachedToWindow)
        view.render(HeaderConsumerState(null, HeaderClearReason.PRODUCER_DEAD), HeaderTheme.RED_NIGHT, 10_000)
        assertTrue(view.renderModel.fields.isEmpty())
        assertNull(view.renderModel.snapshot)
        view.close()
    }

    @Test fun legacyCannotSupplyRealGlyphBoundsAndActualRpmDrawingStillFailsStrictly() {
        val app = RuntimeEnvironment.getApplication()
        val font = Typeface.createFromAsset(app.assets, "no5-header/barlow_condensed_extrabold.ttf")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font; textSize = 44f; textScaleX = 1.16f
        }
        val bounds = Rect()
        paint.getTextBounds("0", 0, 1, bounds)
        // The installed Robolectric 4.15.1 Legacy ShadowPaint uses string length and leaves
        // native outline bounds absent. This is diagnostic fixture evidence, not a font repair.
        assertEquals(1.16f, paint.measureText("0"), 0f)
        assertEquals(0, bounds.width())
        assertThrows(IllegalArgumentException::class.java) { NativeRpmReadout(font) }

        val view = No5SystemHeaderView(app)
        view.render(HeaderConsumerState(HeaderSnapshotV1(HeaderPublication(HeaderApps.HELM,
            "fixture", 1, 1, null, 1, 1_000, 4_000), HeaderPage.AUDIO, HeaderTheme.DAY,
            HeaderProfile.MARINE_SAFETY, HeaderContext("AUDIO")), HeaderClearReason.NONE), nowEpochMs = 10_000)
        view.measure(View.MeasureSpec.makeMeasureSpec(2_000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(130, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 2_000, 130)
        val bitmap = Bitmap.createBitmap(2_000, 130, Bitmap.Config.ARGB_8888)
        try {
            assertThrows(IllegalArgumentException::class.java) { view.draw(Canvas(bitmap)) }
        } finally { bitmap.recycle(); view.close() }
    }
}
