package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import com.no5.domain.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The identical fixture is also run against untouched master for external byte/pixel comparison. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HelmParityMatrixTest {
    @Test fun nativeDayNightUnitsFieldFreshnessUnknownZeroExtremesAndHistoryGaps() {
        val now=System.currentTimeMillis()
        val out=File(System.getProperty("no5.renderOutputDir"),"retained-parity-${android.os.Build.VERSION.SDK_INT}").apply{mkdirs()}
        for(night in listOf(false,true))for(unit in SpeedUnit.entries)for(status in TelemetryStatus.entries) {
            val q=TelemetryQuality(status,now-1000,"PARITY_FIXTURE",demo=true)
            val state=HelmState(portRpm=1.0,starboardRpm=7000.0,speed=0.0,depth=12000.0,heading=359.5,
                portTrim=0.0,starboardTrim=100.0,quality=q,
                telemetryFields=mapOf("portRpm" to TelemetryValue(1.0,q),"starboardRpm" to TelemetryValue(7000.0,q),
                    "speed" to TelemetryValue(0.0,q),"depth" to TelemetryValue(12000.0,q),"heading" to TelemetryValue(359.5,q),
                    "portTrim" to TelemetryValue(0.0,q),"starboardTrim" to TelemetryValue(100.0,q),
                    "portOilBar" to TelemetryValue(0.0,q.copy(status=TelemetryStatus.STALE)),
                    "portCoolantC" to TelemetryValue(null,q),"portFuel" to TelemetryValue(100.0,q)))
            val units=DisplayUnits(speed=unit,depth=if(unit==SpeedUnit.MPH)DepthUnit.FEET else DepthUnit.METERS)
            val history=DepthHistorySnapshot(listOf(DepthHistoryPoint(now-55000,8.0,true),DepthHistoryPoint(now-45000,0.0,false),
                DepthHistoryPoint(now-5000,12.0,true),DepthHistoryPoint(now-1000,3.0,false)),now-120000,now,120000)
            val scene=HelmScene(RuntimeEnvironment.getApplication(),night)
            val bitmap=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
            scene.drawForeground(Canvas(bitmap),renderValues(state,now,units=units),state,history,units,SpeedExtremaSnapshot(0.0,200.0))
            File(out,"${if(night)"night" else "day"}-${unit.name}-${status.name}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
}
