package com.no5.domain

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.sin

/** Weather origin is independent of the Pi (or other gateway) delivering its data. */
enum class WeatherProviderKind { SIMULATED, FORECAST, ONBOARD, EXTERNAL }
enum class WeatherPressureReference { UNSPECIFIED, AMBIENT, MEAN_SEA_LEVEL }

/** A local acquisition blocker, separate from provider provenance and value freshness. */
enum class WeatherIssue {
    BOAT_POSITION_UNAVAILABLE, BOAT_POSITION_STALE, LOCATION_SHARING_DISABLED, PROVIDER_UNAVAILABLE,
}

data class WeatherLocation(val latitudeDegrees: Double, val longitudeDegrees: Double) {
    init { require(latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0); require(longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0) }
}

data class WeatherProvider(val id: String, val label: String, val kind: WeatherProviderKind) {
    init { require(id.isNotBlank() && label.isNotBlank()) { "Weather provider ID and label are required" } }
}

data class WeatherDelivery(val gatewayId: String, val transport: String) {
    init { require(gatewayId.isNotBlank() && transport.isNotBlank()) { "Weather delivery provenance is required" } }
}

data class WeatherCapabilities(
    val airTemperature: Boolean = false,
    val wind: Boolean = false,
    val humidity: Boolean = false,
    val pressure: Boolean = false,
    val observedLocation: Boolean = false,
    val cloudCover: Boolean = false,
    val visibility: Boolean = false,
    val weatherCode: Boolean = false,
)

/**
 * SI-derived named units, with knots retained for vessel display. Wind is TRUE wind;
 * direction is meteorological FROM, clockwise from true north [0, 360), not apparent
 * wind and not a vessel heading. Pressure reference is explicit on the snapshot.
 * Missing values remain null; zero temperature, wind speed, direction and humidity are valid.
 */
data class WeatherValues(
    val airTemperatureC: Double? = null,
    val windSpeedKn: Double? = null,
    val windDirectionDegrees: Double? = null,
    val relativeHumidityPercent: Double? = null,
    val pressureHpa: Double? = null,
    val latitudeDegrees: Double? = null,
    val longitudeDegrees: Double? = null,
    val windGustSpeedKn: Double? = null,
    val cloudCoverPercent: Double? = null,
    val visibilityMeters: Double? = null,
    val weatherCode: Int? = null,
) {
    init {
        validate(cloudCoverPercent, 0.0..100.0)
        validate(visibilityMeters, 0.0..1_000_000.0)
        require(weatherCode == null || weatherCode in 0..99)
        validate(airTemperatureC, -100.0..100.0)
        validate(windSpeedKn, 0.0..250.0)
        validate(windGustSpeedKn, 0.0..300.0)
        validate(windDirectionDegrees, 0.0..359.999999999)
        validate(relativeHumidityPercent, 0.0..100.0)
        validate(pressureHpa, 100.0..1200.0)
        validate(latitudeDegrees, -90.0..90.0)
        validate(longitudeDegrees, -180.0..180.0)
        require((latitudeDegrees == null) == (longitudeDegrees == null)) { "Weather observation location requires both coordinates" }
    }

    private fun validate(value: Double?, range: ClosedFloatingPointRange<Double>) {
        require(value == null || value.isFinite() && value in range) { "Invalid weather value for $range" }
    }
}

/** The deterministic voyage clock is not the transport freshness clock. */
data class WeatherSimulation(
    val elapsedSeconds: Double,
    val observedAtEpochMs: Long,
    val running: Boolean,
    val timeScale: Double,
    val phase: String,
    val scenario: String,
) {
    init {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0)
        require(observedAtEpochMs >= 0)
        require(timeScale.isFinite() && timeScale >= 0)
        require(phase.isNotBlank() && scenario.isNotBlank())
    }
}

/** Explicit gateway test scene. This clock is never used to evaluate telemetry freshness. */
data class WeatherScene(val utcEpochMs: Long, val revision: Long, val location: WeatherLocation) {
    init { require(utcEpochMs in 946684800000L..4102444800000L); require(revision >= 0) }
}

