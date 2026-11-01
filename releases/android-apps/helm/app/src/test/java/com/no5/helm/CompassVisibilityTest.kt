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
class CompassVisibilityTest {
    @Test fun roseAndOriginalTriangleHaveLightAndDarkEdgesWithoutOpaquePlate() {
        val scene=HelmScene(RuntimeEnvironment.getApplication(),false)
        val output=File(System.getProperty("no5.renderOutputDir"),"compass").also{it.mkdirs()}
        for(heading in listOf(0f,359f,1f,270f)) {
            val bitmap=Bitmap.createBitmap(2000,220,Bitmap.Config.ARGB_8888)
            scene.drawHeading(Canvas(bitmap),HelmRenderValues(heading=heading,headingAvailable=true,quality="LIVE"))
            fun count(left:Int,top:Int,right:Int,bottom:Int,light:Boolean):Int {
                var count=0
                for(y in top until bottom)for(x in left until right){val c=bitmap.getPixel(x,y)
                    if(Color.alpha(c)>220 && if(light) Color.red(c)>220 else Color.blue(c)<120)count++}
                return count
            }
            assertTrue("triangle dark core",count(980,118,1020,157,false)>60)
            assertTrue("triangle light halo",count(980,118,1020,157,true)>60)
            assertTrue("rose dark glyphs/ticks",count(650,70,1350,119,false)>100)
            assertTrue("rose light halo",count(650,70,1350,119,true)>100)
            assertEquals("no opaque plate",0,Color.alpha(bitmap.getPixel(800,178)))
            File(output,"heading-${heading.toInt()}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
}
