package com.no5.helm.numeric

import android.graphics.*
import com.no5.helm.R
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
class NativeNumericTextTest {
    private fun paint(font: Int,size: Float=44f)=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface=RuntimeEnvironment.getApplication().resources.getFont(font);textSize=size;color=Color.BLACK
    }
    @Test fun repeatedRunsReuseLayoutWhileDrawOnlyPaintChangesDoNotInvalidate() {
        val p=paint(R.font.barlow_condensed_regular,47f)
        val slots=NumericSlots(4,1,true,true)
        val first=NativeNumericText.layout("12.3 V",p,slots)
        repeat(100) { assertSame(first,NativeNumericText.layout("12.3 V",p,slots)) }
        p.color=Color.RED;p.alpha=120;p.textAlign=Paint.Align.RIGHT
        assertSame(first,NativeNumericText.layout("12.3 V",p,slots))
        assertNotSame(first,NativeNumericText.layout("12.4 V",p,slots))
        assertNotSame(first,NativeNumericText.layout("12.3 V",p,NumericSlots(5,1,true,true)))
        assertNotSame(first,NativeNumericText.layout("12.3 V",p,slots,NumericSlots(2)))
        try {
            @Suppress("UNCHECKED_CAST")
            (first.glyphs as MutableList<NativeNumericText.Glyph>).clear()
            fail("Shared run must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }
    @Test @Config(sdk=[29,33,35]) fun shapingMutationsInvalidateAndRestoreTheOriginalRun() {
        val base=paint(R.font.barlow_condensed_semibold,49f)
        val slots=NumericSlots(3,1,true)
        val first=NativeNumericText.layout("-12.3 V",base,slots)
        val mutations=listOf<(Paint)->Unit>(
            { it.textSize=63f }, { it.textScaleX=.8f }, { it.textSkewX=-.3f },
            { it.letterSpacing=.1f }, { it.wordSpacing=3f }, { it.isFakeBoldText=!it.isFakeBoldText },
            { it.isLinearText=!it.isLinearText }, { it.isSubpixelText=!it.isSubpixelText },
            { it.hinting=if(it.hinting==Paint.HINTING_OFF) Paint.HINTING_ON else Paint.HINTING_OFF }, { it.textLocale=java.util.Locale.JAPAN },
            { it.fontFeatureSettings="tnum" }, { it.isElegantTextHeight=!it.isElegantTextHeight },
            { it.typeface=Typeface.MONOSPACE }
        )
        mutations.forEach { mutate ->
            val changed=Paint(base);mutate(changed)
            assertNotSame(first,NativeNumericText.layout("-12.3 V",changed,slots))
            assertSame(first,NativeNumericText.layout("-12.3 V",base,slots))
        }
    }
    @Test fun boundedCacheEvictsOldRunsAndDoesNotRetainOversizeInput() {
        val p=paint(R.font.barlow_condensed_regular,41f);val slots=NumericSlots(3)
        val first=NativeNumericText.layout("cache-first 123",p,slots)
        repeat(300) { NativeNumericText.layout("cache-entry $it",p,slots) }
        val replaced=NativeNumericText.layout("cache-first 123",p,slots)
        assertNotSame(first,replaced);assertEquals(first,replaced)
        val longValue="1".repeat(300)
        assertNotSame(NativeNumericText.layout(longValue,p,slots),NativeNumericText.layout(longValue,p,slots))
    }
    @Test fun concurrentCallersShareOneImmutableRun() {
        val p=paint(R.font.barlow_condensed_regular,51f);val slots=NumericSlots(4,1)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val start=java.util.concurrent.CountDownLatch(1)
            val jobs=(0 until 16).map {
                pool.submit<NativeNumericText.Run> {
                    val ownPaint=Paint(p);start.await()
                    NativeNumericText.layout("concurrent 123.4 m",ownPaint,slots)
                }
            }
            start.countDown()
            val runs=jobs.map { it.get(10,java.util.concurrent.TimeUnit.SECONDS) }
            runs.forEach { assertSame(runs.first(),it) }
        } finally { pool.shutdownNow() }
    }
    @Test @Config(sdk=[29,33,35]) fun coldAndCachedRenderingArePixelIdenticalAfterEviction() {
        val p=paint(R.font.barlow_condensed_extrabold,55f);val slots=NumericSlots(4,1,true,true)
        val image=Bitmap.createBitmap(400,100,Bitmap.Config.ARGB_8888)
        val cached=Bitmap.createBitmap(400,100,Bitmap.Config.ARGB_8888)
        try {
            for(value in listOf("-12.3* m","— V","9999.9 V","1.1 V")) {
                repeat(300) { NativeNumericText.layout("eviction $it",p,slots) }
                image.eraseColor(Color.TRANSPARENT);cached.eraseColor(Color.TRANSPARENT)
                NativeNumericText.draw(Canvas(image),value,15f,75f,p,slots,370f)
                NativeNumericText.draw(Canvas(cached),value,15f,75f,p,slots,370f)
                assertTrue(value,image.sameAs(cached))
            }
        } finally { image.recycle();cached.recycle() }
    }
    @Test fun actualBundledFontsSupportTabularFeatureButDefaultDigitsAreProportional() {
        for(font in listOf(R.font.barlow_condensed_extrabold,R.font.barlow_condensed_semibold,R.font.barlow_condensed_regular)) {
            val p=paint(font)
            assertNotEquals(p.measureText("1"),p.measureText("8"))
            p.fontFeatureSettings="tnum"
            val widths=('0'..'9').map { p.measureText(it.toString()) }
            widths.forEach { assertEquals(widths.first(),it,.01f) }
        }
    }
    @Test fun nativeCellWidthsAndDecimalUnitAnchorsStayFixedForActualFont() {
        for(font in listOf(R.font.barlow_condensed_extrabold,R.font.barlow_condensed_semibold,R.font.barlow_condensed_regular)) {
            val p=paint(font)
            val runs=listOf("0.0 V","1.1 V","7.7 V","8.8 V","9.9 V","10.0 V","99.9 V","100.0 V","999.9 V","1000.0 V","-1000.0 V","— V","9.9* V")
                .map { NativeNumericText.layout(it,p,NumericSlots(4,1,true,true)) }
            runs.forEach { run ->
                assertEquals(runs.first().width,run.width,.001f)
                assertEquals(runs.first().glyphs[5].x,run.glyphs[5].x,.001f)
                assertEquals(runs.first().glyphs.last().x,run.glyphs.last().x,.001f)
            }
        }
    }
    @Test fun nativeNumbersRemainInsideReservedSlotsAtMaximumValues() {
        val p=paint(R.font.barlow_condensed_extrabold,60f)
        for(value in listOf("0","1","7","8","9","10","99","100","999","1000","-99999.9","+99999.9","99999999999999999999.999999999","—","-99.9*")) {
            val image=Bitmap.createBitmap(320,100,Bitmap.Config.ARGB_8888)
            p.textAlign=Paint.Align.RIGHT
            NativeNumericText.draw(Canvas(image),value,300f,75f,p,NumericSlots(5,1,true,true),280f)
            var count=0
            for(y in 0 until image.height)for(x in 0 until image.width)if(Color.alpha(image.getPixel(x,y))>0) {
                count++;assertTrue("$value at $x",x in 19..301)
            }
            assertTrue(count>20);image.recycle()
        }
    }
    @Test fun exportNativeProportionalAndFixedCellComparison() {
        val samples=listOf("1.1","7.7","8.8","9.9","10.0","99.9","100.0","999.9","1000.0","-99.9","—","9.9*")
        val image=Bitmap.createBitmap(1200,1000,Bitmap.Config.ARGB_8888);val c=Canvas(image);c.drawColor(Color.WHITE)
        val p=paint(R.font.barlow_condensed_semibold,32f)
        c.drawText("BEFORE: proportional / value-dependent width",30f,40f,p)
        c.drawText("AFTER: fixed decimal, sign, stale and unit slots",620f,40f,p)
        p.textSize=52f
        samples.forEachIndexed { i,value ->
            val y=110f+i*72f
            p.textAlign=Paint.Align.RIGHT;c.drawText("$value V",530f,y,p)
            NativeNumericText.draw(c,"$value V",1150f,y,p,NumericSlots(4,1,true,true),510f)
        }
        val out=File(System.getProperty("no5.renderOutputDir"),"numeric").also{it.mkdirs()}
        File(out,"numeric-before-after.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
}
