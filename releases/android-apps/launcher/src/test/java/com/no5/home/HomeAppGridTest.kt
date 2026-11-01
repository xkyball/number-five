package com.no5.home

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.no5.navigation.AppNavigation
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "de-rDE-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeAppGridTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun layout(view: View, height: Int = 1200) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 2000, height)
    }
    private fun candidate(index: Int, title: String) = ResolveInfo().apply {
        nonLocalizedLabel = title
        activityInfo = ActivityInfo().apply {
            val pkg = "test.app$index"
            packageName = pkg; name = "$packageName.MainActivity"; enabled = true; exported = true
            applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true }
        }
    }
    private fun fixtures(): List<ResolveInfo> {
        val titles = listOf("Änderungen", "Albatros", "Bord-Navigation mit einem sehr langen Anzeigenamen über mehrere Zeilen",
            "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "Echo") +
            (5 until 45).map { "Instrument ${it.toString().padStart(2, '0')}" }
        val apps = titles.mapIndexed { index, title -> candidate(index, title) }
        val pm = shadowOf(context.packageManager)
        apps.forEachIndexed { i, info ->
            pm.installPackage(PackageInfo().apply {
                packageName = info.activityInfo.packageName
                applicationInfo = info.activityInfo.applicationInfo
                activities = arrayOf(info.activityInfo)
            })
            if (i != 4) pm.addActivityIcon(ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                ColorDrawable(Color.rgb(40 + i * 4, 130, 180)))
        }
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), apps.reversed())
        return apps
    }
    private fun list(activity:HomeActivity)=all(activity.window.decorView).filterIsInstance<HomeWorkspaceView>().single()
    private fun rows(activity:HomeActivity)=all(list(activity)).filter{it.tag?.toString()?.startsWith("no5-row-home-app-")==true}
    private fun scroll(activity:HomeActivity)=all(list(activity)).filterIsInstance<android.widget.ScrollView>().single()
    private fun openGrid(activity: HomeActivity) {
        all(activity.window.decorView).filterIsInstance<Button>().first { it.text == "Alle Apps" }.performClick()
        layout(activity.findViewById(android.R.id.content))
    }
    @Test fun activityIconWinsAndMissingActivityFallsBackToApplicationThenAndroidDefault() {
        val pm = shadowOf(context.packageManager)
        val controller = HomeController(context)
        val app = HomeController.App("Albatros", ComponentName("test.icons", "test.icons.LaunchAlias"))
        val activityIcon = ColorDrawable(Color.BLUE)
        val packageIcon = ColorDrawable(Color.GREEN)
        pm.setApplicationIcon(app.component.packageName, packageIcon)
        assertSame(packageIcon, controller.icon(app))
        pm.addActivityIcon(app.component, activityIcon)
        assertSame(activityIcon, controller.icon(app))
        val missing = controller.icon(HomeController.App("Fehlend", ComponentName("missing", "missing.Main")))
        assertNotNull(missing)
        assertTrue(missing.intrinsicWidth > 0)
    }
    @Test fun namesStaySortedNativeRowsMeetTouchBoundsAndRealTouchStartsTheirComponent(){
        fixtures()
        val ordered=HomeController(context).apps()
        assertEquals(listOf("Albatros","Änderungen"),ordered.take(2).map{it.label})
        for(night in listOf(false,true))for(height in listOf(1200,928)){
            Robolectric.buildActivity(HomeActivity::class.java,Intent().putExtra(AppNavigation.NIGHT,night)).setup().visible().windowFocusChanged(true).use{host->
                val activity=host.get();openGrid(activity)
                val view=activity.findViewById<ViewGroup>(android.R.id.content);layout(view,height)
                val rows=rows(activity);assertEquals(45,rows.size)
                assertEquals(ordered.map{"no5-row-home-app-${it.component.flattenToString()}"},rows.map{it.tag})
                rows.take(4).forEach{row->
                    val rect=Rect();assertTrue(row.getGlobalVisibleRect(rect));val origin=IntArray(2);view.getLocationOnScreen(origin);rect.offset(-origin[0],-origin[1])
                    assertTrue("Actual native touch target too small: $rect",rect.width()>=96&&rect.height()>=96)
                    assertFalse(HomeStage.intersectsSafety(RectF(rect)))
                    assertTrue(row.contentDescription.toString().startsWith(ordered[rows.indexOf(row)].label))
                }
                assertTrue(rows.any{it.contentDescription.toString().startsWith("Bord-Navigation mit einem sehr langen Anzeigenamen")})
                assertTrue(rows.any{it.contentDescription.toString().startsWith("C".repeat(52))})
                val target=Rect();assertTrue(rows[0].getGlobalVisibleRect(target));val origin=IntArray(2);view.getLocationOnScreen(origin);target.offset(-origin[0],-origin[1])
                assertTrue(rows[0].isAttachedToWindow);assertTrue(rows[0].hasWindowFocus())
                val stamp=SystemClock.uptimeMillis()
                for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                    val event=MotionEvent.obtain(stamp,stamp+if(action==MotionEvent.ACTION_UP)32 else 0,action,target.exactCenterX(),target.exactCenterY(),0)
                    try{assertTrue(view.dispatchTouchEvent(event))}finally{event.recycle()}
                }
                shadowOf(Looper.getMainLooper()).idle()
                val started=shadowOf(activity).nextStartedActivity
                assertEquals(ordered.first().component,started.component)
                assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,started.flags)
                assertFalse(started.hasExtra(AppNavigation.NIGHT))
            }
        }
    }
    @Suppress("DEPRECATION")
    @Test fun scrollSurvivesThemeReturnAndRecreationAndBackClosesTheList(){
        fixtures()
        Robolectric.buildActivity(HomeActivity::class.java).setup().use{host->
            var activity=host.get();openGrid(activity)
            scroll(activity).scrollTo(0,30*104);layout(activity.findViewById(android.R.id.content))
            val position=scroll(activity).scrollY;assertTrue(position>=25*104)
            all(activity.window.decorView).single{it.tag=="header-night"}.performClick()
            layout(activity.findViewById(android.R.id.content));assertEquals(position,scroll(activity).scrollY)
            rows(activity)[30].performClick();assertNotNull(shadowOf(activity).nextStartedActivity)
            host.pause().stop().restart().start().resume();layout(activity.findViewById(android.R.id.content))
            assertEquals(position,scroll(activity).scrollY)
            host.recreate();activity=host.get();layout(activity.findViewById(android.R.id.content))
            assertEquals(position,scroll(activity).scrollY)
            activity.onBackPressed();assertTrue(all(activity.window.decorView).filterIsInstance<HomeWorkspaceView>().isEmpty());assertFalse(activity.isFinishing)
            openGrid(activity);assertEquals(0,scroll(activity).scrollY)
        }
    }
}
