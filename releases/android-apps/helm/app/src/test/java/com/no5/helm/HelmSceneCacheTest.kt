package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.no5.helm.numeric.NumericSlots
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,33,35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HelmSceneCacheTest {
 @Test fun repeatedSceneUsesCachedMeasurementsWithoutChangingPixels() {
  for(night in listOf(false,true)) {
   val scene=HelmScene(RuntimeEnvironment.getApplication(),night)
   val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
   val canvas=Canvas(bitmap)
   scene.drawForeground(canvas,HelmRenderValues())
   val reference=bitmap.copy(Bitmap.Config.ARGB_8888,false)
   val before=scene.textCacheStats()
   repeat(3){bitmap.eraseColor(0);scene.drawForeground(canvas,HelmRenderValues())}
   assertTrue(reference.sameAs(bitmap))
   assertEquals("No repeated Paint allocation or native measurements for the same scene",before,scene.textCacheStats())
   bitmap.recycle();reference.recycle()
  }
 }
 @Test fun cachedPaintRestoresStyleAlphaAlignmentAndStretch() {
  val scene=HelmScene(RuntimeEnvironment.getApplication(),false)
  val actual=Bitmap.createBitmap(600,120,Bitmap.Config.ARGB_8888)
  for(stretch in listOf(.7f,1f,1.7f)) for(outline in listOf(0f,2f)) for(opacity in listOf(.4f,1f)) {
   scene.text(Canvas(actual),"99.9",300f,70f,32f,"hero",Color.RED,"right",1.9f,
    opacity=.3f,outline=3f,numericSlots=NumericSlots(3,1))
   actual.eraseColor(0)
   scene.text(Canvas(actual),"HELM 19",300f,70f,32f,"hero",Color.RED,"center",stretch,
    opacity=opacity,outline=outline)
   val expected=Bitmap.createBitmap(600,120,Bitmap.Config.ARGB_8888)
   HelmScene(RuntimeEnvironment.getApplication(),false).text(Canvas(expected),"HELM 19",300f,70f,32f,"hero",Color.RED,"center",stretch,
    opacity=opacity,outline=outline)
   assertTrue(expected.sameAs(actual));expected.recycle()
  }
  actual.recycle()
 }
 @Test fun changingTextCannotGrowMeasurementCacheWithoutBound() {
  val scene=HelmScene(RuntimeEnvironment.getApplication(),false)
  val bitmap=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888)
  val canvas=Canvas(bitmap)
  repeat(700){scene.text(canvas,"value=$it",0f,0f,12f,"unit",Color.BLACK)}
  val stats=scene.textCacheStats();assertTrue(stats[0]<=128);assertTrue(stats[1]<=512);assertTrue(stats[2]<=512)
  bitmap.recycle()
 }
}
