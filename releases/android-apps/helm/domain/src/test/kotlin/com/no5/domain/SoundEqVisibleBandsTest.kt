package com.no5.domain

import org.junit.Test
import kotlin.test.*
import kotlin.math.pow

class SoundEqVisibleBandsTest {
    private fun reference(previous:SoundEqPoint,requested:SoundEqPoint,points:List<SoundEqPoint>):SoundEqBandLimit {
        fun width(a:SoundEqPoint,b:SoundEqPoint)=kotlin.math.max(0.0,kotlin.math.min(20000.0,kotlin.math.min(a.highHz,b.highHz))-kotlin.math.max(20.0,kotlin.math.max(a.lowHz,b.lowHz)))
        fun compatible(next:List<SoundEqPoint>):Boolean {
            val old=points.associateBy{it.id}
            for(i in next.indices)for(j in i+1 until next.size){
                val a=next[i];val b=next[j];val x=old[a.id];val y=old[b.id]
                val before=if(x!=null&&y!=null)width(x,y)else 0.0
                if(width(a,b)>before+1e-9)return false
                if(x!=null&&y!=null){val order=x.frequencyHz.compareTo(y.frequencyHz);if(order<0&&a.frequencyHz>=b.frequencyHz||order>0&&a.frequencyHz<=b.frequencyHz)return false}
            }
            return true
        }
        if(previous.frequencyHz==requested.frequencyHz&&previous.q==requested.q)return SoundEqBandLimit(requested,false)
        fun at(t:Double):SoundEqPoint {
            if(t==0.0)return previous
            if(t==1.0)return requested
            val f=if(previous.frequencyHz==requested.frequencyHz)previous.frequencyHz else kotlin.math.exp(kotlin.math.ln(previous.frequencyHz)+(kotlin.math.ln(requested.frequencyHz)-kotlin.math.ln(previous.frequencyHz))*t)
            val keepHz=previous.widthLocked&&requested.widthLocked&&kotlin.math.abs(previous.widthHz-requested.widthHz)<1e-6
            val q=if(previous.q==requested.q)previous.q else if(keepHz)(f/previous.widthHz).coerceIn(.3,10.0)else kotlin.math.exp(kotlin.math.ln(previous.q)+(kotlin.math.ln(requested.q)-kotlin.math.ln(previous.q))*t).coerceIn(.3,10.0)
            val g=if(previous.gainDb==requested.gainDb)previous.gainDb else previous.gainDb+(requested.gainDb-previous.gainDb)*t
            return requested.copy(frequencyHz=f.coerceIn(20.0,20000.0),gainDb=g,q=q)
        }
        fun valid(p:SoundEqPoint)=compatible(points.map{if(it.id==p.id)p else it})
        val separate=points.filter{it.id!=previous.id}.none{width(previous,it)>1e-9}
        val ordinary=previous.q==requested.q||previous.frequencyHz==requested.frequencyHz||previous.widthLocked&&requested.widthLocked&&kotlin.math.abs(previous.widthHz-requested.widthHz)<1e-6
        if(separate&&ordinary){
            if(valid(requested))return SoundEqBandLimit(requested,false)
            var lo=0.0;var hi=1.0
            repeat(40){val mid=(lo+hi)/2;if(valid(at(mid)))lo=mid else hi=mid}
            return SoundEqBandLimit(at(lo),true)
        }
        var last=0.0
        for(step in 1..128){val t=step/128.0;if(!valid(at(t))){var lo=last;var hi=t;repeat(40){val mid=(lo+hi)/2;if(valid(at(mid)))lo=mid else hi=mid};return SoundEqBandLimit(at(lo),true)};last=t}
        return SoundEqBandLimit(requested,false)
    }

