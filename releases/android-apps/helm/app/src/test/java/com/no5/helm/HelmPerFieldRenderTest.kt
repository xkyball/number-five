package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Presentation policy only. Host execution does not establish Android pixel layout or FPS. */
class HelmPerFieldRenderTest {
    private fun field(value: Double?, stamp: Long? = 20_000L,
                      status: TelemetryStatus = TelemetryStatus.LIVE) =
        TelemetryValue(value, TelemetryQuality(status, stamp, "TEST_GATEWAY", simulated = true))

    private fun state(vararg fields: Pair<String, TelemetryValue>) = HelmState(
        quality = TelemetryQuality(TelemetryStatus.LIVE, 20_000, "TEST_GATEWAY", simulated = true),
        telemetryFields = fields.toMap(), sourceSessionId = "server-a",
    )

    @Test fun nullAndAbsentCoreFieldsStayUnknownDespiteLegacyScalarDefaults() {
        val raw = state("portRpm" to field(null, null, TelemetryStatus.UNAVAILABLE))
        val view = renderValues(raw, 20_000)
        for (name in listOf("rpm-port", "rpm-stbd", "hud-sog", "hud-depth", "deck-fuelPct", "deck-cog"))
            assertEquals(name, "—", view.text[name])
        assertEquals("—", view.text["secondary-port-gear"])
        assertFalse(view.headingAvailable)
        assertFalse(view.fuelAvailable)
    }

    @Test fun realZerosStayVisibleAndFuelZeroStillHasAMarker() {
        val raw = state("speed" to field(0.0), "depth" to field(0.0), "portRpm" to field(0.0),
            "heading" to field(0.0), "portFuel" to field(0.0))
            .copy(speed = 0.0, depth = 0.0, portRpm = 0.0, heading = 0.0, portFuel = 0.0)
        val view = renderValues(raw, 20_000)
        assertEquals("0", view.text["rpm-port"])
        assertEquals("0.0", view.text["hud-sog"])
        assertEquals("0.0", view.text["hud-depth"])
        assertEquals("0", view.text["deck-fuelPct"])
        assertTrue(view.headingAvailable)
        assertTrue(view.fuelAvailable)
    }

    @Test fun staleEngineShowsItsLastRawSampleWhileFreshGpsContinuesMoving() {
        val raw = state("speed" to field(20.0), "portRpm" to field(3_400.0, 14_000))
            .copy(speed = 15.0, portRpm = 1_700.0)
        val view = renderValues(raw, 20_000)
        assertEquals("3400*", view.text["rpm-port"])
        assertEquals(3_400f, view.portRpm, 0f)
        assertEquals("15.0", view.text["hud-sog"])
        assertTrue(view.text.getValue("header-source-label").contains("STALE"))
    }

    @Test fun overScaleRpmIsNotClampedToTheSevenThousandGaugeLimit() {
        val raw = state("portRpm" to field(9_500.0)).copy(portRpm = 9_500.0)
        val view = renderValues(raw, 20_000)
        assertEquals("9500", view.text["rpm-port"])
        assertEquals(9_500f, view.portRpm, 0f)
    }

    @Test fun criticalValuesAppearImmediatelyDuringASpeedTransition() {
        val initial = state("speed" to field(10.0, 19_950)).copy(speed = 10.0)
        val next = state("speed" to field(20.0), "portOilBar" to field(0.0), "portCoolantC" to field(150.0),
            "portVoltage" to field(8.0), "depth" to field(0.0)).copy(
            speed = 20.0, portOilBar = 0.0, portCoolantC = 150.0, portVoltage = 8.0, depth = 0.0)
        val model = HelmPresentationModel(initial)
        model.accept(next, 50)
        val view = renderValues(model.render(100), 20_000)
        assertEquals("15.0", view.text["hud-sog"])
        assertEquals("0.0 bar", view.text["secondary-port-oil"])
        assertEquals("150°C", view.text["secondary-port-coolant"])
        assertEquals("8.0 V", view.text["secondary-port-voltage"])
        assertEquals("0.0", view.text["hud-depth"])
    }

    @Test fun stalePositionAndTripDoNotCarryALiveEvidenceBadge() {
        val raw = state("latitudeDegrees" to field(-34.0, 14_000), "longitudeDegrees" to field(18.0, 14_000),
            "tripDistanceNm" to field(5.0, 14_000), "tripDurationSeconds" to field(1_800.0, 14_000),
            "fuelUsedLiters" to field(10.0, 14_000)).copy(latitudeDegrees = -34.0, longitudeDegrees = 18.0,
            tripDistanceNm = 5.0, tripDurationSeconds = 1_800.0, fuelUsedLiters = 10.0)
        val view = renderValues(raw, 20_000)
        assertTrue(view.text.getValue("deck-latitude").endsWith("*"))
        assertTrue(view.text.getValue("deck-longitude").endsWith("*"))
        assertEquals("5.0*", view.text["deck-tripDistance"])
        assertEquals("00:30:00*", view.text["deck-tripDuration"])
        assertTrue(view.text.getValue("deck-evidence-position").contains("STALE"))
        assertTrue(view.text.getValue("deck-evidence-trip").contains("STALE"))
        assertEquals("—", view.text["deck-efficiency"])
    }

