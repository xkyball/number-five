package com.no5.helm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.floor
import kotlin.math.ceil

/** Android Paint/Canvas regression; compiling this test is not a device test pass. */
@RunWith(AndroidJUnit4::class)
class HelmCanvasBoundsTest {
    @Test fun changingWideOrUnavailableFieldsCannotPaintIntoAdjacentCellsInEitherTheme(){
        val samples=linkedMapOf(
            "secondary-port-coolant" to "199°C", "secondary-stbd-coolant" to "-40°C",
            "secondary-port-oil" to "25.0 bar", "secondary-stbd-oil" to "25.0 bar",
            "secondary-port-voltage" to "99.9 V", "secondary-stbd-voltage" to "99.9 V",
            "deck-airTemp" to "28.4", "deck-wind" to "23.8",
            "deck-humidity" to "100", "deck-pressure" to "1032",
            "deck-evidence-environment" to "OPEN-METEO · FORECAST · LIVE",
            "deck-evidence-position" to "SOURCE · UNAVAILABLE"
        )
        val weather=listOf("deck-airTemp","deck-wind","deck-humidity","deck-pressure")
        val boxes=samples.keys.map { id ->
            val index=weather.indexOf(id)
            when {
                index>=0 -> NativeHelmLayout.TextBox(250f+index*190,870f,408f+index*190,906f)
                id=="deck-evidence-environment" -> NativeHelmLayout.TextBox(44f,876f,230f,906f)
                id=="deck-evidence-position" -> NativeHelmLayout.TextBox(650f,923f,981f,952f)
                else -> checkNotNull(NativeHelmLayout.valueUnitBox(id)).let{NativeHelmLayout.TextBox(it.left,it.top+44,it.right,it.bottom+44)}
            }
        }
        val context=ApplicationProvider.getApplicationContext<Context>()
        for(night in listOf(false,true)){
            val scene=HelmScene(context,night)
            fun render(text:Map<String,String>):Bitmap=Bitmap.createBitmap(2000,1200,
                Bitmap.Config.ARGB_8888).also{scene.draw(Canvas(it),HelmRenderValues(text=text))}
            val missing=render(samples.mapValues{"—"})
            val populated=render(samples)
            val before=IntArray(2000*1200);val after=IntArray(before.size)
            missing.getPixels(before,0,2000,0,0,2000,1200)
            populated.getPixels(after,0,2000,0,0,2000,1200)
            var outside=0;val changes=IntArray(boxes.size)
            for(index in before.indices)if(before[index]!=after[index]){
                val x=index%2000;val y=index/2000
                val boxIndex=boxes.indexOfFirst{x>=floor(it.left).toInt() && x<ceil(it.right).toInt() &&
                    y>=floor(it.top).toInt() && y<ceil(it.bottom).toInt()}
                if(boxIndex<0)outside++ else changes[boxIndex]++
            }
            assertEquals("night=$night telemetry escaped its declared Canvas cell",0,outside)
            changes.forEachIndexed{i,count->assertTrue("night=$night ${samples.keys.elementAt(i)} did not render",count>0)}
            missing.recycle();populated.recycle()
        }
    }

    @Test fun approvedFooterKeepsBreathingRoomInBothThemes(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        for(night in listOf(false,true)){
            val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            HelmScene(context,night).draw(Canvas(bitmap),HelmRenderValues())
            val background=Color.parseColor(if(night)"#000000" else "#f6f9fa")
            // Only the central gap remains: information now ends y1048 and footer starts1060.
            for(y in 1051..1057)for(x in 354..1646)
                assertEquals("night=$night footer gap painted at $x,$y",background,bitmap.getPixel(x,y))
            assertNotEquals("Approved native footer is painted",background,bitmap.getPixel(1000,1070))
            bitmap.recycle()
        }
    }
}
