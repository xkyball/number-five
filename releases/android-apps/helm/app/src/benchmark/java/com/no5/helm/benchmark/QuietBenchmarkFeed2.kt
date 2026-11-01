package com.no5.helm.benchmark

import com.no5.domain.*

/** Identical ingress/model/snapshot work for Canvas and both GPU renderers. No live IO. */
internal class QuietBenchmarkFeed2(private val path:String,private val count:Int,private val auto:Boolean,private val profile:(Int,Long)->SonarFrame):AutoCloseable {
        private var sourceTime=1000000L
        private val port=DeterministicMockSonarSource(now={sourceTime})
        val controller=com.no5.helm.SonarNativeController(port){sourceTime}
        var values=SonarImageValues(0.0,1.0,0.0);private set
        private val oldAuto=if(auto&&path!="gpuQuiet")BaselineImageAuto()else null
        private val newAuto=if(auto&&path=="gpuQuiet")SonarImageAuto()else null
        init {
            controller.model.connect("SYNTHETIC MATCHED FEED");controller.model.acquisition(true);controller.interpolation(true)
            controller.model.updateContext(SonarEpoch("synthetic-fixed-source",1,1),SonarPingSource.OWN)
            controller.model.setDisplay(SonarDisplay(historySeconds=count/20.0))
            controller.model.setAnalysis(null,SonarBottom(),null,false,amplitude=SonarAmplitude(4095,12,true))
            check(controller.model.ring.capacity==count)
        }
        fun input(id:Int,receivedAtNanos:Long=System.nanoTime(),publish:Boolean=true){
            sourceTime=1000000L+id*50L
            val f=profile(id,sourceTime)
            controller.model.frame(f,receivedAtEpochMs=sourceTime,pingSource=SonarPingSource.OWN,localReceivedAtNanos=receivedAtNanos)
            if(auto)values=(oldAuto?.observe(f,4095,sourceTime,values)?:newAuto?.observe(f,4095,sourceTime,values,historySeconds=count/20.0))?:values
            controller.model.setDisplay(controller.model.display.copy(gainDb=values.gainDb,gamma=values.gamma,threshold=values.threshold))
            if(publish)controller.refresh()
            check(controller.model.latest?.pingId==id.toLong())
        }
        fun publish(){controller.refresh()}
        override fun close(){controller.dispose()}
    }