    @Test fun fieldExpiryKeepsConfiguredUnitsWithoutInventingValues() {
        val raw = state("speed" to field(10.0, 1_000), "depth" to field(3.0, 1_000),
            "totalFuelFlowLph" to field(20.0, 1_000)).copy(speed = 10.0, depth = 3.0, totalFuelFlowLph = 20.0)
        val units = DisplayUnits(SpeedUnit.KMH, ConsumptionUnit.KM_PER_L, DepthUnit.FEET)
        val view = renderValues(raw, 40_000, units = units)
        assertEquals("—", view.text["hud-sog"])
        assertEquals("—", view.text["hud-depth"])
        assertEquals("—", view.text["deck-totalFlow"])
        assertEquals("km/h", view.text["hud-sog-unit"])
        assertEquals("ft", view.text["hud-depth-unit"])
        assertEquals("km/L", view.text["deck-unit-totalFlow"])
        assertEquals(10.0, raw.speed, 0.0)
        assertEquals(3.0, raw.depth!!, 0.0)
    }

    @Test fun transportLossHidesCurrentSamplesButRetainsChosenUnitLabels() {
        val base = state("speed" to field(10.0), "depth" to field(3.0), "portFuel" to field(0.0))
        for (status in listOf(TelemetryStatus.LOST, TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE)) {
            val raw = base.copy(quality = base.quality.copy(status = status))
            val view = renderValues(raw, 20_000, units = DisplayUnits(SpeedUnit.MPH, ConsumptionUnit.L_PER_100KM, DepthUnit.FEET))
            assertEquals("—", view.text["hud-sog"])
            assertEquals("—", view.text["hud-depth"])
            assertFalse(view.fuelAvailable)
            assertEquals("mi/h", view.text["hud-sog-unit"])
            assertEquals("ft", view.text["hud-depth-unit"])
            assertEquals("L/100km", view.text["deck-unit-efficiency"])
        }
    }

    @Test fun efficiencyUsesRawSpeedAndFlowInsteadOfTheMovingNeedle() {
        val raw = state("speed" to field(20.0), "totalFuelFlowLph" to field(20.0),
            "portOilBar" to field(4.0, 14_000)).copy(speed = 15.0, totalFuelFlowLph = 20.0)
        val view = renderValues(raw, 20_000, units = DisplayUnits(consumption = ConsumptionUnit.NM_PER_L))
        assertEquals("15.0", view.text["hud-sog"])
        assertEquals("1.0", view.text["deck-totalFlow"])
    }

    @Test fun derivedRatiosRequireEveryUsedFieldToBeFresh() {
        for (staleName in listOf("speed", "totalFuelFlowLph")) {
            val fields = mapOf("speed" to field(20.0), "totalFuelFlowLph" to field(20.0)).toMutableMap()
            fields[staleName] = field(20.0, 14_000)
            val raw = state(*fields.toList().toTypedArray()).copy(speed = 20.0, totalFuelFlowLph = 20.0)
            for (unit in listOf(ConsumptionUnit.KM_PER_L, ConsumptionUnit.NM_PER_L, ConsumptionUnit.L_PER_100KM))
                assertEquals("—", renderValues(raw, 20_000, units = DisplayUnits(consumption = unit)).text["deck-totalFlow"])
            assertEquals(if (staleName == "speed") "20.0" else "—", renderValues(raw, 20_000).text["deck-totalFlow"])
        }
    }

    @Test fun tripAverageUsesOnlyFreshRequiredInputsAndTheChosenUnit() {
        val raw = state("tripDistanceNm" to field(5.0), "fuelUsedLiters" to field(10.0),
            "tripDurationSeconds" to field(1_800.0)).copy(tripDistanceNm = 5.0, fuelUsedLiters = 10.0, tripDurationSeconds = 1_800.0)
        assertEquals("0.93", renderValues(raw, 20_000, units = DisplayUnits(consumption = ConsumptionUnit.KM_PER_L)).text["deck-efficiency"])
        assertEquals("20.00", renderValues(raw, 20_000).text["deck-efficiency"])
        val expiredFuel = raw.copy(telemetryFields = raw.telemetryFields + ("fuelUsedLiters" to field(10.0, 14_000)))
        for (unit in ConsumptionUnit.entries)
            assertEquals("—", renderValues(expiredFuel, 20_000, units = DisplayUnits(consumption = unit)).text["deck-efficiency"])
    }

    @Test fun newGatewaySessionNeverDisplaysAnInterpolatedOldSourceValue() {
        val first = state("speed" to field(30.0)).copy(speed = 30.0)
        val next = state("speed" to field(1.0)).copy(speed = 1.0, sourceSessionId = "server-b")
        val model = HelmPresentationModel(first)
        model.accept(next, 50)
        assertEquals("1.0", renderValues(model.render(50), 20_000).text["hud-sog"])
    }
}
