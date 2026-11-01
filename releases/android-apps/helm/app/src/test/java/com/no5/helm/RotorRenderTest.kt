package com.no5.helm

import android.graphics.*
import com.no5.domain.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RotorRenderTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun bitmap()=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
    private val now=120000L
    private val history=DepthHistorySnapshot((60..120).map{DepthHistoryPoint(it*1000L,12.4+sin((it-60)*.145)*1.55+sin((it-60)*.61)*.21,it==60)},0,now,120000)
    @Test fun exportApprovedRotorWithRealNativeFontsAndTransparentInterior() {
        val out=File(System.getProperty("no5.renderOutputDir"),"rotor").also{it.mkdirs()}
        for(night in listOf(false,true)) {
            val b=bitmap();val state=HelmState(speed=67.4,depth=12.4)
            SpeedDepthInstrument(context,night).draw(Canvas(b),state,history,DisplayUnits(),"LIVE",now,extrema=SpeedExtremaSnapshot(0.0,81.2))
            assertEquals(Color.TRANSPARENT,b.getPixel(1000,635))
            val crop=Bitmap.createBitmap(b,690,214,620,483)
            File(out,"rotor-${if(night)"night" else "day"}-native.png").outputStream().use{crop.compress(Bitmap.CompressFormat.PNG,100,it)}
            val full=bitmap();val scene=HelmScene(context,night);val canvas=Canvas(full)
            scene.drawBackground(canvas,com.no5.helm.ocean.BackgroundMode.IMAGE,false,com.no5.helm.ocean.OceanFrame())
            scene.drawForeground(canvas,renderValues(state,now),state,history,DisplayUnits(),SpeedExtremaSnapshot(0.0,81.2))
            File(out,"helm-${if(night)"night" else "day"}-rotor.png").outputStream().use{full.compress(Bitmap.CompressFormat.PNG,100,it)}
            if(night)for(y in 0 until crop.height)for(x in 0 until crop.width){val c=crop.getPixel(x,y);assertTrue(Color.red(c)>=Color.green(c)&&Color.red(c)>=Color.blue(c))}
            full.recycle();crop.recycle();b.recycle()
        }
    }
    @Test fun inkBoundsNeverOverlapAndCellsStayFixedAtOneFontSize() {
        val instrument=SpeedDepthInstrument(context,false)
        val values=listOf(0.0,1.0,7.0,8.0,9.0,10.0,27.1,99.9,100.0,999.9,null)
        for(field in listOf(instrument.speedNumerals,instrument.depthNumerals,instrument.extremaNumerals)) {
            val size=field.textSize
            for(value in values) {
                val run=field.layout(value)
                assertEquals(size,field.textSize,0f)
                assertTrue(run.width<=field.maximumWidth+.1f)
                for(pair in run.glyphs.zipWithNext())assertTrue("Outlined glyphs overlap at $value",pair.first.inkBounds.right<pair.second.inkBounds.left)
                val digits=run.glyphs.filter{it.character!='.'}
                if(value!=null)assertEquals("Number cell block remains centered",0f,(digits.first().inkBounds.centerX()+digits.last().inkBounds.centerX())/2,.001f)
            }
            // Same digit count cannot move origins/cell centers as glyph shapes change.
            val a=field.layout(11.1);val b=field.layout(77.7)
            assertEquals(a.width,b.width,0f)
            a.glyphs.zip(b.glyphs).forEach{(x,y)->assertEquals(x.inkBounds.centerX(),y.inkBounds.centerX(),.001f)}
            val dotA=field.layout(27.1).glyphs.single{it.character=='.'}
            val dotB=field.layout(99.9).glyphs.single{it.character=='.'}
            assertEquals(dotA.inkBounds,dotB.inkBounds)
        }
        for(unit in DepthUnit.entries)assertTrue(instrument.depthNumerals.layout(unit.fromMeters(12000.0)).width<=160.001f)
        // Invalid vessel speed is still unavailable; 999.9 above is only a typography fixture.
        assertNull(SpeedDepthInstrumentGeometry.reading(HelmState(speed=999.9),DisplayUnits(),"LIVE",now).speed)
    }

    @Test fun nativeOutlinedDigitsAndDecimalRemainSeparatePixelComponents() {
        val instrument=SpeedDepthInstrument(context,false)
        for((field,value) in listOf(instrument.speedNumerals to 27.1,instrument.depthNumerals to 13.0,instrument.extremaNumerals to 99.9)) {
            val image=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888)
            field.draw(Canvas(image),value,200f,150f,Color.WHITE,Color.BLACK)
            var before=false;var runs=0
            for(x in 0 until image.width) {
                val present=(0 until image.height).any{y->Color.alpha(image.getPixel(x,y))>0}
                if(present&&!before)runs++
                before=present
            }
            assertEquals("Three digits plus an isolated visible decimal for $value",4,runs)
            image.recycle()
        }
    }

    @Test fun historyIncludingLabelsHasPaddingFromTheActualInnerFlank() {
        val instrument=SpeedDepthInstrument(context,false);val box=instrument.historyBounds
        val measure=PathMeasure(instrument.flanks,false);val point=FloatArray(2)
        var minimum=Float.MAX_VALUE
        do {
            for(i in 0..1000){
                measure.getPosTan(measure.length*i/1000,point,null)
                val dx=maxOf(box.left-point[0],0f,point[0]-box.right)
                val dy=maxOf(box.top-point[1],0f,point[1]-box.bottom)
                minimum=minOf(minimum,kotlin.math.hypot(dx,dy))
            }
        } while(measure.nextContour())
        assertTrue("History/label box touches inner contour: $minimum",minimum>=8f)
    }

    @Test fun exportFixedPhysicalScaleBoundariesInAllUnits() {
        val out=File(System.getProperty("no5.renderOutputDir"),"rotor-scale").apply{mkdirs()}
        for(night in listOf(false,true)) {
            val sheet=Bitmap.createBitmap(1860,1932,Bitmap.Config.ARGB_8888);val target=Canvas(sheet)
            listOf(0.0,114.9,115.0,115.1).forEachIndexed{row,kmh->
                SpeedUnit.entries.forEachIndexed{column,unit->
                    val b=bitmap();val c=Canvas(b);val state=HelmState(speed=kmh/1.852,depth=13.0)
                    HelmScene(context,night).drawBackground(c,com.no5.helm.ocean.BackgroundMode.IMAGE,false,com.no5.helm.ocean.OceanFrame())
                    SpeedDepthInstrument(context,night).draw(c,state,history,DisplayUnits(speed=unit),"LIVE",now,extrema=SpeedExtremaSnapshot(0.0,81.2))
                    target.drawBitmap(b,Rect(690,214,1310,697),RectF(column*620f,row*483f,(column+1)*620f,(row+1)*483f),null);b.recycle()
                }
            }
            File(out,"boundaries-${if(night)"night" else "day"}.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)};sheet.recycle()
        }
    }

    @Test fun exportValueAndUnitMatrixOverOcean() {
        val out=File(System.getProperty("no5.renderOutputDir"),"rotor-correction").apply{mkdirs()}
        val values=listOf(0.0,1.0,7.0,8.0,9.0,10.0,27.1,99.9,100.0,999.9,null)
        for(night in listOf(false,true)) {
            val sheet=Bitmap.createBitmap(1860,1932,Bitmap.Config.ARGB_8888);val target=Canvas(sheet)
            values.forEachIndexed{i,value->
                val b=bitmap();val canvas=Canvas(b);val state=HelmState(speed=value?:0.0,depth=if(value==27.1)13.0 else value)
                val scene=HelmScene(context,night)
                scene.drawBackground(canvas,com.no5.helm.ocean.BackgroundMode.IMAGE,false,com.no5.helm.ocean.OceanFrame())
                val quality=if(value==null)"UNAVAILABLE" else "LIVE"
                SpeedDepthInstrument(context,night).draw(canvas,state,history,DisplayUnits(),quality,now,extrema=SpeedExtremaSnapshot(0.0,99.9))
                target.drawBitmap(b,Rect(690,214,1310,697),RectF((i%3)*620f,(i/3)*483f,(i%3+1)*620f,(i/3+1)*483f),null)
                b.recycle()
            }
            File(out,"values-${if(night)"night" else "day"}.png").outputStream().use{sheet.compress(Bitmap.CompressFormat.PNG,100,it)};sheet.recycle()
            for(speed in SpeedUnit.entries)for(depth in DepthUnit.entries) {
                val b=bitmap();val c=Canvas(b);val state=HelmState(speed=27.1,depth=13.0);val units=DisplayUnits(speed=speed,depth=depth)
                val scene=HelmScene(context,night)
                scene.drawBackground(c,com.no5.helm.ocean.BackgroundMode.IMAGE,false,com.no5.helm.ocean.OceanFrame())
                scene.drawForeground(c,renderValues(state,now),state,history,units,SpeedExtremaSnapshot(0.0,99.9))
                File(out,"helm-${if(night)"night" else "day"}-${speed.name}-${depth.name}.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
            }
        }
    }
    @Test fun boundedLabelLayoutsReuseMeasurementsAndInvalidateAllMetricInputs() {
        val cache=InstrumentTextLayouts(4)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val font=Typeface.create("sans-serif-condensed",Typeface.BOLD)
        val first=cache.get("SPEED",font,19f,1.2f,paint)
        assertSame(first,cache.get("SPEED",font,19f,1.2f,paint))
        assertEquals(paint.measureText("SPEED")+1.2f*4,first.width,0f)
        for(i in first.glyphs.indices)assertEquals(paint.measureText(first.glyphs[i])+1.2f,first.advances[i],0f)
        assertNotSame(first,cache.get("SPEED",font,20f,1.2f,paint))
        assertNotSame(first,cache.get("SPEED",font,19f,.2f,paint))
        assertNotSame(first,cache.get("SPEED",Typeface.MONOSPACE,19f,1.2f,paint))
        cache.get("CHANGED",font,19f,1.2f,paint)
        assertEquals(4,cache.size)
        assertNotSame(first,cache.get("SPEED",font,19f,1.2f,paint))
        assertEquals(4,cache.size)
    }

    @Test fun warmGaugeCacheMatchesColdRenderAfterUnitsAndStatusChanges() {
        for(night in listOf(false,true)) {
            val instrument=SpeedDepthInstrument(context,night)
            for(unit in SpeedUnit.entries)for(status in listOf("LIVE","STALE","UNAVAILABLE")) {
                val a=bitmap();val b=bitmap()
                val state=HelmState(speed=27.1,depth=13.0)
                val units=DisplayUnits(speed=unit)
                instrument.draw(Canvas(a),state,history,units,status,now)
                SpeedDepthInstrument(context,night).draw(Canvas(b),state,history,units,status,now)
                assertTrue("Warm vs cold: $night $unit $status",a.sameAs(b))
                assertTrue(instrument.textLayouts.size<=64)
                a.recycle();b.recycle()
            }
        }
    }

}
