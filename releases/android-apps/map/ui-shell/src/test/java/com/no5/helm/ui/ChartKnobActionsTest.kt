package com.no5.helm.ui

import com.no5.helm.chart.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ChartKnobActionsTest {
    @Test fun physicalAdapterOnlyCallsTheSemanticCameraControlUsedByTouch() {
        val calls=mutableListOf<List<Any?>>()
        val engine=Proxy.newProxyInstance(ChartEngine::class.java.classLoader,arrayOf(ChartEngine::class.java)) {_,method,args ->
            check(method.name=="submitCameraControl"){"Physical mapping reached a second camera or transport path: ${method.name}"}
            calls+=args!!.toList();73L
        } as ChartEngine
        val id=submitChartKnobControl(engine,ChartKnobInput.LEFT_CW,41,1234,-33.9,18.4,.02)
        assertEquals(73L,id)
        engine.submitCameraControl(ChartCameraControl.ZOOM_STEPS,1.0,41,1234,-33.9,18.4,.02)
        assertEquals(calls[0],calls[1])
    }
    @Test fun oneShotLongPressNorthAndResetPassSemanticIntentWithoutComputingACamera() {
        val calls=mutableListOf<List<Any?>>()
        val engine=Proxy.newProxyInstance(ChartEngine::class.java.classLoader,arrayOf(ChartEngine::class.java)) {_,method,args ->
            check(method.name=="submitCameraControl");calls+=args!!.toList();calls.size.toLong()
        } as ChartEngine
        submitChartKnobControl(engine,ChartKnobInput.LEFT_LONG_PUSH,1,100,-33.9,18.4,.02)
        submitChartKnobControl(engine,ChartKnobInput.RIGHT_PUSH,2,100,Double.NaN,Double.NaN,.02)
        submitChartKnobControl(engine,ChartKnobInput.RIGHT_LONG_PUSH,3,100,Double.NaN,Double.NaN,.02)
        assertEquals(listOf(ChartCameraControl.CENTER,ChartCameraControl.NORTH_UP,ChartCameraControl.RESET),calls.map{it[0]})
        assertEquals(-33.9,calls[0][4] as Double,0.0);assertEquals(18.4,calls[0][5] as Double,0.0)
        assertEquals(.02,calls[2][6] as Double,0.0)
    }
}
