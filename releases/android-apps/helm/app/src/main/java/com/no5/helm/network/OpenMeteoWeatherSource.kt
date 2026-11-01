package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import kotlin.math.*

/** No credential, endpoint override, payment, account creation or write API is supported. */
interface OpenMeteoTransport : AutoCloseable {
    fun current(location: WeatherLocation): String
}

/** Fixed provider URL over the same deadline-tested client used by the independent Pi ports. */
class UrlConnectionOpenMeteoTransport : OpenMeteoTransport {
    private val client = BoundedHttpJsonClient(PiNetworkPolicy(
        pollIntervalMs = 60_000, connectTimeoutMs = 3_000, readTimeoutMs = 4_000,
        requestTimeoutMs = 8_000, maxResponseBytes = MAX_BYTES,
    ))
    override fun current(location: WeatherLocation): String = client.get(requestUrl(location))
    override fun close() = client.close()

    companion object {
        const val MAX_BYTES = 32_768
        const val ATTRIBUTION_URL = "https://open-meteo.com/"
        const val DOCUMENTATION_URL = "https://open-meteo.com/en/docs"
        fun requestUrl(location: WeatherLocation): String {
            val lat = String.format(Locale.ROOT, "%.5f", location.latitudeDegrees)
            val lon = String.format(Locale.ROOT, "%.5f", location.longitudeDegrees)
            return "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,cloud_cover,visibility,weather_code" +
                "&wind_speed_unit=kn&temperature_unit=celsius&timeformat=unixtime&timezone=GMT&cell_selection=sea&forecast_hours=24"
        }
    }
}

/** Open-Meteo 'current' is weather-model output, never an onboard measurement. */
object OpenMeteoDecoder {
    fun current(json: String, receivedAtEpochMs: Long, requestedLocation: WeatherLocation): WeatherSnapshot {
        require(json.toByteArray(Charsets.UTF_8).size <= UrlConnectionOpenMeteoTransport.MAX_BYTES) { "Weather JSON exceeds size limit" }
        StrictJsonSyntax.validate(json)
        val token = JSONTokener(json)
        val root = token.nextValue() as? JSONObject ?: error("Weather JSON must be an object")
        require(token.nextClean() == '\u0000') { "Trailing weather data" }
        require(!root.optBoolean("error", false)) { "Weather provider reported an error" }
        require(root.get("utc_offset_seconds") is Number && root.getDouble("utc_offset_seconds") == 0.0) { "Weather timestamps must be UTC" }
        val units = root.getJSONObject("current_units")
        val expectedUnits = mapOf("time" to "unixtime", "interval" to "seconds", "temperature_2m" to "°C",
            "relative_humidity_2m" to "%", "pressure_msl" to "hPa", "wind_speed_10m" to "kn",
            "wind_direction_10m" to "°", "wind_gusts_10m" to "kn")
        for ((key, unit) in expectedUnits) require(units.get(key) == unit) { "Unexpected weather unit: $key" }
        val current = root.getJSONObject("current")
        // Optional additions: older/missing model fields never invalidate otherwise usable weather.
        fun extra(key: String, unit: String, range: ClosedFloatingPointRange<Double>): Double? {
            if (!current.has(key)) return null
            require(units.optString(key) == unit) { "Unexpected weather unit: $key" }
            return current.optionalNumber(key, range)
        }
        val clouds = extra("cloud_cover", "%", 0.0..100.0)
        val visibility = extra("visibility", "m", 0.0..1_000_000.0)
        val code = extra("weather_code", "wmo code", 0.0..99.0)
        require(code == null || code % 1.0 == 0.0) { "Weather code must be an integer" }
        val seconds = current.get("time")
        require(seconds is Int || seconds is Long) { "Weather validity time must be an integer" }
        val observed = Math.multiplyExact((seconds as Number).toLong(), 1_000L)
        require(observed >= 0)
        val interval = current.number("interval", 1.0..3_600.0)
        require(interval % 1 == 0.0) { "Weather interval must be whole seconds" }
        val grid = WeatherLocation(root.number("latitude", -90.0..90.0), root.number("longitude", -180.0..180.0))
        return WeatherSnapshot(
            status = TelemetryStatus.LIVE,
            provider = WeatherProvider("OPEN_METEO", "Open-Meteo · forecast model", WeatherProviderKind.FORECAST),
            delivery = WeatherDelivery("api.open-meteo.com", "HTTPS"),
            capabilities = WeatherCapabilities(true, true, true, true, true,
                cloudCover = current.has("cloud_cover"), visibility = current.has("visibility"),
                weatherCode = current.has("weather_code")),
            values = WeatherValues(
                airTemperatureC = current.optionalNumber("temperature_2m", -100.0..100.0),
                windSpeedKn = current.optionalNumber("wind_speed_10m", 0.0..250.0),
                // The upstream API may emit 360 for north; typed domain uses [0, 360).
                windDirectionDegrees = current.optionalNumber("wind_direction_10m", 0.0..360.0)?.let { if (it == 360.0) 0.0 else it },
                relativeHumidityPercent = current.optionalNumber("relative_humidity_2m", 0.0..100.0),
                pressureHpa = current.optionalNumber("pressure_msl", 100.0..1_200.0),
                latitudeDegrees = grid.latitudeDegrees,
                longitudeDegrees = grid.longitudeDegrees,
                windGustSpeedKn = current.optionalNumber("wind_gusts_10m", 0.0..300.0),
                cloudCoverPercent = clouds, visibilityMeters = visibility, weatherCode = code?.toInt(),
            ),
            timestampEpochMs = observed, observedAtEpochMs = observed, receivedAtEpochMs = receivedAtEpochMs,
            simulated = false, pressureReference = WeatherPressureReference.MEAN_SEA_LEVEL,
            requestedLocation = requestedLocation,
        )
    }

