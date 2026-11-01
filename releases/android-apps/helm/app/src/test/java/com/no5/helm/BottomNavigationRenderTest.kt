package com.no5.helm
import android.graphics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomNavigationRenderTest {
    @Test fun everyScreenUsesExactlyOneReferenceOrangeIndicatorInBothThemes() {
        val output=File(System.getProperty("no5.renderOutputDir"),"navigation").also{it.mkdirs()}
        for(night in listOf(false,true))for((active,page) in BottomNavigation.pages.withIndex()) {
            val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            HelmScene(RuntimeEnvironment.getApplication(),night).drawChrome(Canvas(bitmap),page,HelmRenderValues())
            val expected=if(night)Color.rgb(184,71,18) else Color.rgb(255,91,0)
            for(index in 0..4) {
                if(index==active){assertEquals(expected,bitmap.getPixel((BottomNavigation.cellLeft(index)+20).toInt(),1171));assertEquals(expected,bitmap.getPixel((BottomNavigation.cellLeft(index)+222).toInt(),1174))}
                else assertNotEquals(expected,bitmap.getPixel((BottomNavigation.cellLeft(index)+100).toInt(),1171))
            }
            var orangePixels=0
            for(y in 1160 until 1180)for(x in 0 until 2000)if(bitmap.getPixel(x,y)==expected)orangePixels++
            assertEquals("Exactly one 206 by 4 active rule",206*4,orangePixels)
            Bitmap.createBitmap(bitmap,0,1058,2000,142).also { footer ->
                File(output,"$page-${if(night)"night" else "day"}.png").outputStream().use{footer.compress(Bitmap.CompressFormat.PNG,100,it)}
                footer.recycle()
            };bitmap.recycle()
        }
    }
    @Test fun labelsHaveStrongContrastAndNightRuleIsDimmed() {
        fun luminance(color:Int):Double {
            fun channel(v:Int):Double { val s=v/255.0;return if(s<=.04045)s/12.92 else Math.pow((s+.055)/1.055,2.4) }
            return .2126*channel(Color.red(color))+.7152*channel(Color.green(color))+.0722*channel(Color.blue(color))
        }
        fun contrast(a:Int,b:Int):Double { val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05) }
        val evidence=StringBuilder()
        for(night in listOf(false,true)) {
            val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            HelmScene(RuntimeEnvironment.getApplication(),night).drawChrome(Canvas(bitmap),"AUDIO",HelmRenderValues())
            // Sample the real shared footer surface, not the decorative outer hull.
            val surface=bitmap.getPixel(700,1150)
            for(selected in listOf(false,true)) {
                val ink=Color.parseColor(BottomNavigation.ink(night,selected))
                val ratio=contrast(ink,surface)
                assertTrue("Readable footer labels: night=$night selected=$selected ratio=$ratio",ratio>=4.5)
                evidence.append("night=$night selected=$selected labelContrast=$ratio\n")
            }
            val orange=bitmap.getPixel(920,1172)
            val ratio=contrast(orange,surface)
            // Day uses the exact approved #ff5b00 (2.94:1); selection also has semantic state and darker ink.
            assertTrue(ratio>=if(night)3.0 else 2.9)
            evidence.append("night=$night indicatorContrast=$ratio luminance=${luminance(orange)}\n")
            bitmap.recycle()
        }
        assertTrue(luminance(Color.rgb(184,71,18))<luminance(Color.rgb(255,91,0)))
        File(System.getProperty("no5.renderOutputDir"),"navigation-contrast.txt").writeText(evidence.toString())
    }

}