/**
 * timestampEpochMs is source-record time (publisher wall time for simulated weather,
 * original provider fetch time for gateway forecasts, model validity time for direct
 * forecasts), never the client's receive time. observedAtEpochMs is
 * observation/model validity time; receivedAtEpochMs is a separate transport diagnostic. In simulation
 * it may pause or run ahead, and is deliberately excluded from transport freshness.
 */
data class WeatherSnapshot(
    val status: TelemetryStatus = TelemetryStatus.UNAVAILABLE,
    val provider: WeatherProvider? = null,
    val delivery: WeatherDelivery? = null,
    val capabilities: WeatherCapabilities = WeatherCapabilities(),
    val values: WeatherValues = WeatherValues(),
    val timestampEpochMs: Long? = null,
    val observedAtEpochMs: Long? = null,
    val simulation: WeatherSimulation? = null,
    val simulated: Boolean = false,
    val error: String? = null,
    val receivedAtEpochMs: Long? = null,
    val pressureReference: WeatherPressureReference = WeatherPressureReference.UNSPECIFIED,
    val requestedLocation: WeatherLocation? = null,
    val scene: WeatherScene? = null,
    val issue: WeatherIssue? = null,
) {
    init {
        require(scene == null || simulated && provider?.kind == WeatherProviderKind.SIMULATED)
        require(timestampEpochMs == null || timestampEpochMs >= 0)
        require(observedAtEpochMs == null || observedAtEpochMs >= 0)
        require(receivedAtEpochMs == null || receivedAtEpochMs >= 0)
    }

    companion object {
        fun unavailable(error: String? = null, issue: WeatherIssue? = null) = WeatherSnapshot(error = error, issue = issue)
    }
}

/** Exact V1 wire units. Adapters reject missing or incompatible units before decoding. */
object WeatherUnits {
    val V1: Map<String, String> = mapOf(
        "airTemperatureC" to "degC",
        "windSpeedKn" to "kn",
        "windDirectionDegrees" to "deg_true_from",
        "relativeHumidityPercent" to "percent",
        "pressureHpa" to "hPa",
        "latitudeDegrees" to "deg",
        "longitudeDegrees" to "deg",
    )

    fun matchesV1(units: Map<String, String>): Boolean = V1.all { (name, unit) -> units[name] == unit }
}

/** No demo shortcut: a halted publisher becomes stale/lost even with simulated data. */
object WeatherFreshness {
    /** Forecast validity cadence is coarser than a live simulator's publication cadence. */
    val FORECAST = FreshnessPolicy(staleAfterMs = 30 * 60_000, lostAfterMs = 60 * 60_000)
    /** Provider cadence is independent of whether the delivering gateway simulates HELM. */
    fun policyFor(snapshot: WeatherSnapshot): FreshnessPolicy =
        if (snapshot.provider?.kind == WeatherProviderKind.FORECAST) FORECAST else QualityEvaluator.SHARED
    fun evaluate(
        snapshot: WeatherSnapshot,
        nowEpochMs: Long,
        policy: FreshnessPolicy = QualityEvaluator.SHARED,
    ): TelemetryStatus {
        if (snapshot.status in setOf(TelemetryStatus.UNAVAILABLE, TelemetryStatus.INVALID, TelemetryStatus.LOST)) return snapshot.status
        val provider = snapshot.provider ?: return TelemetryStatus.INVALID
        val delivery = snapshot.delivery ?: return TelemetryStatus.INVALID
        val published = snapshot.timestampEpochMs ?: return TelemetryStatus.INVALID
        val observed = snapshot.observedAtEpochMs ?: return TelemetryStatus.INVALID
        if (snapshot.simulated != (provider.kind == WeatherProviderKind.SIMULATED)) return TelemetryStatus.INVALID
        if (snapshot.simulated && (snapshot.simulation == null || snapshot.simulation.observedAtEpochMs != observed)) return TelemetryStatus.INVALID
        if (!snapshot.simulated && snapshot.simulation != null) return TelemetryStatus.INVALID
        // Refreshing a gateway response must not make an old external observation fresh.
        val stamp = if (snapshot.simulated) published else minOf(published, observed)
        val quality = TelemetryQuality(snapshot.status, stamp, "${provider.id} via ${delivery.gatewayId}", demo = false)
        val status = QualityEvaluator.evaluate(quality, nowEpochMs, policy)
        // Check both wall clocks so a future publication cannot hide behind an old observation.
        if (published.toDouble() - nowEpochMs.toDouble() > 5_000 ||
            !snapshot.simulated && observed.toDouble() - nowEpochMs.toDouble() > 5_000) {
            return if (status == TelemetryStatus.LOST) status else TelemetryStatus.STALE
        }
        return status
    }
}

