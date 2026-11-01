package com.no5.helm

import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/** CPU Canvas submission wall time only. Never GPU time, display latency or FPS. */
internal object SonarPaintDiagnostics {
    private val full=SonarPaintDurationRing()
    private val preview=SonarPaintDurationRing()
    private val fullLut=SonarPaintDurationRing()
    private val fullUpload=SonarPaintDurationRing()
    private val fullDrawRemainder=SonarPaintDurationRing()
    private val previewLut=SonarPaintDurationRing()
    private val previewUpload=SonarPaintDurationRing()
    private val previewDrawRemainder=SonarPaintDurationRing()
    private val fullCosts=SonarPaintCostBreakdown()
    private val previewCosts=SonarPaintCostBreakdown()
    fun record(preview:Boolean,elapsedNanos:Long) {
        if(preview)this.preview.record(elapsedNanos) else full.record(elapsedNanos)
    }
    /** Aligned per-draw walltime partitions. Zero LUT means its cached transfer was reused. */
    fun recordPhases(preview:Boolean,lutNanos:Long,recolorUploadNanos:Long,drawRemainderNanos:Long) {
        if(preview){previewLut.record(lutNanos);previewUpload.record(recolorUploadNanos);previewDrawRemainder.record(drawRemainderNanos)}
        else{fullLut.record(lutNanos);fullUpload.record(recolorUploadNanos);fullDrawRemainder.record(drawRemainderNanos)}
    }
    fun recordCosts(preview:Boolean,pack:Long,copy:Long,pairPrepare:Long,drawWithoutPair:Long,
        calls:Long,columns:Long,bytes:Long,misses:Long,visibleColumns:Long,aggregated:Boolean,bulkFallback:Boolean) {
        (if(preview)previewCosts else fullCosts).record(pack,copy,pairPrepare,drawWithoutPair,calls,columns,bytes,misses,visibleColumns,aggregated,bulkFallback)
    }
    fun dump(writer:PrintWriter) {
        writer.println("sonarPaint scope=CPU_CANVAS_SUBMISSION_WALL_TIME gpuTime=UNKNOWN fps=UNKNOWN")
        full.dump("full",writer);preview.dump("preview",writer)
        writer.println("sonarPaint phases=LUT_PREPARATION,RECOLOR_BITMAP_UPLOAD,DRAW_UI_REMAINDER zeros=CACHED_TRANSFER gpuTime=UNKNOWN")
        fullLut.dump("full.lut",writer);fullUpload.dump("full.recolorUpload",writer);fullDrawRemainder.dump("full.drawRemainder",writer)
        previewLut.dump("preview.lut",writer);previewUpload.dump("preview.recolorUpload",writer);previewDrawRemainder.dump("preview.drawRemainder",writer)
        writer.println("sonarPaint detail=MAIN_PACK,MAIN_BITMAP_COPY,PAIR_MISS_PREPARE,DRAW_WITHOUT_PAIR aggregation=0_RAW_1_TIMEBINS counters=PER_DRAW")
        fullCosts.dump("full",writer);previewCosts.dump("preview",writer)
    }
}

/** Fixed duration slots with sequence tags. Dump never takes a writer/render lock. */
internal class SonarPaintDurationRing {
    private val nanos=AtomicLongArray(64)
    private val sequences=AtomicLongArray(64)
    private val next=AtomicLong()
    init {for(index in 0 until 64)sequences.set(index,-1)}
    fun record(elapsedNanos:Long) {
        if(elapsedNanos<0)return
        val sequence=next.getAndIncrement()
        val index=sequence.toInt() and 63
        sequences.set(index,-1)
        nanos.set(index,elapsedNanos)
        sequences.set(index,sequence)
    }
    /** Only dump performs aggregate/string work; concurrently replaced slots are omitted. */
    fun dump(name:String,writer:PrintWriter,unit:String="Ns") {
        val end=next.get();val start=maxOf(0,end-64)
        var count=0;var sum=0L;var maximum=0L;var last=-1L;var overflow=false
        var sequence=start
        while(sequence<end) {
            val index=sequence.toInt() and 63
            if(sequences.get(index)==sequence) {
                val value=nanos.get(index)
                if(sequences.get(index)==sequence) {
                    count++;maximum=maxOf(maximum,value)
                    if(Long.MAX_VALUE-sum<value){sum=Long.MAX_VALUE;overflow=true}else sum+=value
                    if(sequence==end-1)last=value
                }
            }
            sequence++
        }
        writer.println("sonarPaint $name totalSamples=$end windowSamples=$count windowLimit=64 sum$unit=$sum last$unit=${if(last<0)"unknown" else last.toString()} max$unit=$maximum sumOverflow=$overflow")
    }
}

/** Fixed primitive rings. The main upload copy and cache-miss preparation are intentionally distinct. */
internal class SonarPaintCostBreakdown {
    private val pack=SonarPaintDurationRing();private val copy=SonarPaintDurationRing()
    private val pairs=SonarPaintDurationRing();private val draw=SonarPaintDurationRing()
    private val calls=SonarPaintDurationRing();private val columns=SonarPaintDurationRing();private val bytes=SonarPaintDurationRing()
    private val misses=SonarPaintDurationRing();private val visible=SonarPaintDurationRing();private val aggregated=SonarPaintDurationRing();private val fallback=SonarPaintDurationRing()
    fun record(pack:Long,copy:Long,pairPrepare:Long,drawWithoutPair:Long,calls:Long,columns:Long,bytes:Long,misses:Long,visibleColumns:Long,aggregated:Boolean,bulkFallback:Boolean) {
        this.pack.record(pack);this.copy.record(copy);pairs.record(pairPrepare);draw.record(drawWithoutPair)
        this.calls.record(calls);this.columns.record(columns);this.bytes.record(bytes);this.misses.record(misses);visible.record(visibleColumns)
        this.aggregated.record(if(aggregated)1 else 0);fallback.record(if(bulkFallback)1 else 0)
    }
    fun dump(prefix:String,writer:PrintWriter) {
        pack.dump("$prefix.pack",writer);copy.dump("$prefix.bitmapCopy",writer);pairs.dump("$prefix.pairPrepare",writer);draw.dump("$prefix.drawWithoutPair",writer)
        calls.dump("$prefix.uploadCalls",writer,"Count");columns.dump("$prefix.uploadedColumns",writer,"Count");bytes.dump("$prefix.uploadedBytes",writer,"Bytes")
        misses.dump("$prefix.pairMisses",writer,"Count");visible.dump("$prefix.visibleColumns",writer,"Count")
        aggregated.dump("$prefix.aggregated",writer,"Count");fallback.dump("$prefix.bulkFallback",writer,"Count")
    }
}
