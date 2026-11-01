package com.no5.helm
import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test
class HelmRenderValuesTest {
    @Test fun presentationUnitsConvertValuesAndLabelsTogetherWithoutChangingSource() {
        val source = HelmState(speed=10.0,depth=3.0,totalFuelFlowLph=20.0,tripDistanceNm=5.0,
            tripDurationSeconds=1800.0,fuelUsedLiters=10.0)
        val units=DisplayUnits(SpeedUnit.KMH,ConsumptionUnit.KM_PER_L,DepthUnit.FEET)
        val rendered=renderValues(source,units=units)
        assertEquals("18.5",rendered.text["hud-sog"])
        assertEquals("km/h",rendered.text["hud-sog-unit"])
        assertEquals("9.8",rendered.text["hud-depth"])
        assertEquals("ft",rendered.text["hud-depth-unit"])
        assertEquals("9.3",rendered.text["deck-tripDistance"])
        assertEquals("km",rendered.text["deck-unit-tripDistance"])
        assertEquals("0.9",rendered.text["deck-totalFlow"])
        assertEquals("0.93",rendered.text["deck-efficiency"])
        assertEquals("km/L",rendered.text["deck-unit-efficiency"])
        assertEquals(10.0,source.speed,0.0)
        assertEquals(3.0,source.depth!!,0.0)
    }
    @Test fun staleAndStoppedRatiosNeverDisplayInfinityOrInventFuelUse() {
        val stopped=HelmState(speed=0.0,totalFuelFlowLph=0.0)
        for(unit in ConsumptionUnit.entries) {
            val value=renderValues(stopped,units=DisplayUnits(consumption=unit)).text["deck-totalFlow"]!!
            assertFalse(value.contains("NaN"));assertFalse(value.contains("Infinity"))
            assertEquals(if(unit==ConsumptionUnit.LPH)"0.0" else "—",value)
            assertEquals("—",renderValues(stopped.copy(quality=stopped.quality.copy(status=TelemetryStatus.STALE)),
                units=DisplayUnits(consumption=unit)).text["deck-totalFlow"])
        }
    }
    @Test fun unavailableHeadingAndNumbersAreSuppressed(){
        for(status in listOf(TelemetryStatus.LOST,TelemetryStatus.INVALID,TelemetryStatus.UNAVAILABLE)){
            val v=renderValues(HelmState(quality=TelemetryQuality(status,demo=true)))
            assertFalse(v.headingAvailable);assertEquals("—",v.text["rpm-port"]);assertEquals("—",v.text["hud-depth"]);assertEquals(0f,v.portRpm,0f)
        }
    }
    @Test fun staleHeadingIsExplicitAndHeld(){val v=renderValues(HelmState(heading=358.0,quality=TelemetryQuality(TelemetryStatus.STALE,demo=true)));assertTrue(v.headingAvailable);assertEquals(358f,v.heading,0f);assertEquals("STALE",v.quality)}
    @Test fun trueZeroRemainsVisible(){val v=renderValues(HelmState(portRpm=0.0,depth=0.0));assertEquals("0",v.text["rpm-port"]);assertEquals("0.0",v.text["hud-depth"]);assertTrue(v.headingAvailable)}
    @Test fun liveValuesRequireValidFreshSourceMetadata() {
        val sample=HelmState(portRpm=1234.0,quality=TelemetryQuality(TelemetryStatus.LIVE,1000,"pi",false))
        assertEquals("1234",renderValues(sample,2000).text["rpm-port"])
        assertEquals("STALE",renderValues(sample,7000).quality)
        assertEquals("—",renderValues(sample,32000).text["rpm-port"])
        assertEquals("—",renderValues(sample.copy(quality=sample.quality.copy(timestampEpochMs=null)),2000).text["rpm-port"])
        assertEquals("STALE",renderValues(sample,2500,FreshnessPolicy(1000,2000)).quality)
    }
    @Test fun fieldsAreProvidedBySourceInsteadOfHardcodedDemoValues() {
        val sample=HelmState(portCoolantC=44.0,portOilBar=1.8,portFuelFlowLph=null,
            totalFuelFlowLph=null,fuelRemainingLiters=93.0,courseOverGroundDegrees=123.0,
            portGear="N",latitudeDegrees=null,longitudeDegrees=null)
        val v=renderValues(sample)
        assertEquals("44°C",v.text["secondary-port-coolant"])
        assertEquals("1.8 bar",v.text["secondary-port-oil"])
        assertEquals("N",v.text["secondary-port-gear"])
        assertEquals("—",v.text["secondary-port-flow"])
        assertEquals("—",v.text["deck-latitude"])
        assertEquals("93",v.text["deck-remainingL"])
        assertEquals("123",v.text["deck-cog"])
    }
}
