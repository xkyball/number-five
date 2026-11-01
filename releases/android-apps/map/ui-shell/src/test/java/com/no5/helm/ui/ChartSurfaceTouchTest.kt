package com.no5.helm.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.no5.helm.chart.ChartViewport
import com.no5.helm.chart.ChartSurfaceGeometry
import com.no5.helm.chart.ScreenPoint
import com.no5.helm.chart.ChartMotion
import com.no5.helm.chart.ChartBufferTransformMode
import com.no5.helm.chart.ChartBufferSnapshot
import com.no5.helm.chart.ChartFrame
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.*

/** Real Android MotionEvents dispatched through a parent View to the actual chart SurfaceView. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChartSurfaceTouchTest {
    private lateinit var view: ChartSurfaceView
    private lateinit var parent: FrameLayout
    private val home = ChartViewport(42.36, -71.04, 0.15, width = 1000, height = 600)
    private val updates = mutableListOf<ChartViewport>()
    private val taps = mutableListOf<ScreenPoint>()
    private var time = 1000L

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        view = ChartSurfaceView(context).apply {
            viewport = home
            onViewport = { updates += it }
            onTap = { taps += it }
        }
        parent = FrameLayout(context).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) }
        parent.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, 1000, 600)
        updates.clear()
    }

    private fun coordinates(p: TouchPoint) = MotionEvent.PointerCoords().apply {
        x = p.x.toFloat(); y = p.y.toFloat(); pressure = 1f; size = 1f
    }

    private fun event(action: Int, points: List<TouchPoint>): MotionEvent {
        time += 16
        return MotionEvent.obtain(1000, time, action, points.size,
            points.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray(),
            points.map(::coordinates).toTypedArray(), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun send(action: Int, vararg points: TouchPoint) {
        event(action, points.toList()).let { e ->
            try { assertTrue("Chart must own the touch stream from DOWN", parent.dispatchTouchEvent(e)) }
            finally { e.recycle() }
        }
    }

    private fun p(id: Int = 7, x: Double = 500.0, y: Double = 300.0) = TouchPoint(id, x, y)
    private fun twoDown(left: Double = 400.0, right: Double = 600.0) {
        send(MotionEvent.ACTION_DOWN, p(x = left))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), p(x = left), p(19, right))
    }
    private fun twoUp(left: Double, right: Double) {
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), p(x = left), p(19, right))
        send(MotionEvent.ACTION_UP, p(x = left))
    }

    @Test fun boundedPreviewNeverOvershootsAndCanImmediatelyReverseAtTheEdge() {
        val bounds=com.no5.helm.chart.ChartNavigationBounds(1,listOf(com.no5.helm.chart.ChartCellExtent("A",42.0,43.0,-72.0,-70.0)))
        val region=com.no5.helm.chart.ChartCameraBounds.from(bounds)!!
        view.onTouchBegin={true};val cameras=mutableListOf<ChartViewport>();view.onTouchViewport={cameras+=it}
        view.admitCamera={basis,wanted,_,_->region.constrain(wanted,basis).effective}
        send(MotionEvent.ACTION_DOWN,p())
        send(MotionEvent.ACTION_MOVE,p(x=100000.0))
        val edge=view.viewport;assertEquals(edge,region.constrain(edge).effective)
        send(MotionEvent.ACTION_MOVE,p(x=99950.0))
        assertNotEquals(edge,view.viewport)
        assertTrue(cameras.all{region.constrain(it).effective==it})
        send(MotionEvent.ACTION_UP,p(x=99950.0))
    }
    @Test fun realPinchAndRotationKeepPanIntentOffUntilALaterTranslationOfTheSameGesture() {
        var gestures=0;var pans=0
        view.onGestureStarted={gestures++};view.onPanStarted={pans++}
        twoDown()
        send(MotionEvent.ACTION_MOVE,p(x=350.0),p(19,650.0))
        assertEquals(1,gestures);assertEquals(0,pans)
        send(MotionEvent.ACTION_MOVE,p(x=500.0,y=150.0),p(19,500.0,450.0))
        assertEquals(1,gestures);assertEquals(0,pans)
        send(MotionEvent.ACTION_MOVE,p(x=540.0,y=170.0),p(19,540.0,470.0))
        assertEquals(1,gestures);assertEquals(1,pans)
        send(MotionEvent.ACTION_MOVE,p(x=550.0,y=180.0),p(19,550.0,480.0))
        assertEquals(1,pans)
        send(MotionEvent.ACTION_CANCEL,p(x=550.0,y=180.0),p(19,550.0,480.0))
        send(MotionEvent.ACTION_DOWN,p())
        send(MotionEvent.ACTION_MOVE,p(x=540.0))
        assertEquals(2,gestures);assertEquals(2,pans)
        send(MotionEvent.ACTION_UP,p(x=540.0))
    }

    @Test fun touchActivityStartsOnAcceptedDownAndEndsOnlyWithLastUpOrCancel() {
        val activity=mutableListOf<Boolean>();view.onTouchActivity={activity+=it}
        twoDown();assertEquals(listOf(true),activity)
        send(MotionEvent.ACTION_POINTER_UP,p(x=400.0),p(19,600.0));assertEquals(listOf(true),activity)
        send(MotionEvent.ACTION_MOVE,p(19,650.0));send(MotionEvent.ACTION_UP,p(19,650.0))
        assertEquals(listOf(true,false),activity)
        send(MotionEvent.ACTION_DOWN,p());send(MotionEvent.ACTION_CANCEL,p())
        assertEquals(listOf(true,false,true,false),activity)
    }
    @Test fun removedButtonsDoNotCancelTouchAndSecondPointerCanUseTheirFormerPixelsImmediately() {
        val button=Rect(800f,0f,1000f,600f);val activity=mutableListOf<Boolean>();val motions=mutableListOf<ChartMotion>()
        view.nativeMotion={motions+=it};view.onTouchActivity={activity+=it};view.blockedRegions=listOf(button);view.gestureBlockedRegions=emptyList()
        send(MotionEvent.ACTION_DOWN,p(x=400.0))
        // The gesture mask applies before Compose has removed the visual controls.
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),p(x=400.0),p(19,850.0))
        view.blockedRegions=emptyList()
        send(MotionEvent.ACTION_MOVE,p(x=350.0),p(19,900.0))
        twoUp(350.0,900.0)
        assertEquals(listOf(true,false),activity);assertEquals(1,motions.size)
        assertEquals(550.0/450.0,motions.single().zoom,1e-12)
    }
    @Test fun protectedRegionsCancelCameraButKeepTouchActiveThroughTheTerminalEvent() {
        for(addedDuringTouch in listOf(false,true))for(terminal in listOf(MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL)) {
            val activity=mutableListOf<Boolean>();view.onTouchActivity={activity+=it}
            view.blockedRegions=emptyList();view.gestureBlockedRegions=null
            val region=Rect(650f,0f,800f,600f)
            if(!addedDuringTouch)view.blockedRegions=listOf(region)
            send(MotionEvent.ACTION_DOWN,p())
            val before=updates.size
            if(addedDuringTouch)view.blockedRegions=listOf(region)
            send(MotionEvent.ACTION_MOVE,p(x=700.0))
            assertTrue("A cancelled camera still has a held chart pointer",view.isTouchActive)
            assertEquals(listOf(true),activity);assertEquals(before,updates.size)
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),p(x=700.0),p(19,750.0))
            send(MotionEvent.ACTION_POINTER_UP,p(x=700.0),p(19,750.0))
            assertTrue(view.isTouchActive)
            send(terminal,p(19,750.0))
            assertEquals(listOf(true,false),activity);assertEquals(before,updates.size)
            send(MotionEvent.ACTION_DOWN,p());send(MotionEvent.ACTION_UP,p())
            assertEquals(listOf(true,false,true,false),activity)
        }
    }
    @Test fun dockClaimEndsOutlineOwnershipOnceAndIgnoresRemainingEventsUntilNewDown() {
        var finishes=0;val activity=mutableListOf<Boolean>();view.onTouchBegin={true};view.onTouchFinish={finishes++};view.onTouchActivity={activity+=it}
        send(MotionEvent.ACTION_DOWN,p());view.cancelTouchForDock();view.cancelTouchForDock()
        assertTrue("Dock claim cancels camera work while the finger remains down",view.isTouchActive)
        assertEquals(listOf(true),activity)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),p(),p(19,650.0))
        send(MotionEvent.ACTION_MOVE,p(x=550.0),p(19,700.0))
        send(MotionEvent.ACTION_POINTER_UP,p(x=550.0),p(19,700.0))
        assertTrue(view.isTouchActive);assertEquals(listOf(true),activity)
        send(MotionEvent.ACTION_MOVE,p(19,750.0));send(MotionEvent.ACTION_UP,p(19,750.0))
        assertEquals(1,finishes);assertEquals(listOf(true,false),activity);assertTrue(updates.isEmpty())
        send(MotionEvent.ACTION_DOWN,p());send(MotionEvent.ACTION_CANCEL,p())
        assertEquals(2,finishes);assertEquals(listOf(true,false,true,false),activity)
    }

    @Test fun objectInfoRequiresExplicitModeAndStillAcceptsSingleTap() {
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_UP, p())
        assertTrue(taps.isEmpty()); assertTrue(updates.isEmpty())
        view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_UP, p())
        assertEquals(listOf(ScreenPoint(500.0, 300.0)), taps)
    }

    @Test fun nativeMotionKeepsSurfacePixelsAndDoesNotUseUiGeography() {
        val motions = mutableListOf<ChartMotion>()
        view.nativeMotion = { motions += it }
        view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_MOVE, p(x = 650.0)); send(MotionEvent.ACTION_UP, p(x = 650.0))
        assertEquals(1, motions.size); assertEquals(150.0, motions.single().pan.x, 0.0)
        assertEquals(home, view.viewport); assertTrue(updates.isEmpty()); assertTrue(taps.isEmpty())
    }
    @Test fun offModeMultitouchUsesNativeMotionsThroughPointerUpAndCancelWithoutPreviewCallbacks() {
        val motions=mutableListOf<ChartMotion>();var starts=0;var previews=0;var finishes=0
        view.onTouchBegin={starts++;false};view.onTouchViewport={previews++};view.onTouchFinish={finishes++}
        view.nativeMotion={motions+=it}
        twoDown();send(MotionEvent.ACTION_MOVE,p(x=350.0),p(19,650.0))
        send(MotionEvent.ACTION_POINTER_UP,p(x=350.0),p(19,650.0))
        send(MotionEvent.ACTION_MOVE,p(19,700.0));send(MotionEvent.ACTION_CANCEL,p(19,700.0))
        assertEquals(1,starts);assertEquals(0,previews);assertEquals(0,finishes)
        assertEquals(2,motions.size);assertEquals(1.5,motions[0].zoom,1e-12)
        assertEquals(ScreenPoint(650.0,300.0),motions[1].anchor);assertEquals(ScreenPoint(50.0,0.0),motions[1].pan)
        assertEquals(home,view.viewport);assertTrue(updates.isEmpty())
    }

    @Test fun anySecondPointerCrossingPanelCancelsEntireNativeStream() {
        val motions = mutableListOf<ChartMotion>(); view.nativeMotion = { motions += it }
        view.blockedRegions = listOf(Rect(800f, 0f, 1000f, 600f)); view.inspectEnabled = true
        twoDown(); send(MotionEvent.ACTION_MOVE, p(x = 350.0), p(19, 850.0))
        send(MotionEvent.ACTION_MOVE, p(x = 350.0), p(19, 650.0)); twoUp(350.0, 650.0)
        assertTrue(motions.isEmpty()); assertTrue(taps.isEmpty())
    }

    @Test fun historicalPanelExcursionCannotReturnAsValidTapOrMotion() {
        val motions = mutableListOf<ChartMotion>(); view.nativeMotion = { motions += it }
        view.blockedRegions = listOf(Rect(800f, 0f, 1000f, 600f)); view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, p())
        event(MotionEvent.ACTION_MOVE, listOf(p(x = 900.0))).let { e ->
            e.addBatch(time + 16, 500f, 300f, 1f, 1f, 0)
            try { assertTrue(parent.dispatchTouchEvent(e)) } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_UP, p())
        assertTrue(motions.isEmpty()); assertTrue(taps.isEmpty())
    }

    @Test fun resizedOrNewPanelBoundsCancelPendingTapAndNewDownRecovers() {
        val local = mutableListOf<ScreenPoint>(); view.onSurfaceTap = { local += it }; view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, p()); view.blockedRegions = listOf(Rect(800f, 0f, 1000f, 600f)); send(MotionEvent.ACTION_UP, p())
        assertTrue(local.isEmpty())
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_UP, p())
        assertEquals(listOf(ScreenPoint(500.0, 300.0)), local)
    }

    @Test fun firstAndOnlyDragMoveIsAppliedInFullWithoutPopup() {
        view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, p())
        send(MotionEvent.ACTION_MOVE, p(x = 650.0))
        send(MotionEvent.ACTION_UP, p(x = 650.0))
        assertEquals(1, updates.size)
        val expected = home.longitude - Math.toDegrees(150.0 / home.metresToPixels / (6378137.0 * 0.9996))
        assertEquals(expected, view.viewport.longitude, 1e-9)
        assertEquals(home.metresToPixels, view.viewport.metresToPixels, 0.0)
        assertTrue(taps.isEmpty())
    }

    @Test fun movementReportedOnlyOnReleaseIsADragNeverATap() {
        view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_UP, p(x = 650.0))
        assertEquals(1, updates.size)
        assertTrue(view.viewport.longitude < home.longitude)
        assertTrue(taps.isEmpty())
    }

    @Test fun firstAndOnlyTwoFingerSpreadDoublesZoom() {
        view.inspectEnabled = true
        twoDown()
        send(MotionEvent.ACTION_MOVE, p(x = 300.0), p(19, 700.0))
        twoUp(300.0, 700.0)
        assertEquals(1, updates.size)
        assertEquals(0.3, view.viewport.metresToPixels, 1e-12)
        assertEquals(home.latitude, view.viewport.latitude, 1e-10)
        assertEquals(home.longitude, view.viewport.longitude, 1e-10)
        assertTrue(taps.isEmpty())
    }

    @Test fun twoFingerPinchHalvesZoom() {
        view.inspectEnabled = true
        twoDown(300.0, 700.0)
        send(MotionEvent.ACTION_MOVE, p(x = 400.0), p(19, 600.0))
        twoUp(400.0, 600.0)
        assertEquals(0.075, view.viewport.metresToPixels, 1e-12)
        assertTrue(taps.isEmpty())
    }

    @Test fun reindexedPointersAndLiftedOriginalFingerDoNotJumpOrOpenInfo() {
        view.inspectEnabled = true
        twoDown()
        // Same stable IDs in the opposite index order.
        send(MotionEvent.ACTION_MOVE, p(19, 650.0), p(x = 350.0))
        val enlarged = view.viewport
        send(MotionEvent.ACTION_POINTER_UP, p(x = 350.0), p(19, 650.0))
        assertEquals(enlarged, view.viewport)
        send(MotionEvent.ACTION_MOVE, p(19, 700.0))
        val expected = transformChartViewport(enlarged, ScreenPoint(650.0, 300.0), ScreenPoint(50.0, 0.0))
        assertEquals(expected, view.viewport)
        send(MotionEvent.ACTION_UP, p(19, 700.0))
        assertTrue(taps.isEmpty())
    }

    @Test fun batchedExcursionReturningToStartIsNeverATap() {
        view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, p())
        val move = event(MotionEvent.ACTION_MOVE, listOf(p(x = 650.0)))
        time += 16
        move.addBatch(time, arrayOf(coordinates(p())), 0)
        try { assertEquals(1, move.historySize); assertTrue(parent.dispatchTouchEvent(move)) } finally { move.recycle() }
        send(MotionEvent.ACTION_UP, p())
        assertEquals(2, updates.size)
        assertEquals(home.longitude, view.viewport.longitude, 1e-10)
        assertTrue(taps.isEmpty())
    }

    @Test fun cancelledTouchCannotOpenInfoAndNextSequenceWorks() {
        view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_CANCEL, p())
        assertTrue(taps.isEmpty())
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_MOVE, p(x = 650.0)); send(MotionEvent.ACTION_UP, p(x = 650.0))
        assertEquals(1, updates.size); assertTrue(taps.isEmpty())
    }

    @Test fun twoFingerRotationKeepsCenterAndScale() {
        twoDown()
        send(MotionEvent.ACTION_MOVE, p(x = 500.0, y = 200.0), p(19, 500.0, 400.0))
        assertEquals(PI / 2, view.viewport.rotationRadians, 1e-12)
        assertEquals(home.metresToPixels, view.viewport.metresToPixels, 1e-12)
        assertEquals(home.latitude, view.viewport.latitude, 1e-10)
        assertEquals(home.longitude, view.viewport.longitude, 1e-10)
    }

    @Test fun defaultIntentPolicySuppressesSmallRotationWhilePinchingThroughRealMotionEvents() {
        twoDown()
        send(MotionEvent.ACTION_MOVE, p(x = 380.0, y = 297.0), p(19, 620.0, 303.0))
        assertEquals(0.0, view.viewport.rotationRadians, 0.0)
        assertEquals(home.metresToPixels * hypot(240.0, 6.0) / 200, view.viewport.metresToPixels, 1e-12)
        assertEquals(home.latitude, view.viewport.latitude, 1e-10)
        assertEquals(home.longitude, view.viewport.longitude, 1e-10)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            p(x = 380.0, y = 297.0), p(19, 620.0, 303.0))
        send(MotionEvent.ACTION_UP, p(x = 380.0, y = 297.0))
        assertTrue(taps.isEmpty())
    }

    @Test fun defaultIntentPolicyAllowsDeliberateRotationAfterStartingWithOneFingerPan() {
        send(MotionEvent.ACTION_DOWN, p(x = 400.0))
        send(MotionEvent.ACTION_MOVE, p(x = 420.0))
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), p(x = 420.0), p(19, 620.0))
        val r = .3
        send(MotionEvent.ACTION_MOVE, p(x = 520.0 - 100 * cos(r), y = 300.0 - 100 * sin(r)),
            p(19, 520.0 + 100 * cos(r), 300.0 + 100 * sin(r)))
        // MotionEvent coordinates are float; the intended .3 radians is quantized before dispatch.
        assertEquals(r, view.viewport.rotationRadians, 1e-6)
        assertEquals(home.metresToPixels, view.viewport.metresToPixels, 1e-12)
    }

    @Test @Config(qualifiers = "w1000dp-h600dp-land-xhdpi")
    fun normalPinchRemainsUsableWithADenseDisplayFrameworkConfiguration() {
        val configuration = android.view.ViewConfiguration.get(view.context)
        println("GESTURE_CONFIG,density=${view.resources.displayMetrics.density},touchSlop=${configuration.scaledTouchSlop},frameworkMinimumSpan=${configuration.scaledMinimumScalingSpan}")
        twoDown(420.0, 580.0)
        send(MotionEvent.ACTION_MOVE, p(x = 380.0), p(19, 620.0))
        assertEquals(home.metresToPixels * 1.5, view.viewport.metresToPixels, 1e-12)
        assertEquals(0.0, view.viewport.rotationRadians, 0.0)
    }

    @Test fun slowHeldTouchDoesNotBecomeFeatureTap() {
        view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, p()); time += 1000; send(MotionEvent.ACTION_UP, p())
        assertTrue(taps.isEmpty()); assertTrue(updates.isEmpty())
    }

    private fun resize(w: Int, h: Int) {
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        view.resizeSurface(w, h) // Same entry point as SurfaceHolder.surfaceChanged.
    }

    // Independent Mercator inverse/forward, checking both fingers rather than only the centroid.
    private fun geographic(view: ChartViewport, point: ScreenPoint): ScreenPoint {
        val dx = (point.x - view.width / 2.0) / view.metresToPixels
        val dy = (point.y - view.height / 2.0) / view.metresToPixels
        val east = dx * cos(view.rotationRadians) + dy * sin(view.rotationRadians)
        val north = dx * sin(view.rotationRadians) - dy * cos(view.rotationRadians)
        val radius = 6378137.0 * .9996
        return ScreenPoint(view.longitude + Math.toDegrees(east / radius),
            Math.toDegrees(2 * atan(exp(ln(tan(PI / 4 + Math.toRadians(view.latitude) / 2)) + north / radius)) - PI / 2))
    }

    private fun project(view: ChartViewport, geo: ScreenPoint): ScreenPoint {
        val radius = 6378137.0 * .9996
        val east = Math.toRadians(geo.x - view.longitude) * radius
        val north = (ln(tan(PI / 4 + Math.toRadians(geo.y) / 2)) - ln(tan(PI / 4 + Math.toRadians(view.latitude) / 2))) * radius
        return ScreenPoint(view.width / 2.0 + (east * cos(view.rotationRadians) + north * sin(view.rotationRadians)) * view.metresToPixels,
            view.height / 2.0 + (east * sin(view.rotationRadians) - north * cos(view.rotationRadians)) * view.metresToPixels)
    }

    @Test fun bothAnchorsFollowCombinedPinchRotationAndPanAtBothTargetSizes() {
        for (height in listOf(1116, 1200)) {
            resize(2000, height)
            view.acceptViewport(home.copy(width = 2000, height = height, rotationRadians = .24))
            val before = view.viewport
            val points = listOf(p(7, 620.0, 390.0), p(19, 940.0, 570.0))
            val geographicPoints = points.map { geographic(before, ScreenPoint(it.x, it.y)) }
            send(MotionEvent.ACTION_DOWN, points[0])
            send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), *points.toTypedArray())
            val after = points.map { point ->
                val dx = point.x - 780; val dy = point.y - 480
                p(point.id, 870 + 1.35 * (dx * cos(.63) - dy * sin(.63)),
                    440 + 1.35 * (dx * sin(.63) + dy * cos(.63)))
            }
            send(MotionEvent.ACTION_MOVE, *after.toTypedArray())
            for (i in points.indices) {
                val projected = project(view.viewport, geographicPoints[i])
                assertEquals(after[i].x, projected.x, .001)
                assertEquals(after[i].y, projected.y, .001)
            }
            send(MotionEvent.ACTION_CANCEL, *after.toTypedArray())
            assertEquals(2000, view.viewport.width); assertEquals(height, view.viewport.height)
        }
    }

    @Test fun resizeKeepsCenterAndTapCoordinatesAndCancelsOldGesture() {
        resize(2000, 1200)
        val before = view.viewport
        send(MotionEvent.ACTION_DOWN, p())
        resize(2000, 1116)
        val resized = view.viewport
        send(MotionEvent.ACTION_MOVE, p(x = 650.0))
        send(MotionEvent.ACTION_UP, p(x = 650.0))
        assertEquals(resized, view.viewport)
        assertEquals(before.latitude, resized.latitude, 0.0)
        assertEquals(before.longitude, resized.longitude, 0.0)
        assertEquals(before.metresToPixels, resized.metresToPixels, 0.0)
        view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, p(x = 850.0, y = 700.0))
        send(MotionEvent.ACTION_UP, p(x = 850.0, y = 700.0))
        assertEquals(ScreenPoint(850.0, 700.0), taps.last())
        // A late Compose update cannot restore the old 1200-high framebuffer.
        view.acceptViewport(before)
        assertEquals(1116, view.viewport.height)
        resize(2000, 1200)
        assertEquals(1200, view.viewport.height)
    }

    @Test fun cappedPortraitMappingKeepsBothFingerAnchorsAndTapAligned() {
        resize(1200, 2000)
        val geometry = ChartSurfaceGeometry.fit(1200, 2000)
        val before = view.viewport
        assertEquals(home.metresToPixels, before.metresToPixels * geometry.scale, 1e-12)
        val old = listOf(p(7, 350.0, 700.0), p(19, 750.0, 1100.0))
        val geos = old.map { geographic(before, geometry.toFrame(ScreenPoint(it.x, it.y))) }
        send(MotionEvent.ACTION_DOWN, old[0])
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), *old.toTypedArray())
        val next = listOf(p(7, 590.0, 500.0), p(19, 710.0, 1280.0))
        send(MotionEvent.ACTION_MOVE, *next.toTypedArray())
        for (i in geos.indices) {
            val projected = geometry.toSurface(project(view.viewport, geos[i]))
            assertEquals(next[i].x, projected.x, .001); assertEquals(next[i].y, projected.y, .001)
        }
        send(MotionEvent.ACTION_CANCEL, *next.toTypedArray())
        view.inspectEnabled = true
        send(MotionEvent.ACTION_DOWN, next[0]); send(MotionEvent.ACTION_UP, next[0])
        assertEquals(geometry.toFrame(ScreenPoint(next[0].x, next[0].y)), taps.last())
    }
    @Test fun outlineTouchUsesNewestCameraAndFinishesImmediatelyOnlyOnLastUp() {
        var starts=0;var finishes=0;val wanted=mutableListOf<ChartViewport>();val native=mutableListOf<ChartMotion>()
        view.onTouchBegin={starts++;true};view.onTouchViewport={wanted+=it};view.onTouchFinish={finishes++};view.nativeMotion={native+=it}
        twoDown();repeat(60){i->send(MotionEvent.ACTION_MOVE,p(x=370.0-i),p(19,630.0+i,300.0+i*.5))}
        send(MotionEvent.ACTION_POINTER_UP,p(x=340.0),p(19,660.0,330.0));assertEquals(0,finishes)
        send(MotionEvent.ACTION_MOVE,p(19,700.0,350.0));val before=view.viewport
        send(MotionEvent.ACTION_UP,p(19,720.0,360.0))
        assertEquals(1,starts);assertEquals(1,finishes);assertTrue(native.isEmpty());assertEquals(wanted.last(),view.viewport);assertNotEquals(before,view.viewport)
        assertTrue(wanted.distinct().size>=60)
    }
    @Test fun outlineCancelDoesNotApplyCancelCoordinatesAndCommandCancelsRemainingStream() {
        var finishes=0;val wanted=mutableListOf<ChartViewport>();view.onTouchBegin={true};view.onTouchViewport={wanted+=it};view.onTouchFinish={finishes++}
        send(MotionEvent.ACTION_DOWN,p());send(MotionEvent.ACTION_MOVE,p(x=650.0));val camera=view.viewport
        send(MotionEvent.ACTION_CANCEL,p(x=900.0));assertEquals(camera,view.viewport);assertEquals(1,finishes)
        send(MotionEvent.ACTION_DOWN,p());send(MotionEvent.ACTION_MOVE,p(x=550.0));val count=wanted.size
        view.cancelGesture();send(MotionEvent.ACTION_MOVE,p(x=900.0));send(MotionEvent.ACTION_UP,p(x=900.0));assertEquals(count,wanted.size);assertEquals(1,finishes)
    }
    @Test fun overlayDoesNotStealTouchAndOwnInvalidationChangesAcrossHeldGesture() {
        val overlay=ChartOutlineView(view.context);parent.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        parent.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));parent.layout(0,0,1000,600)
        assertFalse(overlay.isClickable);view.onTouchBegin={true};val wanted=mutableListOf<ChartViewport>();view.onTouchViewport={wanted+=it}
        send(MotionEvent.ACTION_DOWN,p());repeat(60){send(MotionEvent.ACTION_MOVE,p(x=550.0+it))};send(MotionEvent.ACTION_UP,p(x=575.0));assertTrue(wanted.distinct().size>=60)
    }

    // Controlled TextureView camera associations; these tests make no hardware/Display claim.
    private fun t0Buffer(camera: ChartViewport, revision: Long, stamp: Long = revision, generation: Long = 7) =
        ChartBufferSnapshot(stamp, generation, revision, ChartFrame(stamp, "controlled:$stamp", ChartSurfaceGeometry.fit(1000, 600), camera, null), true)
    private fun texturePoint(x: Float, y: Float): FloatArray {
        val matrix = Matrix(); view.getTransform(matrix)
        return floatArrayOf(x, y).also { matrix.mapPoints(it) }
    }
    private class ControlledTimestampTexture : SurfaceTexture(0) {
        var sampledTag = 0L
        override fun getTimestamp(): Long = sampledTag
    }
    @Test fun t0FinalImageRebasesAfterDeferredAcquireWithoutAnotherProducerCallback() {
        var revision = 10L
        view.bufferTransformMode = ChartBufferTransformMode.TRANSFORM_WITH_RENDER; view.bufferTransformGeneration = 7
        view.onTouchBegin = { true }; view.onTouchViewport = { revision++ }; view.cameraRevision = { revision }
        val initial = t0Buffer(home, revision)
        val tags = mutableMapOf(initial.stamp to initial)
        val sampled = mutableListOf<Long>(); val acknowledged = mutableListOf<Long>()
        view.onTaggedBuffer = { sampled += it; tags[it] }; view.onBufferConsumed = { acknowledged += it }
        view.consumeTransformBuffer(initial)
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_MOVE, p(x = 650.0)); send(MotionEvent.ACTION_UP, p(x = 650.0))
        val target = view.viewport; val replacement = t0Buffer(target, revision); tags[replacement.stamp] = replacement
        val texture = ControlledTimestampTexture()
        try {
            view.surfaceTextureListener!!.onSurfaceTextureAvailable(texture,1000,600)
            // API29 UI callback sees the old timestamp; HWUI acquires the replacement later in that traversal.
            texture.sampledTag = initial.stamp
            view.surfaceTextureListener!!.onSurfaceTextureUpdated(texture)
            assertEquals(650f, texturePoint(500f, 300f)[0], .01f)
            texture.sampledTag = replacement.stamp
            view.acknowledgeTextureUpdate()
            assertEquals(listOf(initial.stamp, replacement.stamp), sampled)
            assertEquals(listOf(replacement.stamp), acknowledged)
            assertEquals(replacement.stamp, view.bufferTransformBasis!!.stamp)
            assertEquals(500f, texturePoint(500f, 300f)[0], .01f)
            assertEquals(target, view.viewport)
            view.acknowledgeTextureUpdate(); assertEquals(2, sampled.size)
        } finally { view.surfaceTextureListener!!.onSurfaceTextureDestroyed(texture) }
    }
    @Test fun t0LateOlderBufferKeepsLatestTargetAndExactCameraHandoffResetsTransform() {
        var revision = 10L; var finishes = 0
        view.bufferTransformMode = ChartBufferTransformMode.TRANSFORM_WITH_RENDER
        view.bufferTransformGeneration = 7
        view.onTouchBegin = { true }; view.onTouchViewport = { revision++ }; view.cameraRevision = { revision }
        view.onTouchFinish = { finishes++ }
        view.consumeTransformBuffer(t0Buffer(home, revision))
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_MOVE, p(x = 650.0))
        val older = view.viewport
        send(MotionEvent.ACTION_MOVE, p(x = 750.0))
        val target = view.viewport
        assertEquals(750f, texturePoint(500f, 300f)[0], .01f)
        view.consumeTransformBuffer(t0Buffer(older, 11))
        assertEquals(target, view.viewport)
        assertEquals(750f, texturePoint(650f, 300f)[0], .01f)
        send(MotionEvent.ACTION_UP, p(x = 750.0)); assertEquals(1, finishes)
        view.consumeTransformBuffer(t0Buffer(target, revision))
        assertEquals(750f, texturePoint(750f, 300f)[0], .01f)
        view.acceptViewport(home) // A delayed producer status must not take the camera backwards.
        assertEquals(target, view.viewport)
    }
    @Test fun t0ControlUsesSameCameraChangesWithoutTextureTransform() {
        var revision = 10L; val wanted = mutableListOf<ChartViewport>()
        view.bufferTransformMode = ChartBufferTransformMode.CONTROL; view.bufferTransformGeneration = 7
        view.onTouchBegin = { true }; view.onTouchViewport = { wanted += it; revision++ }; view.cameraRevision = { revision }
        view.consumeTransformBuffer(t0Buffer(home, revision))
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_MOVE, p(x = 650.0)); send(MotionEvent.ACTION_UP, p(x = 650.0))
        assertEquals(transformChartViewport(home, ScreenPoint(500.0, 300.0), ScreenPoint(150.0, 0.0)), wanted.single())
        assertEquals(500f, texturePoint(500f, 300f)[0], 0f)
    }
    @Test fun t0UnknownOrWrongGenerationBufferNeverInheritsNewestCamera() {
        var revision = 10L; var finishes = 0
        view.bufferTransformMode = ChartBufferTransformMode.TRANSFORM_ONLY; view.bufferTransformGeneration = 7
        view.onTouchBegin = { true }; view.onTouchViewport = { revision++ }; view.cameraRevision = { revision }; view.onTouchFinish = { finishes++ }
        view.consumeTransformBuffer(t0Buffer(home, revision))
        send(MotionEvent.ACTION_DOWN, p()); send(MotionEvent.ACTION_MOVE, p(x = 650.0))
        view.consumeTransformBuffer(t0Buffer(view.viewport, revision, generation = 6))
        assertNull(view.bufferTransformBasis); assertEquals(1, finishes)
        assertEquals(500f, texturePoint(500f, 300f)[0], 0f)
        view.consumeTransformBuffer(null); assertNull(view.bufferTransformBasis)
        send(MotionEvent.ACTION_UP, p(x = 650.0))
    }

}