/** Independent read-only weather boundary. No provider credentials or command path. */
interface WeatherSourcePort {
    val identity: SourceIdentity
    val snapshots: SourceFeed<WeatherSnapshot>
    val freshnessPolicy: FreshnessPolicy get() = QualityEvaluator.SHARED
    fun advance(elapsedMillis: Long)
    fun close()
}

class UnavailableWeatherSource(
    override val identity: SourceIdentity,
    reason: String = "Weather provider is not configured",
) : WeatherSourcePort {
    private val feed = MutableSourceFeed(WeatherSnapshot.unavailable(reason))
    override val snapshots: SourceFeed<WeatherSnapshot> get() = feed
    override fun advance(elapsedMillis: Long) = Unit
    override fun close() = feed.close()
}

/**
 * Deterministic local provider, separately owned by the source session. HELM contributes
 * only a read-only simulation clock/location. Provider health never mutates vessel state.
 * Pump after HELM.advance so both snapshots represent the same simulated instant.
 */
class MockWeatherSource(
    override val identity: SourceIdentity,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val helmState: () -> HelmState = { HelmState() },
) : WeatherSourcePort {
    private val lock = Any()
    private var closed = false
    private val observationOrigin = Instant.parse("2026-10-03T00:00:00Z").toEpochMilli()
    private val initialSecondOfDay = helmState().time.toSecondOfDay()
    private val feed = MutableSourceFeed(sample())
    override val snapshots: SourceFeed<WeatherSnapshot> get() = feed

    init { require(identity.mode == SourceMode.DEMO) { "Mock weather requires DEMO identity" } }

    override fun advance(elapsedMillis: Long) = synchronized(lock) {
        if (closed) return@synchronized
        require(elapsedMillis in 0..86_400_000) { "Advance must be between zero and one day" }
        feed.emit(sample())
        Unit
    }

    override fun close() = synchronized(lock) {
        closed = true
        feed.close()
    }

    private fun sample(): WeatherSnapshot {
        val vessel = helmState()
        val elapsed = vessel.timeTick.toDouble()
        val days = (initialSecondOfDay + vessel.timeTick) / 86_400
        val observed = observationOrigin + days * 86_400_000 + vessel.time.toSecondOfDay() * 1_000L
        val locationAvailable = vessel.latitudeDegrees != null && vessel.longitudeDegrees != null
        return WeatherSnapshot(
            status = TelemetryStatus.LIVE,
            provider = WeatherProvider("NO5_LOCAL_WEATHER_SIMULATOR", "Local weather simulator", WeatherProviderKind.SIMULATED),
            delivery = WeatherDelivery(identity.label, "IN_PROCESS"),
            capabilities = WeatherCapabilities(true, true, true, true, locationAvailable),
            values = WeatherValues(
                airTemperatureC = 22.4 + sin(elapsed / 900) * .8,
                windSpeedKn = 10.6 + sin(elapsed / 90) * 2.1,
                windDirectionDegrees = (225.0 + sin(elapsed / 180) * 12) % 360,
                relativeHumidityPercent = 64.0 + sin(elapsed / 1200) * 4,
                pressureHpa = 1016.0 + sin(elapsed / 1800) * 1.5,
                latitudeDegrees = vessel.latitudeDegrees.takeIf { locationAvailable },
                longitudeDegrees = vessel.longitudeDegrees.takeIf { locationAvailable },
            ),
            timestampEpochMs = nowEpochMs(),
            observedAtEpochMs = observed,
            simulation = WeatherSimulation(elapsed, observed, vessel.running, 1.0, if (vessel.running) "CRUISE" else "PAUSED", "LOCAL_HELM_DEMO"),
            simulated = true,
            receivedAtEpochMs = nowEpochMs(),
            pressureReference = WeatherPressureReference.AMBIENT,
        )
    }
}

