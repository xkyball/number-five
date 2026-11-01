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
class QuietBenchmarkActivity:Activity() {
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
        val common=intent.getBooleanExtra("common",false);commonMatrix=common
        for(round in 0..2)for(auto in listOf(false,true))for(count in if(common)listOf(2048)else listOf(4800,12000)) {
            val order=if(common)listOf("canvas","gpu195","gpuQuiet")else listOf("gpu195","gpuQuiet")
            repeat(order.size){i->cases+=Case(order[(i+round)%order.size],count,auto,round)}
        }
        nextCase()
    }
    private fun nextCase(){
        current?.close();current=null;root.removeAllViews()
        if(cancelled)return
        if(SystemClock.elapsedRealtime()>=deadline){finishReport("MATRIX_DEADLINE");return}
        if(caseIndex>=cases.size){finishReport(null);return}
        val c=cases[caseIndex++]
        Trace.setCounter("quiet.case",caseIndex.toLong())
        // Canvas uses its actual bounded reference renderer, within 2048 supported pings.
        if(c.path=="canvas"){runCanvas(c);return}
        val ring=SonarRingBuffer(c.count)
        val auto=if(c.auto&&c.path=="gpuQuiet")SonarImageAuto()else null;val oldAuto=if(c.auto&&c.path=="gpu195")BaselineImageAuto()else null
        var values=SonarImageValues(0.0,1.0,0.0)
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
            if(id in 40..239 && starts[id.toInt()]>0 && stamp.pingId!=lastPresented)latencies+=System.nanoTime()-starts[id.toInt()]
            lastPresented=stamp.pingId
            val now=System.nanoTime();if(gap>0)maxGap=max(maxGap,now-gap);gap=now
        }
        if(c.path=="gpu195"){
            val v=BaselineGpuView(this);view=v;current=v;submit=v::submit;error={v.lastError};ready={v.pipelinePrepared};v.onTexturePresented=done
        }else{
            val v=SonarGpuView(this);view=v;current=v;submit=v::submit;error={v.lastError};ready={v.pipelinePrepared};v.onTexturePresented=done
        }
        root.addView(view,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        fun display():SonarDisplay=SonarDisplay(historySeconds=c.count/20.0,gainDb=values.gainDb,gamma=values.gamma,threshold=values.threshold,
            gainMode=if(c.auto&&c.path=="gpuQuiet")SonarImageMode.AUTO else SonarImageMode.MANUAL,
            gammaMode=if(c.auto&&c.path=="gpuQuiet")SonarImageMode.AUTO else SonarImageMode.MANUAL,
            thresholdMode=if(c.auto&&c.path=="gpuQuiet")SonarImageMode.AUTO else SonarImageMode.MANUAL)
        fun input(id:Int){
            val time=1000000L+id*50L
            val f=profile(id,time)
            ring.push(f,pingSource=SonarPingSource.OWN,amplitude=SonarAmplitude(4095,12,true),amplitudeFullScale=4095,imageMapping=values,localReceivedAtNanos=System.nanoTime())
            if(c.auto)values=(if(c.path=="gpu195")oldAuto?.observe(f,4095,time,values)else auto?.observe(f,4095,time,values,historySeconds=c.count/20.0))?:values
        }
        repeat(c.count){input(it)}
        fun frame()=SonarGpuFrame(ring,ring.lifecycleGeneration,ring.version,"synthetic-fixed-source",display(),null,4095,0.0,1023.0,true,false)
        submit(frame())
        val prepareDeadline=SystemClock.elapsedRealtime()+10000
        fun start(){
            if(error()!=null){finishReport(error());return}
            if(!ready()){
                if(SystemClock.elapsedRealtime()>prepareDeadline){finishReport("WINDOW_PREPARE_TIMEOUT");return}
                main.postDelayed({start()},10);return
            }
            wallStart=SystemClock.uptimeMillis()
            fun update(){
                if(cancelled)return
                if(error()!=null){finishReport(error());return}
                if(updates==240){
                    cpuEnd=Process.getElapsedCpuTime()
                    val target=c.count+239L
                    val until=SystemClock.elapsedRealtime()+2000
                    fun settle(){
                        if(lastPresented<target && SystemClock.elapsedRealtime()<until){main.postDelayed({settle()},5);return}
                        val sorted=latencies.sorted()
                        fun p(q:Double)=if(sorted.isEmpty())JSONObject.NULL else sorted[(ceil(sorted.size*q).toInt()-1).coerceIn(sorted.indices)]/1e6
                        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
                        results.put(JSONObject().put("path",c.path).put("pings",c.count).put("auto",c.auto).put("round",c.round)
                            .put("processed",200).put("trace_case",caseIndex).put("measure_start_boottime_ns",measuredStart).put("measure_end_boottime_ns",SystemClock.elapsedRealtimeNanos()).put("app_cpu_ms_per_ping",(cpuEnd-cpuStart)/200.0).put("presented_samples",sorted.size)
                            .put("local_to_window_p95_ms",p(.95)).put("local_to_window_p99_ms",p(.99)).put("max_window_gap_ms",maxGap/1e6)
                            .put("last_ping_presented",lastPresented==target).put("passed",lastPresented==target&&maxGap<=2000000000L).put("pss_kib",info.totalPss).put("java_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                            .put("native_heap_bytes",Debug.getNativeHeapAllocatedSize()).put("gpu_time_ms",JSONObject.NULL))
                        File(getExternalFilesDir(null),"quiet-matrix-progress-$commonMatrix.json").writeText(JSONObject().put("cases",results).put("complete",false).toString())
                        main.postDelayed({nextCase()},250)
                    };settle();return
                }
                if(updates==40){cpuStart=Process.getElapsedCpuTime();measuredStart=SystemClock.elapsedRealtimeNanos()}
                input(c.count+updates);submit(frame());updates++
            }
            val producer=HandlerThread("quiet-fixture-input").apply{start()}
            val receiver=Handler(producer.looper)
            val permits=java.util.concurrent.Semaphore(64)
            var admitted=0
            fun receive(){
                if(cancelled){producer.quitSafely();return}
                if(admitted==240){main.post{update()};producer.quitSafely();return}
                permits.acquire();val index=admitted++;starts[index]=System.nanoTime()
                main.post{try{update()}finally{permits.release()}}
                receiver.postAtTime({receive()},wallStart+admitted*50L)
            };receiver.post{receive()}
        };main.postDelayed({start()},20)
    }
    private fun runCanvas(c:Case){
        // Actual current Canvas renderer; source API and data remain fixture-only.
        val port=DeterministicMockSonarSource(now={1010000L})
        val controller=com.no5.helm.SonarNativeController(port){1010000L}
        controller.model.connect("SYNTHETIC CANVAS");controller.model.acquisition(true)
        controller.model.setDisplay(SonarDisplay(historySeconds=102.4))
        val r=controller.model.ring
        val a=BaselineImageAuto();var values=SonarImageValues(0.0,1.0,0.0)
        val renderer=com.no5.helm.SonarNativeRenderer(this)
        val lat=ArrayList<Long>();var input=0L;var count=0;var startCpu=0L;var committedInput=0L
        val view=object:View(this){override fun onDraw(canvas:android.graphics.Canvas){
            super.onDraw(canvas);renderer.drawPlotForProof(canvas,controller,false,android.graphics.RectF(0f,0f,width.toFloat(),height.toFloat()))
            val admitted=input;val measured=count>40
            viewTreeObserver.registerFrameCommitCallback{if(measured && admitted>committedInput){lat+=System.nanoTime()-admitted;committedInput=admitted}}
        }}
        current=AutoCloseable{renderer.dispose();controller.dispose()};root.addView(view,FrameLayout.LayoutParams(-1,-1))
        fun add(id:Int){val f=profile(id,1000000L+id*50L);r.push(f,amplitudeFullScale=4095,pingSource=SonarPingSource.OWN)
            if(c.auto)values=a.observe(f,4095,f.timestampEpochMs,values)?:values
        }
        repeat(c.count){add(it)}
        val start=SystemClock.uptimeMillis()
        val arrivals=LongArray(240)
        fun update(){
            if(count==240){
                val cpu=(Process.getElapsedCpuTime()-startCpu)/200.0
                main.postDelayed({val s=lat.sorted();fun p(q:Double):Any=if(s.isEmpty())JSONObject.NULL else s[(ceil(q*s.size).toInt()-1).coerceIn(s.indices)]/1e6
                    results.put(JSONObject().put("path","canvas").put("pings",2048).put("auto",c.auto).put("round",c.round).put("processed",200).put("app_cpu_ms_per_ping",cpu).put("presented_samples",s.size).put("local_to_window_p95_ms",p(.95)).put("local_to_window_p99_ms",p(.99)).put("gpu_time_ms",JSONObject.NULL));nextCase()},100);return
            }
            if(count==40)startCpu=Process.getElapsedCpuTime()
            input=arrivals[count];add(c.count+count)
            controller.model.setDisplay(controller.model.display.copy(gainDb=values.gainDb,gamma=values.gamma,threshold=values.threshold));controller.refresh();view.invalidate();count++
        }
        val producer=HandlerThread("quiet-canvas-input").apply{start()};val receiver=Handler(producer.looper)
        val permits=java.util.concurrent.Semaphore(64);var admitted=0
        fun receive(){
            if(cancelled){producer.quitSafely();return}
            if(admitted==240){main.post{update()};producer.quitSafely();return}
            permits.acquire();val index=admitted++;arrivals[index]=System.nanoTime()
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
        File(getExternalFilesDir(null),"quiet-matrix-$commonMatrix.json").writeText(JSONObject().put("schema","no5.quiet-gpu-matrix/1").put("failure",failure?:JSONObject.NULL).put("cases",results).put("synthetic",true).put("qualification_seconds",0).toString())
        root.addView(TextView(this).apply{text=if(failure==null)"QUIET_MATRIX_DONE" else "QUIET_MATRIX_FAILED";contentDescription=text})
    }
    override fun onDestroy(){cancelled=true;main.removeCallbacksAndMessages(null);current?.close();current=null;super.onDestroy()}
}
