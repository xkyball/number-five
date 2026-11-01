package com.no5.helm.ocean

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Test-only fixed locations/times. Never injected into production telemetry. */
class OceanWeatherRenderTest {
    @Test fun exportReproducibleWeatherFramesThroughProductionMapper() {
        val cases=JSONArray()
        for((name,cloud,visibility,wind,utc,heading) in listOf(
            Case("sunny",8.0,35000.0,12.0,"2026-10-03T13:30:00Z",0.0),
            Case("overcast",100.0,15000.0,18.0,"2026-10-03T13:30:00Z",0.0),
            Case("fog",100.0,300.0,3.0,"2026-10-03T13:30:00Z",0.0),
            Case("twilight",15.0,20000.0,8.0,"2026-10-03T16:50:00Z",270.0))) {
            val p=OceanPresentation("test-only")
            p.accept(OceanInputs("test-only",headingDegrees=heading,headingQuality=OceanInputQuality.LIVE,
                weatherQuality=OceanInputQuality.LIVE,windCapability=true,windSpeedKn=wind,
                windDirectionFromDegrees=147.0,cloudCoverPercent=cloud,visibilityMeters=visibility,
                latitudeDegrees=-34.3,longitudeDegrees=18.6,utcEpochMs=Instant.parse(utc).toEpochMilli()))
            repeat(1000){p.frame(0f,.05f,OceanQuality.HIGH)}
            val frame=p.frame(0f,0f,OceanQuality.HIGH);val e=frame.environment
            assertTrue(e.sunUp.isFinite());if(name=="twilight")assertTrue(e.sunUp<0f)
            cases.put(JSONObject().put("name",name).put("utc",utc).put("heading",heading)
                .put("roughness",frame.waveHeight).put("cloud",e.cloudCover).put("fog",e.fogDensity)
                .put("windTo",e.windToRadians).put("sun",JSONArray(listOf(e.sunEast,e.sunUp,e.sunNorth))))
        }
        val output=File(System.getProperty("no5.renderOutputDir"),"ocean-gl").also{it.mkdirs()}
        File(output,"weather-scenarios.json").writeText(JSONObject().put("testOnly",true).put("cases",cases).toString(2))
    }
    private data class Case(val name:String,val cloud:Double,val visibility:Double,val wind:Double,val utc:String,val heading:Double)
}
