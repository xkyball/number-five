package com.no5.helm.ui
import android.app.Activity
import android.graphics.*
import android.view.*
import android.widget.FrameLayout
import com.no5.helm.chart.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartOutlineRasterTest {
    @Test fun cachedProductOutlineMovesAcrossEntireHeldGestureWithoutNativeCalls() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible().get()
        val container=FrameLayout(activity);val chart=ChartSurfaceView(activity);val overlay=ChartOutlineView(activity)
        container.addView(chart,FrameLayout.LayoutParams(-1,-1));container.addView(overlay,FrameLayout.LayoutParams(-1,-1));activity.setContentView(container)
        container.measure(View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));container.layout(0,0,1000,600)
        chart.viewport=ChartViewport(42.36,-71.04,.5,width=1000,height=600)
        overlay.outlines=ChartOutlines(GeoProjection(42.36,-71.04),floatArrayOf(-100f,0f,100f,0f,0f,-100f,0f,100f),20.0,"testgeometry")
        overlay.geometry=ChartSurfaceGeometry.fit(1000,600)
        overlay.blocked=listOf(androidx.compose.ui.geometry.Rect(0f,0f,1000f,60f))
        var native=0;var ended=0;chart.nativeMotion={native++};chart.onTouchBegin={true};chart.onTouchFinish={ended++}
        chart.onTouchViewport={camera->overlay.preview=ChartTouchPreview(camera,true,true,1,null)}
        fun send(action:Int,x:Float,y:Float,time:Long){val e=MotionEvent.obtain(1000,time,action,x,y,0);try{assertTrue(container.dispatchTouchEvent(e))}finally{e.recycle()}}
        val b=Bitmap.createBitmap(1000,600,Bitmap.Config.ARGB_8888);val c=Canvas(b);val hashes=mutableSetOf<Int>()
        send(MotionEvent.ACTION_DOWN,500f,300f,1000)
        repeat(60){i->send(MotionEvent.ACTION_MOVE,510f+i*3f,300f+i,1016+i*16L);overlay.draw(c);val pixels=IntArray(600000);b.getPixels(pixels,0,1000,0,0,1000,600);hashes+=pixels.contentHashCode()}
        assertTrue(hashes.size>=50);assertEquals(0,native);assertEquals(0,ended)
        send(MotionEvent.ACTION_UP,695f,365f,2100);assertEquals(1,ended)
        File("build/outline-evidence").mkdirs();File("build/outline-evidence/product-held.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
}
