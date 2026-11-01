package com.no5.helm.sonar.gl

import com.no5.domain.*
import kotlin.math.*

/** Metadata-only request; RAW and transfer stay in the one canonical ring. */
internal data class ReliefData(val source:SonarRingBuffer,val revision:Long,val mappingVersion:Long,
    val generation:Long,val head:Int,val count:Int,val seconds:Float,val newest:Float,val newestEpochMs:Long,val newestPingId:Long)
internal data class ReliefFrame(val data:ReliefData,val cropStart:Float,val cropEnd:Float,
    val gain:Float,val gamma:Float,val threshold:Float,val palette:Int,val night:Boolean,
    val yaw:Float=-.25f,val pitch:Float=.8f,val zoom:Float=.75f,val aligned:Boolean=false,
    val autoMask:Int=0,val gainOffset:Float=1f)
internal class SonarReliefFeed {
    fun snapshot(source:SonarRingBuffer,historySeconds:Double):ReliefData = source.readLocked {
        var count=0
        if(source.count>0)while(count<source.count&&source.timestampAt(0).toDouble()-source.timestampAt(count)<=historySeconds*1000)count++
        ReliefData(source,source.version,source.imageMappingVersion,source.lifecycleGeneration,source.head,count,
            if(count>1)((source.timestampAt(0).toDouble()-source.timestampAt(count-1))/1000).toFloat()else 0f,
            if(count>0)Math.floorMod(source.timestampAt(0),16777216L).toFloat()else 0f,if(count>0)source.timestampAt(0)else 0L,if(count>0)source.pingIdAt(0)else -1L)
    }
}

internal data class ReliefMesh(val vertices:FloatArray,val indices:IntArray)
internal object SonarReliefGeometry {
    const val COLUMNS=512
    const val ROWS=256
    fun mesh():ReliefMesh {
        val v=FloatArray((COLUMNS+1)*(ROWS+1)*2)
        var at=0
        for(x in 0..COLUMNS)for(y in 0..ROWS){v[at++]=x.toFloat();v[at++]=y.toFloat()/ROWS}
        val indices=IntArray(COLUMNS*ROWS*6);at=0
        for(x in 0 until COLUMNS)for(y in 0 until ROWS){
            val a=x*(ROWS+1)+y;val b=a+1;val d=(x+1)*(ROWS+1)+y;val c=d+1
            // Both provoking vertices belong to the older column: one gap flag per complete cell.
            for(i in intArrayOf(a,b,c,a,c,d))indices[at++]=i
        }
        return ReliefMesh(v,indices)
    }
}
