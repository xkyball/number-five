package com.no5.domain

/** Display preferences only. Gateway telemetry remains knots, meters and liters/hour. */
enum class SpeedUnit(val symbol: String, val distanceSymbol: String, private val factor: Double, val gaugeMaximum: Double) {
    KNOTS("kn", "NM", 1.0, 60.0),
    KMH("km/h", "km", 1.852, 120.0),
    MPH("mi/h", "mi", 1.1507794480235425, 80.0);
    fun fromKnots(value: Double) = value * factor
    fun distanceFromNm(value: Double) = value * factor
}

enum class DepthUnit(val symbol: String, private val factor: Double) {
    METERS("m", 1.0), FEET("ft", 3.280839895013123);
    fun fromMeters(value: Double) = value * factor
}

enum class ConsumptionUnit(val symbol: String) {
    LPH("L/h"), KM_PER_L("km/L"), NM_PER_L("NM/L"), L_PER_100KM("L/100km");

    /** Derived ratios require current, usable source samples; zero never becomes infinity. */
    fun instantaneous(speedKn: Double?, flowLph: Double?, quality: TelemetryStatus): Double? {
        if (quality != TelemetryStatus.LIVE || !usable(flowLph)) return null
        val flow = flowLph!!
        if (this == LPH) return flow
        if (!usable(speedKn)) return null
        val speed = speedKn!!
        return finite(when (this) {
            KM_PER_L -> if (flow > 0) speed * 1.852 / flow else return null
            NM_PER_L -> if (flow > 0) speed / flow else return null
            L_PER_100KM -> if (speed > 0) flow * 100 / (speed * 1.852) else return null
            LPH -> flow
        })
    }

    fun trip(distanceNm: Double?, fuelLiters: Double?, durationSeconds: Double?, quality: TelemetryStatus): Double? {
        if (quality != TelemetryStatus.LIVE || !usable(fuelLiters)) return null
        val fuel = fuelLiters!!
        if (this == LPH) return if (usable(durationSeconds) && durationSeconds!! > 0) finite(fuel * 3600 / durationSeconds) else null
        if (!usable(distanceNm)) return null
        val distance = distanceNm!!
        return finite(when (this) {
            KM_PER_L -> if (fuel > 0) distance * 1.852 / fuel else return null
            NM_PER_L -> if (fuel > 0) distance / fuel else return null
            L_PER_100KM -> if (distance > 0) fuel * 100 / (distance * 1.852) else return null
            LPH -> return null
        })
    }

    private fun usable(value: Double?) = value != null && value.isFinite() && value >= 0
    private fun finite(value: Double) = value.takeIf { it.isFinite() && it >= 0 }
}

data class DisplayUnits(
    val speed: SpeedUnit = SpeedUnit.KNOTS,
    val consumption: ConsumptionUnit = ConsumptionUnit.LPH,
    val depth: DepthUnit = DepthUnit.METERS,
)