    private fun JSONObject.number(key: String, range: ClosedFloatingPointRange<Double>): Double {
        val value = (get(key) as? Number)?.toDouble() ?: error("$key must be numeric")
        require(value.isFinite() && value in range) { "Invalid weather field: $key" }
        return value
    }

    private fun JSONObject.optionalNumber(key: String, range: ClosedFloatingPointRange<Double>): Double? {
        require(has(key)) { "Missing weather field: $key" }
        return if (isNull(key)) null else number(key, range)
    }
}

/**
 * Explicit opt-in external forecast provider. The selected source's valid boat position is
 * its only location input; no Android location permission, handset GPS or stored fallback.
 * A source owns one transport and worker, with a ten-minute cache and at most one attempt
 * per minute. A move of 5 km refreshes the cache at the next permitted attempt.
 */
class OpenMeteoWeatherSource(
    override val identity: SourceIdentity,
    private val helm: HelmSource,
    private val allowWeatherLocationSharing: Boolean,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val transport: OpenMeteoTransport = UrlConnectionOpenMeteoTransport(),
    scheduler: PiPollScheduler = BackgroundPiPollScheduler("open-meteo"),
) : WeatherSourcePort {
    private val lock = Any()
    private var closed = false
    private var requesting = false
    private var lastAttemptEpochMs: Long? = null
    private var lastSuccessEpochMs: Long? = null
    private var lastLocation: WeatherLocation? = null
    private var lastAttemptFailed = false
    private var positionUnavailable = false
    private val feed = MutableSourceFeed(WeatherSnapshot.unavailable(
        if (allowWeatherLocationSharing) "Waiting for a valid boat position" else "Location sharing with Open-Meteo is not approved",
        if (allowWeatherLocationSharing) WeatherIssue.BOAT_POSITION_UNAVAILABLE else WeatherIssue.LOCATION_SHARING_DISABLED))
    override val snapshots: SourceFeed<WeatherSnapshot> get() = feed
    override val freshnessPolicy: FreshnessPolicy get() = WeatherFreshness.FORECAST
    private val polling: SourceSubscription

    init {
        require(helm.identity == identity) { "Weather and location must belong to the same source session" }
        polling = try {
            if (allowWeatherLocationSharing) scheduler.schedule(5_000, ::poll) else SourceSubscription { }
        } catch (failure: RuntimeException) {
            closed = true
            feed.close()
            transport.close()
            throw failure
        }
    }

    override fun advance(elapsedMillis: Long) = Unit // Network I/O stays on the owned worker.

    private fun poll() {
        val now = nowEpochMs()
        val vessel = helm.snapshots.value
        val positionStatuses = listOf(QualityEvaluator.evaluate(vessel.quality, now, helm.freshnessPolicy),
            vessel.fieldStatus("latitudeDegrees", now, helm.freshnessPolicy),
            vessel.fieldStatus("longitudeDegrees", now, helm.freshnessPolicy))
        val position = try {
            vessel.fieldObservation("latitudeDegrees", vessel.latitudeDegrees).value?.let { latitude ->
                vessel.fieldObservation("longitudeDegrees", vessel.longitudeDegrees).value?.let { WeatherLocation(latitude, it) }
            }
        } catch (_: IllegalArgumentException) { null }
        val location = position?.takeIf { positionStatuses.all { it == TelemetryStatus.LIVE } }
        synchronized(lock) {
            if (closed || !allowWeatherLocationSharing || requesting) return
            if (location == null) {
                val previous = feed.value
                positionUnavailable = true
                val issue = if (position != null && positionStatuses.any { it == TelemetryStatus.STALE || it == TelemetryStatus.LOST })
                    WeatherIssue.BOAT_POSITION_STALE else WeatherIssue.BOAT_POSITION_UNAVAILABLE
                val reason = if (issue == WeatherIssue.BOAT_POSITION_STALE) "Boat position is stale" else "Waiting for a valid boat position"
                publish(if (previous.provider == null) WeatherSnapshot.unavailable(reason, issue)
                    else previous.copy(status = TelemetryStatus.STALE, error = "$reason; cached forecast retained", issue = issue))
                return
            }
            if (positionUnavailable) {
                positionUnavailable = false
                if (feed.value.provider != null) publish(feed.value.copy(
                    status = if (lastAttemptFailed) TelemetryStatus.STALE else TelemetryStatus.LIVE,
                    error = if (lastAttemptFailed) "Open-Meteo unavailable; cached forecast retained" else null,
                    issue = if (lastAttemptFailed) WeatherIssue.PROVIDER_UNAVAILABLE else null,
                ))
            }
            val lastAttempt = lastAttemptEpochMs
            if (lastAttempt != null && now.toDouble() - lastAttempt.toDouble() < MIN_ATTEMPT_MS) return
            val lastSuccess = lastSuccessEpochMs
            val moved = lastLocation?.let { distanceKm(it, location) >= 5.0 } ?: true
            if (!lastAttemptFailed && lastSuccess != null && now.toDouble() - lastSuccess.toDouble() < CACHE_MS && !moved) return
            lastAttemptEpochMs = now
            requesting = true
        }
        try {
            val response = transport.current(location!!)
            val received = nowEpochMs()
            val snapshot = OpenMeteoDecoder.current(response, received, location)
            synchronized(lock) {
                if (closed) return
                lastSuccessEpochMs = received
                lastLocation = location
                lastAttemptFailed = false
                publish(snapshot)
            }
        } catch (_: Exception) {
            synchronized(lock) {
                if (closed) return
                lastAttemptFailed = true
                val previous = feed.value
                publish(if (previous.provider == null) WeatherSnapshot.unavailable("Open-Meteo unavailable or invalid response", WeatherIssue.PROVIDER_UNAVAILABLE)
                    else previous.copy(status = TelemetryStatus.STALE, error = "Open-Meteo unavailable; cached forecast retained", issue = WeatherIssue.PROVIDER_UNAVAILABLE))
            }
        } finally {
            synchronized(lock) { requesting = false }
        }
    }

    private fun publish(snapshot: WeatherSnapshot) {
        // Feed removes throwing observers; they cannot turn a successful request into an outage.
        try { feed.emit(snapshot) } catch (_: RuntimeException) { }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            feed.close()
        }
        try { polling.close() } finally { transport.close() }
    }

    companion object {
        const val MIN_ATTEMPT_MS = 60_000L
        const val CACHE_MS = 600_000L
        internal fun distanceKm(a: WeatherLocation, b: WeatherLocation): Double {
            val lat = Math.toRadians(b.latitudeDegrees - a.latitudeDegrees)
            val lon = Math.toRadians(b.longitudeDegrees - a.longitudeDegrees)
            val haversine = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitudeDegrees)) * cos(Math.toRadians(b.latitudeDegrees)) * sin(lon / 2).pow(2)
            return 6_371.0 * 2 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
        }
    }
}
