package com.no5.helm.numeric

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.LruCache
import android.os.LocaleList
import java.util.Collections

/** Uses the existing Typeface's outlines, centered in fixed glyph cells even without OpenType tnum. */
object NativeNumericText {
    data class Glyph(val text: String,val x: Float,val width: Float,val visible: Boolean,val scale: Float = 1f)
    data class Run(val glyphs: List<Glyph>,val width: Float)
    // Measurement state only: color/alpha/alignment are applied when drawing, not baked into Run.
    private data class FontKey(val typeface: Typeface?,val size: Float,val stretch: Float,
        val skew: Float,val letterSpacing: Float,val wordSpacing: Float,val flags: Int,
        val hinting: Int,val locales: LocaleList,val features: String?,val variations: String?,
        val elegant: Boolean) {
        constructor(p: Paint): this(p.typeface,p.textSize,p.textScaleX,p.textSkewX,p.letterSpacing,
            p.wordSpacing,p.flags,p.hinting,p.textLocales,p.fontFeatureSettings,
            p.fontVariationSettings,p.isElegantTextHeight)
    }
    private data class RunKey(val font: FontKey,val value: String,val slots: NumericSlots,
        val following: NumericSlots)
    private const val RUN_CAPACITY=256
    private const val MAX_CACHED_VALUE_LENGTH=256
    private val runs=LruCache<RunKey,Run>(RUN_CAPACITY)
    private data class Metrics(val pitch: Float,val bounds: Map<Char,Pair<Float,Float>>)
    private val cache=LruCache<FontKey,Metrics>(48)
    private fun metrics(paint: Paint,key: FontKey): Metrics {
        cache.get(key)?.let { return it }
        return measureMetrics(paint).also { cache.put(key,it) }
    }
    private fun measureMetrics(paint:Paint):Metrics {
        val bounds=Rect()
        val glyphs="0123456789.+−-*—".associateWith { c->
            paint.getTextBounds(c.toString(),0,1,bounds)
            bounds.left.toFloat() to bounds.right.toFloat()
        }
        val pitch=('0'..'9').maxOf { maxOf(paint.measureText(it.toString()),glyphs.getValue(it).second-glyphs.getValue(it).first) }
        return Metrics(pitch,glyphs)
    }
    /** Cache access and miss construction are atomic; callers retain ownership of their Paint. */
    @Synchronized fun layout(value: String,paint: Paint,slots: NumericSlots,following: NumericSlots=slots): Run {
        val font=FontKey(paint)
        // Bound retained strings/cells as well as entry count; unexpected long values still render.
        val key=if(value.length<=MAX_CACHED_VALUE_LENGTH) RunKey(font,value,slots,following) else null
        key?.let { runs.get(it) }?.let { return it }
        return buildRun(value,paint,slots,following,metrics(paint,font)).also { run -> key?.let { runs.put(it,run) } }
    }
    private fun buildRun(value:String,paint:Paint,slots:NumericSlots,following:NumericSlots,metrics:Metrics):Run {
        val pitch=metrics.pitch
        var x=0f
        val glyphs=NumericCells.place(value,slots,following).map { cell->
            val width=if(cell.fixed) pitch*cell.widthFactor else paint.measureText(cell.text)
            val bounds=if(cell.fixed)metrics.bounds.getValue(cell.text.single()) else null
            val fit=if(bounds!=null)minOf(1f,width/(bounds.second-bounds.first).coerceAtLeast(1f)) else 1f
            val origin=if(bounds!=null) x+(width-(bounds.first+bounds.second)*fit)/2 else x
            Glyph(cell.text,origin,width,cell.visible,fit).also { x+=width }
        }
        return Run(Collections.unmodifiableList(glyphs),x)
    }

    /** Immutable measurement identity. Snapshotting isolates it from a caller's mutable draw Paint.
     * Each style has its own bounded cache and lock; worker calls never share mutable measurement Paint. */
    class NumericStyle internal constructor(source:Paint) {
        val styleId:Long=ids.incrementAndGet()
        private val measurement=Paint(source)
        private val measured=measureMetrics(measurement)
        private data class Key(val displayedValue:String,val slots:NumericSlots,val following:NumericSlots)
        private val cached=LruCache<Key,Run>(32)
        @Synchronized internal fun layout(value:String,slots:NumericSlots,following:NumericSlots):Run {
            val key=if(value.length<=MAX_CACHED_VALUE_LENGTH)Key(value,slots,following)else null
            key?.let{cached.get(it)}?.let{return it}
            return buildRun(value,measurement,slots,following,measured).also { run->key?.let{cached.put(it,run)} }
        }
    }
    private val ids=java.util.concurrent.atomic.AtomicLong()
    fun style(paint:Paint)=NumericStyle(paint)
    fun layout(value:String,style:NumericStyle,slots:NumericSlots,following:NumericSlots=slots):Run=style.layout(value,slots,following)
    fun draw(canvas: Canvas,value: String,x: Float,y: Float,paint: Paint,slots: NumericSlots,
        maxWidth: Float=Float.MAX_VALUE,following: NumericSlots=slots,style:NumericStyle?=null) {
        val run=if(style==null)layout(value,paint,slots,following)else style.layout(value,slots,following)
        val scale=minOf(1f,maxWidth/run.width.coerceAtLeast(1f))
        val oldAlign=paint.textAlign
        val left=when(oldAlign) {Paint.Align.LEFT->0f;Paint.Align.RIGHT->-run.width;else->-run.width/2}
        canvas.save();canvas.translate(x,y);canvas.scale(scale,scale);paint.textAlign=Paint.Align.LEFT
        for(glyph in run.glyphs) if(glyph.visible) {
            canvas.save();canvas.translate(left+glyph.x,0f);canvas.scale(glyph.scale,glyph.scale)
            canvas.drawText(glyph.text,0f,0f,paint);canvas.restore()
        }
        paint.textAlign=oldAlign;canvas.restore()
    }
}
