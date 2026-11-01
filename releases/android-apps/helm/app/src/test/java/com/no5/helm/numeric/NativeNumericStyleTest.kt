package com.no5.helm.numeric

import android.graphics.Paint
import android.graphics.Typeface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeNumericStyleTest {
    @Test fun immutableStyleMatchesLegacyAndDoesNotFollowMutablePaint() {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=37f;typeface=Typeface.DEFAULT_BOLD;textScaleX=1.3f}
        val style=NativeNumericText.style(paint)
        val slots=NumericSlots(4,1,true,true)
        for(value in listOf("0.0","—","-9.9","999.9*","1.0")) {
            assertEquals(NativeNumericText.layout(value,paint,slots),NativeNumericText.layout(value,style,slots))
        }
        val before=NativeNumericText.layout("12.3",style,slots)
        paint.textSize=100f;paint.textScaleX=.5f
        assertSame(before,NativeNumericText.layout("12.3",style,slots))
        assertNotEquals(before,NativeNumericText.layout("12.3",paint,slots))
    }
    @Test fun parallelLookupsUseStyleOwnedLockAndImmutableRuns() {
        val style=NativeNumericText.style(Paint().apply{textSize=30f})
        val slots=NumericSlots(4,1)
        val expected=NativeNumericText.layout("123.4",style,slots)
        val pool=Executors.newFixedThreadPool(4)
        try {val futures=(0..31).map{pool.submit<NativeNumericText.Run>{NativeNumericText.layout("123.4",style,slots)}}
            futures.forEach{assertSame(expected,it.get())}
        } finally {pool.shutdownNow()}
    }
}