/** Locale-stable ENV strings. Existing native scene labels own the numeric units. */
data class WeatherPresentation(
    val status: TelemetryStatus,
    val statusLabel: String,
    val providerLabel: String,
    val deliveryLabel: String,
    val airTemperature: String,
    val windSpeed: String,
    val windDirection: String,
    val humidity: String,
    val pressure: String,
    val observedLocation: String,
    val observationTime: String,
    val evidenceLabel: String,
) {
    companion object {
        fun from(snapshot: WeatherSnapshot, nowEpochMs: Long, policy: FreshnessPolicy = QualityEvaluator.SHARED): WeatherPresentation {
            val status = WeatherFreshness.evaluate(snapshot, nowEpochMs, policy)
            val usable = status == TelemetryStatus.LIVE || status == TelemetryStatus.STALE
            fun numeric(value: Double?, supported: Boolean, decimals: Int): String =
                if (usable && supported && value != null) String.format(Locale.ROOT, "%.${decimals}f", value) else "—"
            val value = snapshot.values
            val cap = snapshot.capabilities
            val direction = value.windDirectionDegrees?.takeIf { usable && cap.wind }
            val latitude = value.latitudeDegrees?.takeIf { usable && cap.observedLocation }
            val longitude = value.longitudeDegrees?.takeIf { usable && cap.observedLocation }
            return WeatherPresentation(
                status = status,
                statusLabel = "${when { snapshot.simulated -> "SIMULATED"; snapshot.provider?.kind == WeatherProviderKind.FORECAST -> "FORECAST"; else -> "WEATHER" }} · ${status.name}",
                providerLabel = snapshot.provider?.label ?: "No weather provider",
                deliveryLabel = snapshot.delivery?.let { "${it.gatewayId} · ${it.transport}" } ?: "—",
                airTemperature = numeric(value.airTemperatureC, cap.airTemperature, 1),
                windSpeed = numeric(value.windSpeedKn, cap.wind, 1),
                windDirection = direction?.let { String.format(Locale.ROOT, "%03d°", (it + .5).toInt() % 360) } ?: "—",
                humidity = numeric(value.relativeHumidityPercent, cap.humidity, 0),
                pressure = numeric(value.pressureHpa, cap.pressure, 0),
                observedLocation = if (latitude != null && longitude != null) String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude) else "—",
                observationTime = snapshot.observedAtEpochMs?.takeIf { usable }?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toString() } ?: "—",
                evidenceLabel = when (snapshot.issue) {
                    WeatherIssue.BOAT_POSITION_UNAVAILABLE -> if (usable) "NO POSITION · STALE CACHE" else "NO BOAT POSITION"
                    WeatherIssue.BOAT_POSITION_STALE -> if (usable) "STALE POSITION · CACHE" else "BOAT POSITION STALE"
                    WeatherIssue.LOCATION_SHARING_DISABLED -> "LOCATION NOT SHARED"
                    WeatherIssue.PROVIDER_UNAVAILABLE -> if (usable) "FORECAST CACHE · STALE" else "PROVIDER UNAVAILABLE"
                    null -> when {
                        snapshot.scene != null -> "SIMULATED SCENE · $status"
                        snapshot.provider?.id?.contains("meteo", ignoreCase = true) == true -> "OPEN-METEO · FORECAST · $status"
                        else -> "${if (snapshot.simulated) "SIMULATED" else if (snapshot.provider?.kind == WeatherProviderKind.FORECAST) "FORECAST" else "WEATHER"} · $status"
                    }
                },
            )
        }
    }
}