    @Test fun everyGainSignStopsAtNeighbourEvenWithHugePointerJump(){
        for(gain in listOf(-12.0,0.0,3.0))for(otherGain in listOf(-12.0,0.0,3.0)){
            val a=SoundEqPoint("a",500.0,gain,5.0);val b=SoundEqPoint("b",1000.0,otherGain,5.0)
            val limit=SoundEqVisibleBands.constrain(a,a.copy(frequencyHz=19000.0),listOf(a,b))
            assertTrue(limit.constrained);assertTrue(limit.point.highHz<=b.lowHz+1e-9)
            assertTrue(limit.point.frequencyHz<b.frequencyHz);assertEquals(gain.toRawBits(),limit.point.gainDb.toRawBits())
        }
    }
    @Test fun widthOnlyPreservesFrequencyAndGainBitExactly(){
        val a=SoundEqPoint("a",500.0,-3.0,10.0);val b=SoundEqPoint("b",1000.0,0.0,5.0)
        val result=SoundEqVisibleBands.constrain(a,a.copy(q=.3),listOf(a,b))
        assertTrue(result.constrained);assertEquals(a.frequencyHz.toRawBits(),result.point.frequencyHz.toRawBits())
        assertEquals(a.gainDb.toRawBits(),result.point.gainDb.toRawBits());assertTrue(result.point.highHz<=b.lowHz+1e-9)
    }
    @Test fun oldOverlapIsNotMovedAutomaticallyAndCanOnlyImprove(){
        val a=SoundEqPoint("a",900.0,0.0,2.0);val b=SoundEqPoint("b",1000.0,-4.0,2.0);val old=listOf(a,b)
        assertTrue(SoundEqVisibleBands.compatible(old,old))
        assertTrue(SoundEqVisibleBands.compatible(old,listOf(a.copy(frequencyHz=700.0),b)))
        assertFalse(SoundEqVisibleBands.compatible(old,listOf(a.copy(frequencyHz=980.0),b)))
        assertFalse(SoundEqVisibleBands.compatible(old,listOf(a.copy(frequencyHz=5000.0),b)))
        assertFalse(SoundEqVisibleBands.compatible(old,old+SoundEqPoint("new",1000.0)))
    }
    @Test fun ordinaryFastPathsStillStopAtTheFirstBoundaryAndKeepEveryIntermediateStepSafe(){
        val a=SoundEqPoint("a",500.0,-3.0,5.0);val b=SoundEqPoint("b",1000.0,0.0,5.0)
        for(request in listOf(a.copy(frequencyHz=19000.0,gainDb=6.0),a.copy(q=.3),a.copy(widthLocked=true).move(5000.0,6.0))){
            val old=if(request.widthLocked)a.copy(widthLocked=true)else a
            val result=SoundEqVisibleBands.constrain(old,request,listOf(old,b))
            assertTrue(result.constrained);assertTrue(SoundEqVisibleBands.compatible(listOf(old,b),listOf(result.point,b)))
            assertTrue(result.point.highHz<=b.lowHz+1e-9);assertTrue(result.point.frequencyHz<b.frequencyHz)
            for(i in 1..100){
                val t=i/100.0
                val f=kotlin.math.exp(kotlin.math.ln(old.frequencyHz)+(kotlin.math.ln(result.point.frequencyHz)-kotlin.math.ln(old.frequencyHz))*t)
                val q=if(request.widthLocked)f/old.widthHz else kotlin.math.exp(kotlin.math.ln(old.q)+(kotlin.math.ln(result.point.q)-kotlin.math.ln(old.q))*t)
                assertTrue(SoundEqVisibleBands.compatible(listOf(old,b),listOf(old.copy(frequencyHz=f,q=q.coerceIn(.3,10.0)),b)))
            }
        }
    }
    @Test fun fullHeightChangesLeaveGeometryAndOtherPointsUntouched(){
        val a=SoundEqPoint("a",500.0,0.0,5.0);val b=SoundEqPoint("b",1000.0,0.0,5.0)
        val result=SoundEqVisibleBands.constrain(a,a.copy(gainDb=18.0),listOf(a,b))
        assertFalse(result.constrained);assertEquals(a.copy(gainDb=18.0),result.point)
    }

    @Test fun allocationReducedProjectionMatchesPreviousDecisionAndValue(){
        var seed=0x5eedL
        fun next():Long{seed=seed*6364136223846793005L+1442695040888963407L;return seed ushr 1}
        repeat(500){case->
            val count=2+(next()%15).toInt()
            val points=(0 until count).map{i->
                val f=20.0*1000.0.pow(next().rem(1_000_000).toDouble()/1_000_000.0)
                SoundEqPoint("p$i",f,(next()%25).toDouble()-12.0,.3+(next()%970).toDouble()/100.0,widthLocked=next()%2L==0L)
            }
            val index=(next()%count).toInt();val old=points[index]
            val requested=old.copy(
                frequencyHz=20.0*1000.0.pow(next().rem(1_000_000).toDouble()/1_000_000.0),
                gainDb=(next()%34).toDouble()-12.0,
                q=.3+(next()%970).toDouble()/100.0
            )
            val base=reference(old,requested,points)
            val optimized=SoundEqVisibleBands.constrain(old,requested,points)
            assertEquals(base,optimized,"case $case")
        }
    }

    @Test fun boundedSixteenPointHostMicrobenchmarkReportsOldAndNewProjectionCosts(){
        val points=(0 until 16).map{i->SoundEqPoint("p$i",60.0*1.38.pow(i.toDouble()),0.0,4.5)}
        val old=points[7];val requested=old.copy(frequencyHz=old.frequencyHz*2.5)
        repeat(40){
            assertEquals(reference(old,requested,points),SoundEqVisibleBands.constrain(old,requested,points))
            reference(old,requested,points);SoundEqVisibleBands.constrain(old,requested,points)
        }
        val prior=LongArray(200);val current=LongArray(200)
        repeat(prior.size){i->
            var start=System.nanoTime();val expected=reference(old,requested,points);prior[i]=System.nanoTime()-start
            start=System.nanoTime();val actual=SoundEqVisibleBands.constrain(old,requested,points);current[i]=System.nanoTime()-start
            assertEquals(expected,actual,"sample $i")
        }
        prior.sort();current.sort()
        println("EQ_GEOMETRY_HOST_MICROBENCH points=16 samples=${prior.size} reference_p50_ns=${prior[prior.size/2]} optimized_p50_ns=${current[current.size/2]} reference_p95_ns=${prior[prior.size*95/100]} optimized_p95_ns=${current[current.size*95/100]}")
    }
}
