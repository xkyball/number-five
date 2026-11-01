package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class DisplayUnitsTest {
    @Test fun canonicalKnotsAndNauticalMilesConvertToChosenDisplayUnits() {
        assertEquals(10.0, SpeedUnit.KNOTS.fromKnots(10.0), 1e-10)
        assertEquals(18.52, SpeedUnit.KMH.fromKnots(10.0), 1e-10)
        assertEquals(11.507794480235425, SpeedUnit.MPH.fromKnots(10.0), 1e-10)
        assertEquals(1.852, SpeedUnit.KMH.distanceFromNm(1.0), 1e-10)
        assertEquals(1.1507794480235425, SpeedUnit.MPH.distanceFromNm(1.0), 1e-10)
        assertEquals(0.0, SpeedUnit.KMH.fromKnots(0.0), 0.0)
    }

    @Test fun depthPreferenceConvertsOnlyDisplayValue() {
        assertEquals(10.0, DepthUnit.METERS.fromMeters(10.0), 0.0)
        assertEquals(32.80839895013123, DepthUnit.FEET.fromMeters(10.0), 1e-10)
        assertEquals(0.0, DepthUnit.FEET.fromMeters(0.0), 0.0)
    }

    @Test fun instantaneousConsumptionUsesCurrentSpeedAndFuelFlow() {
        assertEquals(20.0, ConsumptionUnit.LPH.instantaneous(null, 20.0, TelemetryStatus.LIVE)!!, 1e-10)
        assertEquals(.926, ConsumptionUnit.KM_PER_L.instantaneous(10.0, 20.0, TelemetryStatus.LIVE)!!, 1e-10)
        assertEquals(.5, ConsumptionUnit.NM_PER_L.instantaneous(10.0, 20.0, TelemetryStatus.LIVE)!!, 1e-10)
        assertEquals(107.9913606911447, ConsumptionUnit.L_PER_100KM.instantaneous(10.0, 20.0, TelemetryStatus.LIVE)!!, 1e-10)
    }

    @Test fun tripConsumptionUsesDistanceFuelAndDurationIndependently() {
        assertEquals(10.0, ConsumptionUnit.LPH.trip(null, 20.0, 7200.0, TelemetryStatus.LIVE)!!, 1e-10)
        assertEquals(.926, ConsumptionUnit.KM_PER_L.trip(10.0, 20.0, null, TelemetryStatus.LIVE)!!, 1e-10)
        assertEquals(.5, ConsumptionUnit.NM_PER_L.trip(10.0, 20.0, null, TelemetryStatus.LIVE)!!, 1e-10)
        assertEquals(107.9913606911447, ConsumptionUnit.L_PER_100KM.trip(10.0, 20.0, null, TelemetryStatus.LIVE)!!, 1e-10)
    }

    @Test fun trueZerosStayVisibleButUndefinedRatiosNeverBecomeInfinity() {
        assertEquals(0.0, ConsumptionUnit.LPH.instantaneous(0.0, 0.0, TelemetryStatus.LIVE)!!, 0.0)
        assertEquals(0.0, ConsumptionUnit.KM_PER_L.instantaneous(0.0, 1.0, TelemetryStatus.LIVE)!!, 0.0)
        assertNull(ConsumptionUnit.KM_PER_L.instantaneous(1.0, 0.0, TelemetryStatus.LIVE))
        assertNull(ConsumptionUnit.L_PER_100KM.instantaneous(0.0, 1.0, TelemetryStatus.LIVE))
        assertEquals(0.0, ConsumptionUnit.L_PER_100KM.instantaneous(1.0, 0.0, TelemetryStatus.LIVE)!!, 0.0)
        assertNull(ConsumptionUnit.LPH.trip(1.0, 1.0, 0.0, TelemetryStatus.LIVE))
        assertNull(ConsumptionUnit.NM_PER_L.trip(1.0, 0.0, 1.0, TelemetryStatus.LIVE))
        assertNull(ConsumptionUnit.L_PER_100KM.trip(0.0, 1.0, 1.0, TelemetryStatus.LIVE))
    }

    @Test fun staleMissingNegativeAndNonFiniteInputsRemainUnavailable() {
        ConsumptionUnit.entries.forEach { unit ->
            TelemetryStatus.entries.filter { it != TelemetryStatus.LIVE }.forEach { status ->
                assertNull(unit.instantaneous(10.0, 20.0, status))
                assertNull(unit.trip(10.0, 20.0, 3600.0, status))
            }
            listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
                assertNull(unit.instantaneous(10.0, bad, TelemetryStatus.LIVE))
                assertNull(unit.trip(10.0, bad, 3600.0, TelemetryStatus.LIVE))
                if(unit != ConsumptionUnit.LPH) {
                    assertNull(unit.instantaneous(bad, 20.0, TelemetryStatus.LIVE))
                    assertNull(unit.trip(bad, 20.0, 3600.0, TelemetryStatus.LIVE))
                }
            }
        }
        assertNull(ConsumptionUnit.KM_PER_L.instantaneous(Double.MAX_VALUE, Double.MIN_VALUE, TelemetryStatus.LIVE))
    }
}
