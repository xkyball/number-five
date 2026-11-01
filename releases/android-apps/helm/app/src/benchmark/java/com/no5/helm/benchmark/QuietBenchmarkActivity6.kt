package com.no5.helm.benchmark

import android.app.Activity
import android.os.*
import android.view.*
import android.widget.*
import com.no5.domain.*
import com.no5.helm.gpu.*
import org.json.*
import java.io.File
import kotlin.math.*

/** Isolated synthetic input; never connects to a gateway or reads production preferences. */
class QuietBenchmarkActivity6:Activity() {
    private val main=Handler(Looper.getMainLooper())
    private var current:AutoCloseable?=null
    @Volatile private var cancelled=false
    private lateinit var root:FrameLayout
    private var caseIndex=0
    private var deadline=0L
    private var commonMatrix=false
    private val results=JSONArray()
    private data class Case(val path:String,val count:Int,val auto:Boolean,val round:Int)
    private val cases=ArrayList<Case>()
    override fun onCreate(state:Bundle?){super.onCreate(state);root=FrameLayout(this);setContentView(root)
        deadline=SystemClock.elapsedRealtime()+900000
        val common=false;commonMatrix=false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Run7 baseline12000 startup gap remains a FAIL and is not retried.
        for(round in 0..2)for(auto in listOf(false,true))for(count in listOf(2048,4800)) {
            val order=listOf("gpu195","gpuQuiet")
            repeat(order.size){i->cases+=Case(order[(i+round)%order.size],count,auto,round)}
        }
        // New12000 is checked separately; it has no passing matched baseline.
        for(round in 0..2)for(auto in listOf(false,true))cases+=Case("gpuQuiet",12000,auto,round)
        nextCase()
    }
    private fun nextCase(){
        current?.close();current=null;root.removeAllViews()
        val until=SystemClock.elapsedRealtime()+2000
        fun cleanup(){
            if(cancelled)return
            val released=SonarGpuNative.resources().all{it==0L}&&BaselineGpuNative.resources().all{it==0L}
            if(!released){
                if(SystemClock.elapsedRealtime()>=until){finishReport("CASE_CLEANUP_TIMEOUT");return}
                main.postDelayed({cleanup()},5);return
            }
            beginCase()
        };cleanup()
    }
    private fun beginCase(){
        if(cancelled)return
        if(SystemClock.elapsedRealtime()>=deadline){finishReport("MATRIX_DEADLINE");return}
        if(caseIndex>=cases.size){finishReport(null);return}
        val c=cases[caseIndex++]
        Trace.setCounter("quiet.case",caseIndex.toLong())
        // Canvas uses its actual bounded reference renderer, within 2048 supported pings.
        if(c.path=="canvas"){runCanvas(c);return}
        val feed=QuietBenchmarkFeed3(c.path,c.count,c.auto,::profile);val ring=feed.controller.model.ring
        val starts=LongArray(240)
        val latencies=ArrayList<Long>()
        var lastPresented=-1L;var gap=0L;var maxGap=0L
        var updates=0;var cpuStart=0L;var cpuEnd=0L;var wallStart=0L;var measuredStart=0L
        var view:View
        var submit:(SonarGpuFrame)->Unit
        var error:()->String?
        var ready:()->Boolean
        val done:(SonarGpuCompletion)->Unit={stamp ->
            val id=stamp.pingId-c.count
            val committed=System.nanoTime()
            // Every newly included ping gets its own arrival-to-Window time,
            // including pings incorporated together between two displayed frames.
            val first=maxOf(40L,lastPresented-c.count+1L)
            for(index in first..minOf(id,239L))if(starts[index.toInt()]>0)latencies+=committed-starts[index.toInt()]
            lastPresented=stamp.pingId
            val now=System.nanoTime();if(gap>0)maxGap=max(maxGap,now-gap);gap=now
        }
        if(c.path=="gpu195"){
            val v=BaselineGpuView(this);view=v;current=AutoCloseable{v.close();feed.close()};submit=v::submit;error={v.lastError};ready={v.pipelinePrepared};v.onTexturePresented=done
        }else{
            val v=SonarGpuView(this);view=v;current=AutoCloseable{v.close();feed.close()};submit=v::submit;error={v.lastError};ready={v.pipelinePrepared};v.onTexturePresented=done
        }
        root.addView(view,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        fun display():SonarDisplay=SonarDisplay(historySeconds=c.count/20.0,gainDb=feed.values.gainDb,gamma=feed.values.gamma,threshold=feed.values.threshold,
            gainMode=if(c.auto&&c.path=="gpuQuiet")SonarImageMode.AUTO else SonarImageMode.MANUAL,
            gammaMode=if(c.auto&&c.path=="gpuQuiet")SonarImageMode.AUTO else SonarImageMode.MANUAL,
            thresholdMode=if(c.auto&&c.path=="gpuQuiet")SonarImageMode.AUTO else SonarImageMode.MANUAL)
        fun input(id:Int){feed.input(id)}
        fun frame()=SonarGpuFrame(ring,ring.lifecycleGeneration,ring.version,"synthetic-fixed-source",display(),null,4095,0.0,1023.0,true,false)
        submit(frame())
        val prepareDeadline=SystemClock.elapsedRealtime()+10000
        fun beginPacedInputs(){
            if(error()!=null){finishReport(error());return}
            if(!ready()){
                if(SystemClock.elapsedRealtime()>prepareDeadline){run {
                    val facts=JSONObject().put("case",caseIndex).put("path",c.path).put("pings",c.count).put("auto",c.auto).put("available",(view as TextureView).isAvailable).put("width",view.width).put("height",view.height)
                    val stacks=JSONArray();Thread.getAllStackTraces().filterKeys{it.name.contains("sonar",true)||it.name=="RenderThread"}.forEach{(t,st)->stacks.put(JSONObject().put("thread",t.name).put("state",t.state.toString()).put("stack",JSONArray(st.take(12).map{it.toString()})))}
                    facts.put("stacks",stacks);File(getExternalFilesDir(null),"quiet-prepare-failure.json").writeText(facts.toString());finishReport("WINDOW_PREPARE_TIMEOUT")
                };return}
                main.postDelayed({beginPacedInputs()},10);return
            }
            // Match production startup: prepare the empty owned pipeline first.
            // Prefill is still outside measured input; the following 40 updates warm both paths equally.
            repeat(c.count){input(it)}
            submit(frame())
            wallStart=SystemClock.uptimeMillis()
            fun update(){
                if(cancelled)return
                if(error()!=null){finishReport(error());return}
                if(updates==240){
                    val target=c.count+239L
                    val until=SystemClock.elapsedRealtime()+2000
                    fun settle(){
                        if(lastPresented<target && SystemClock.elapsedRealtime()<until){main.postDelayed({settle()},5);return}
                        cpuEnd=Process.getElapsedCpuTime()
                        val sorted=latencies.sorted()
                        fun p(q:Double)=if(sorted.isEmpty())JSONObject.NULL else sorted[(ceil(sorted.size*q).toInt()-1).coerceIn(sorted.indices)]/1e6
                        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
                        results.put(JSONObject().put("path",c.path).put("pings",c.count).put("auto",c.auto).put("round",c.round)
                            .put("processed",200).put("trace_case",caseIndex).put("measure_start_boottime_ns",measuredStart).put("measure_end_boottime_ns",SystemClock.elapsedRealtimeNanos()).put("app_cpu_ms_per_ping",(cpuEnd-cpuStart)/200.0).put("presented_samples",sorted.size)
                            .put("local_to_window_p95_ms",p(.95)).put("local_to_window_p99_ms",p(.99)).put("max_local_to_window_ms",if(sorted.isEmpty())JSONObject.NULL else sorted.last()/1e6).put("max_window_gap_ms",maxGap/1e6)
                            .put("last_ping_presented",lastPresented==target).put("passed",lastPresented==target&&maxGap<=2000000000L&&sorted.size==200&&sorted.last()<=2000000000L).put("pss_kib",info.totalPss).put("java_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                            .put("native_heap_bytes",Debug.getNativeHeapAllocatedSize()).put("gpu_time_ms",JSONObject.NULL))
                        File(getExternalFilesDir(null),"quiet-matrix-progress-$commonMatrix.json").writeText(JSONObject().put("cases",results).put("complete",false).toString())
                        if(lastPresented!=target||maxGap>2000000000L||sorted.size!=200||sorted.lastOrNull()?.let{it>2000000000L}==true){finishReport("CASE_WINDOW_GATE_FAILED");return}
                        main.postDelayed({nextCase()},250)
                    };settle();return
                }
                if(updates==40){cpuStart=Process.getElapsedCpuTime();measuredStart=SystemClock.elapsedRealtimeNanos()}
                input(c.count+updates);submit(frame());updates++
            }
            val producer=HandlerThread("quiet-fixture-input").apply{this.start()}
            val receiver=Handler(producer.looper)
            val permits=java.util.concurrent.Semaphore(64)
            val queue=java.util.ArrayDeque<Int>()
            val queued=java.util.concurrent.atomic.AtomicBoolean(false)
            var admitted=0
            fun drain(){
                val pending=ArrayList<Int>()
                synchronized(queue){while(queue.isNotEmpty())pending+=queue.removeFirst()}
                try{
                    if(cancelled)return
                    for(index in pending){
                        if(index==40){cpuStart=Process.getElapsedCpuTime();measuredStart=SystemClock.elapsedRealtimeNanos()}
                        feed.input(c.count+index,starts[index],publish=false);updates++
                    }
                    if(pending.isNotEmpty()){feed.publish();submit(frame())}
                }finally{
                    permits.release(pending.size);queued.set(false)
                    if(synchronized(queue){queue.isNotEmpty()}&&queued.compareAndSet(false,true))main.post{drain()}
                }
                if(updates==240)update()
            }
            fun receive(){
                if(cancelled){producer.quitSafely();return}
                if(admitted==240){producer.quitSafely();return}
                val arrived=System.nanoTime();permits.acquire();val index=admitted++;starts[index]=arrived
                synchronized(queue){queue.addLast(index)}
                if(queued.compareAndSet(false,true))main.post{drain()}
                receiver.postAtTime({receive()},wallStart+admitted*50L)
            };receiver.post{receive()}
        };main.postDelayed({beginPacedInputs()},20)
    }
    private fun runCanvas(c:Case){
        // Actual current Canvas renderer; source API and data remain fixture-only.
        val feed=QuietBenchmarkFeed3(c.path,c.count,c.auto,::profile);val controller=feed.controller
        val renderer=com.no5.helm.SonarNativeRenderer(this)
        val lat=ArrayList<Long>();var input=0L;var count=0;var startCpu=0L;var committedIndex=39;var gap=0L;var maxGap=0L
        val arrivals=LongArray(240)
        val view=object:View(this){override fun onDraw(canvas:android.graphics.Canvas){
            super.onDraw(canvas);renderer.drawPlotForProof(canvas,controller,false,android.graphics.RectF(0f,0f,width.toFloat(),height.toFloat()))
            val last=count-1
            viewTreeObserver.registerFrameCommitCallback{
                val now=System.nanoTime()
                for(index in maxOf(40,committedIndex+1)..last)if(arrivals[index]>0)lat+=now-arrivals[index]
                committedIndex=maxOf(committedIndex,last)
                if(gap>0)maxGap=max(maxGap,now-gap);gap=now
            }
        }}
        current=AutoCloseable{renderer.dispose();feed.close()};root.addView(view,FrameLayout.LayoutParams(-1,-1))
        fun add(id:Int){feed.input(id)}
        repeat(c.count){add(it)}
        val start=SystemClock.uptimeMillis()
        fun update(){
            if(count==240){
                val until=SystemClock.elapsedRealtime()+2000
                fun settle(){
                    if(committedIndex<239&&SystemClock.elapsedRealtime()<until){main.postDelayed({settle()},5);return}
                    val cpu=(Process.getElapsedCpuTime()-startCpu)/200.0
                    val sorted=lat.sorted()
                    fun p(q:Double):Any=if(sorted.isEmpty())JSONObject.NULL else sorted[(ceil(q*sorted.size).toInt()-1).coerceIn(sorted.indices)]/1e6
                    val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
                    results.put(JSONObject().put("path","canvas").put("pings",2048).put("auto",c.auto).put("round",c.round).put("processed",200).put("app_cpu_ms_per_ping",cpu)
                        .put("presented_samples",sorted.size).put("local_to_window_p95_ms",p(.95)).put("local_to_window_p99_ms",p(.99)).put("max_local_to_window_ms",if(sorted.isEmpty())JSONObject.NULL else sorted.last()/1e6)
                        .put("max_window_gap_ms",maxGap/1e6).put("last_ping_presented",committedIndex==239).put("passed",committedIndex==239&&maxGap<=2000000000L&&sorted.size==200&&sorted.last()<=2000000000L)
                        .put("pss_kib",info.totalPss).put("native_heap_bytes",Debug.getNativeHeapAllocatedSize()).put("java_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()).put("gpu_time_ms",JSONObject.NULL))
                    if(committedIndex!=239||maxGap>2000000000L||sorted.size!=200||sorted.lastOrNull()?.let{it>2000000000L}==true){finishReport("CASE_WINDOW_GATE_FAILED");return}
                    nextCase()
                };settle();return
            }
            if(count==40)startCpu=Process.getElapsedCpuTime()
            input=arrivals[count];add(c.count+count)
            view.invalidate();count++
        }
        val producer=HandlerThread("quiet-canvas-input").apply{this.start()};val receiver=Handler(producer.looper)
        val permits=java.util.concurrent.Semaphore(64);var admitted=0
        fun receive(){
            if(cancelled){producer.quitSafely();return}
            if(admitted==240){main.post{update()};producer.quitSafely();return}
            val arrived=System.nanoTime();permits.acquire();val index=admitted++;arrivals[index]=arrived
            main.post{try{update()}finally{permits.release()}}
            receiver.postAtTime({receive()},start+100+admitted*50L)
        };receiver.postDelayed({receive()},100)
    }
    private fun profile(id:Int,time:Long):SonarFrame=SonarFrame(ShortArray(1024){bin ->
        val bottom=400+(id%101)/10
        (when {bin in bottom..bottom+12 -> 2800;bin in 200..202 -> 200+(id%13)*10;else -> 25+(bin*17+id*7)%40}).toShort()
    },id.toLong(),time)
    private fun finishReport(failure:String?){
        cancelled=true;current?.close();current=null;root.removeAllViews()
        File(getExternalFilesDir(null),"quiet-matrix-$commonMatrix.json").writeText(JSONObject().put("schema","no5.quiet-gpu-matrix/1").put("failure",failure?:JSONObject.NULL).put("cases",results).put("synthetic",true).put("baseline12000","retained run7 FAIL; not retried; new12000 unpaired").put("qualification_seconds",0).toString())
        root.addView(TextView(this).apply{text=if(failure==null)"QUIET_MATRIX_DONE" else "QUIET_MATRIX_FAILED";contentDescription=text})
    }
    override fun onPause(){
        if(!cancelled)finishReport("ACTIVITY_PAUSED_DURING_MATRIX")
        super.onPause()
    }
    override fun onDestroy(){cancelled=true;main.removeCallbacksAndMessages(null);current?.close();current=null;super.onDestroy()}
}
