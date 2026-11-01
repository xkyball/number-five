package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import com.no5.domain.*
import com.no5.helm.ocean.BackgroundMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Host software Canvas only. No device FPS/CPU claim or timing assertion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HelmPerformanceProbeTest {
 @Test fun repeatedForegroundHostTiming() {
  val out=File(System.getProperty("no5.renderOutputDir"));out.mkdirs()
  val records=mutableListOf<String>()
  for(night in listOf(false,true)) {
   val scene=HelmScene(RuntimeEnvironment.getApplication(),night)
   val state=HelmState(speed=15.0,depth=12.9,heading=36.0,
    quality=TelemetryQuality(TelemetryStatus.LIVE,source="BENCHMARK",demo=true))
   val values=renderValues(state,120_000L)
   val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
   val canvas=Canvas(bitmap)
   repeat(12){bitmap.eraseColor(0);scene.drawForeground(canvas,values,state)}
   val times=LongArray(40){
    bitmap.eraseColor(0)
    val start=System.nanoTime();scene.drawForeground(canvas,values,state);System.nanoTime()-start
   }.sorted()
   records+="\"${if(night)"night" else "day"}\":{\"median_ms\":${times[20]/1e6},\"p95_ms\":${times[37]/1e6},\"frames\":40}"
   File(out,"perf-${if(night)"night" else "day"}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
   bitmap.recycle()
  }
  File(out,"helm-host-timing.json").writeText("{${records.joinToString(",")}}")
 }
}
