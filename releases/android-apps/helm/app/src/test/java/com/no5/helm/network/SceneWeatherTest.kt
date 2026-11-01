package com.no5.helm.network

import com.no5.domain.*
import com.no5.helm.ocean.oceanSourceInputs
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class SceneWeatherTest {
    private fun scene() = PiV1Decoder.weather(wireFixture("scene-weather"))
    @Test fun sharedPythonFixtureKeepsSceneAndTransportClocksSeparate() {
        val w=scene();val now=w.timestampEpochMs!!
        assertEquals(7L,w.scene!!.revision)
        assertEquals(8.0,w.values.cloudCoverPercent)
        assertEquals(35000.0,w.values.visibilityMeters)
        assertEquals(TelemetryStatus.LIVE,WeatherFreshness.evaluate(w,now))
        assertEquals(TelemetryStatus.LOST,WeatherFreshness.evaluate(w,now+30001))
        assertEquals(TelemetryStatus.LIVE,WeatherFreshness.evaluate(w.copy(scene=w.scene!!.copy(utcEpochMs=946684800000)),now))
    }
    @Test fun rendererAcceptsOnlyFreshExplicitGatewayScene() {
        val w=scene();val now=w.timestampEpochMs!!
        val q=TelemetryQuality(TelemetryStatus.LIVE,now,"fixture",demo=false)
        val helm=HelmState.unavailable().copy(quality=q,telemetryFields=mapOf(
            "latitudeDegrees" to TelemetryValue(-34.3,q),"longitudeDegrees" to TelemetryValue(18.6,q)))
        fun mapped(weather:WeatherSnapshot,time:Long=now)=oceanSourceInputs("test",helm,QualityEvaluator.SHARED,weather,QualityEvaluator.SHARED,time)
        val historical=w.copy(scene=w.scene!!.copy(utcEpochMs=946684800000))
        assertEquals(946684800000,mapped(historical).utcEpochMs)
        assertEquals(now,mapped(historical.copy(status=TelemetryStatus.STALE)).utcEpochMs)
        assertEquals(now+30001,mapped(historical,now+30001).utcEpochMs)
        val real=w.copy(simulated=false,simulation=null,scene=null,provider=WeatherProvider("OPEN_METEO","Forecast",WeatherProviderKind.FORECAST))
        assertEquals(now,mapped(real).utcEpochMs)
    }
    @Test fun invalidSceneMetadataUnitsAndProvenanceFailClosed() {
        val mutations:List<(JSONObject)->Unit> = listOf(
            {it.getJSONObject("scene").put("version",2)},
            {it.getJSONObject("scene").put("clockMode","VOYAGE")},
            {it.getJSONObject("scene").put("latitudeDegrees",91)},
            {it.getJSONObject("scene").put("utcEpochMs",true)},
            {it.getJSONObject("scene").put("revision",-1)},
            {it.getJSONObject("units").put("visibilityMeters","km")},
            {it.getJSONObject("capabilities").put("cloudCover",false)},
            {it.put("simulated",false).put("sourceKind","REAL");it.getJSONObject("provider").put("kind","FORECAST");it.remove("simulation")})
        for(mutate in mutations){val json=JSONObject(wireFixture("scene-weather"));mutate(json);assertFailsWith<PiProtocolException>{PiV1Decoder.weather(json.toString())}}
    }
}
