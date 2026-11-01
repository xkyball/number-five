package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.json.JSONTokener
import java.time.LocalTime

class PiProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Canonical No5 gateway V1 contract, shared by simulation and a conforming future Pi. */
object PiV1Decoder {
    private val vesselTimePattern = Regex("[0-2][0-9]:[0-5][0-9]:[0-5][0-9]")
    const val SOURCE = "NO5_PI_SIMULATOR"
    private val helmUnits = mapOf(
        "speed" to "kn", "heading" to "deg_true", "courseOverGroundDegrees" to "deg_true",
        "depth" to "m", "waterTemp" to "degC", "rpm" to "rpm", "trim" to "percent",
        "voltage" to "V", "fuel" to "percent", "coolant" to "degC", "oil" to "bar",
        "fuelFlow" to "L/h", "position" to "deg", "fuelRemainingLiters" to "L",
    )
    private val weatherUnits = mapOf(
        "airTemperatureC" to "degC", "windSpeedKn" to "kn", "windDirectionDegrees" to "deg_true_from",
        "relativeHumidityPercent" to "percent", "pressureHpa" to "hPa",
        "latitudeDegrees" to "deg", "longitudeDegrees" to "deg",
    )

    /** Confirms gateway identity/capabilities independently of sensor availability. */
    fun gatewayStatus(json: String): Unit = decode {
        val root = jsonEnvelope(json, "status")
        optionalSimulation(root)
        val endpoints = root.getJSONArray("availableEndpoints")
        val unique = mutableSetOf<String>()
        for (index in 0 until endpoints.length()) {
            val path = endpoints.get(index) as? String ?: error("Endpoint must be a string")
            require(path.matches(Regex("^/v1/[A-Za-z0-9/_-]+$")) && unique.add(path)) { "Invalid or duplicate endpoint" }
        }
        val capabilities = root.obj("capabilities")
        for (name in listOf("helm", "weather", "audio", "sonar", "hardwareWrites")) capabilities.boolean(name)
    }

    fun helm(json: String): HelmState = decode {
        val root = envelope(json, "helm", helmUnits)
        val status = status(root)
        val quality = TelemetryQuality(status, root.integer("publishedAtEpochMs").also { require(it > 0) }, root.string("source"), demo = false, simulated = root.boolean("simulated"))
        val simulation = optionalSimulation(root)
        if (status in hiddenStatuses) return@decode HelmState.unavailable(source = quality.source).copy(
            quality = quality, sourceSessionId = root.string("serverSessionId"),
            simulationRunId = if (simulation == null) null else root.obj("simulation").let { if (it.has("runId")) it.string("runId") else null },
            simulationElapsedSeconds = simulation?.elapsedSeconds, simulationTimeScale = simulation?.timeScale,
        )
        val capabilities = root.obj("capabilities")
        require(capabilities.boolean("telemetry") && !capabilities.boolean("commands")) { "HELM V1 is read-only telemetry" }
        val running = root.boolean("running")
        require(simulation == null || simulation.running == running) { "Conflicting simulator running state" }
        val vesselTime = root.string("vesselTime")
        require(vesselTimePattern.matches(vesselTime)) { "Invalid vessel time" }
        val values = root.obj("values")
        val extras = root.obj("extras")
        for ((capability, fields) in mapOf("depth" to listOf("depth"), "waterTemperature" to listOf("waterTemp"), "position" to listOf("latitudeDegrees", "longitudeDegrees"))) {
            if (!capabilities.boolean(capability)) require(fields.all { values.isNull(it) }) { "Unsupported HELM capability contains values" }
        }
        require(values.isNull("latitudeDegrees") == values.isNull("longitudeDegrees")) { "Position requires both coordinates" }
        val fields = helmFields(root.obj("fieldQuality"), values, extras, quality, root.integer("timestampEpochMs"))
        HelmState(
            portRpm = values.projectedNumber("portRpm", 0.0..100_000.0),
            starboardRpm = values.projectedNumber("starboardRpm", 0.0..100_000.0),
            portTrim = values.projectedNumber("portTrim", 0.0..100.0),
            starboardTrim = values.projectedNumber("starboardTrim", 0.0..100.0),
            heading = values.projectedNumber("heading", 0.0..359.999999999),
            speed = values.projectedNumber("speed", 0.0..200.0),
            depth = values.optionalNumber("depth", 0.0..12_000.0),
            waterTemp = values.optionalNumber("waterTemp", -5.0..60.0),
            portVoltage = values.projectedNumber("portVoltage", 0.0..60.0),
            starboardVoltage = values.projectedNumber("starboardVoltage", 0.0..60.0),
            houseVoltage = values.projectedNumber("houseVoltage", 0.0..60.0),
            portFuel = values.projectedNumber("portFuel", 0.0..100.0),
            starboardFuel = values.projectedNumber("starboardFuel", 0.0..100.0),
            time = LocalTime.parse(vesselTime),
            timeTick = simulation?.elapsedSeconds?.toLong() ?: root.integer("timestampEpochMs") / 1000,
            running = running,
            quality = quality,
            portCoolantC = values.optionalNumber("portCoolantC", -50.0..200.0),
            starboardCoolantC = values.optionalNumber("starboardCoolantC", -50.0..200.0),
            portOilBar = values.optionalNumber("portOilBar", 0.0..100.0),
            starboardOilBar = values.optionalNumber("starboardOilBar", 0.0..100.0),
            portFuelFlowLph = values.optionalNumber("portFuelFlowLph", 0.0..10_000.0),
            starboardFuelFlowLph = values.optionalNumber("starboardFuelFlowLph", 0.0..10_000.0),
            totalFuelFlowLph = values.optionalNumber("totalFuelFlowLph", 0.0..20_000.0),
            portGear = values.gear("portGear"), starboardGear = values.gear("starboardGear"),
            latitudeDegrees = values.optionalNumber("latitudeDegrees", -90.0..90.0),
            longitudeDegrees = values.optionalNumber("longitudeDegrees", -180.0..180.0),
            fuelRemainingLiters = values.optionalNumber("fuelRemainingLiters", 0.0..1_000_000.0),
            courseOverGroundDegrees = values.optionalNumber("courseOverGroundDegrees", 0.0..359.999999999),
            tripDistanceNm = extras.optionalNumber("tripDistanceNm", 0.0..1_000_000.0),
            tripDurationSeconds = extras.optionalNumber("tripDurationSeconds", 0.0..31_536_000_000.0),
            fuelUsedLiters = extras.optionalNumber("fuelUsedLiters", 0.0..1_000_000.0),
            efficiencyNmPerLiter = extras.optionalNumber("efficiencyNmPerLiter", 0.0..1_000_000.0),
            telemetryFields = fields,
            sourceSessionId = root.string("serverSessionId"),
            simulationPhase = simulation?.phase,
            simulationRunId = if (simulation == null) null else root.obj("simulation").let { if (it.has("runId")) it.string("runId") else null },
            simulationElapsedSeconds = simulation?.elapsedSeconds,
            simulationTimeScale = simulation?.timeScale,
        )
    }

    fun weather(json: String): WeatherSnapshot = decode {
        val root = envelope(json, "weather", weatherUnits)
        val status = status(root)
        val provider = root.obj("provider")
        val providerKind = WeatherProviderKind.valueOf(provider.string("kind"))
        require(root.boolean("simulated") == (providerKind == WeatherProviderKind.SIMULATED)) { "Conflicting weather provenance" }
        val delivery = root.obj("delivery")
        require(delivery.string("gatewayId") == root.string("source") && delivery.string("transport") in setOf("HTTP", "HTTPS")) { "Unexpected weather delivery" }
        val capabilities = root.obj("capabilities")
        val values = root.obj("values")
        for ((capability, fields) in mapOf("airTemperature" to listOf("airTemperatureC"), "wind" to listOf("windSpeedKn", "windDirectionDegrees"), "humidity" to listOf("relativeHumidityPercent"), "pressure" to listOf("pressureHpa"), "observedLocation" to listOf("latitudeDegrees", "longitudeDegrees"))) {
            if (!capabilities.boolean(capability)) require(fields.all { values.isNull(it) }) { "Unsupported weather capability contains values" }
        }
        val scene = if (root.has("scene") && !root.isNull("scene")) root.obj("scene").let {
            require(root.boolean("simulated") && providerKind == WeatherProviderKind.SIMULATED)
            require(it.integer("version") == 1L && it.string("mode") == "SIMULATED" && it.string("clockMode") == "FIXED")
            WeatherScene(it.integer("utcEpochMs"), it.integer("revision"),
                WeatherLocation(it.number("latitudeDegrees", -90.0..90.0), it.number("longitudeDegrees", -180.0..180.0)))
        } else null
        for ((capability, field, unit) in listOf(Triple("cloudCover","cloudCoverPercent","percent"), Triple("visibility","visibilityMeters","m"))) {
            val supported = capabilities.has(capability) && capabilities.boolean(capability)
            if (supported) require(root.obj("units").string(field) == unit)
            else require(values.isNull(field)) { "Unsupported scene weather value" }
        }
        val simulation = optionalSimulation(root)
        require(root.integer("observedAtEpochMs") > 0) { "Missing weather observation time" }
        require(simulation == null || root.integer("observedAtEpochMs") == simulation.observedAtEpochMs) { "Conflicting weather simulation clocks" }
        val provenance = if (root.has("provenance") && !root.isNull("provenance")) root.obj("provenance") else null
        provenance?.let {
            if (it.has("validAtEpochMs")) require(it.integer("validAtEpochMs") == root.integer("observedAtEpochMs")) { "Conflicting model validity time" }
            if (it.has("fetchedAtEpochMs")) require(it.integer("fetchedAtEpochMs") == root.integer("timestampEpochMs")) { "Conflicting provider fetch time" }
        }
        val requestedLocation = provenance?.takeIf { it.has("requestedLatitude") || it.has("requestedLongitude") }?.let {
            WeatherLocation(it.number("requestedLatitude", -90.0..90.0), it.number("requestedLongitude", -180.0..180.0))
        }
        WeatherSnapshot(
            status = status,
            provider = WeatherProvider(provider.string("id"), provider.string("label"), providerKind),
            delivery = WeatherDelivery(delivery.string("gatewayId"), delivery.string("transport")),
            capabilities = WeatherCapabilities(
                airTemperature = capabilities.boolean("airTemperature"), wind = capabilities.boolean("wind"),
                humidity = capabilities.boolean("humidity"), pressure = capabilities.boolean("pressure"),
                observedLocation = capabilities.boolean("observedLocation"),
                cloudCover = capabilities.has("cloudCover") && capabilities.boolean("cloudCover"),
                visibility = capabilities.has("visibility") && capabilities.boolean("visibility"),
            ),
            values = if (status in hiddenStatuses) WeatherValues() else WeatherValues(
                airTemperatureC = values.optionalNumber("airTemperatureC", -80.0..80.0),
                windSpeedKn = values.optionalNumber("windSpeedKn", 0.0..250.0),
                windDirectionDegrees = values.optionalNumber("windDirectionDegrees", 0.0..359.999999999),
                relativeHumidityPercent = values.optionalNumber("relativeHumidityPercent", 0.0..100.0),
                pressureHpa = values.optionalNumber("pressureHpa", 800.0..1_200.0),
                latitudeDegrees = values.optionalNumber("latitudeDegrees", -90.0..90.0),
                longitudeDegrees = values.optionalNumber("longitudeDegrees", -180.0..180.0),
                cloudCoverPercent = if (values.has("cloudCoverPercent")) values.optionalNumber("cloudCoverPercent", 0.0..100.0) else null,
                visibilityMeters = if (values.has("visibilityMeters")) values.optionalNumber("visibilityMeters", 0.0..1_000_000.0) else null,
            ),
            timestampEpochMs = root.integer("timestampEpochMs"),
            observedAtEpochMs = root.integer("observedAtEpochMs"),
            simulation = simulation, simulated = root.boolean("simulated"),
            requestedLocation = requestedLocation, scene = scene,
            pressureReference = if (root.has("pressureReference")) WeatherPressureReference.valueOf(root.string("pressureReference")) else WeatherPressureReference.UNSPECIFIED,
        )
    }

    private val hiddenStatuses = setOf(TelemetryStatus.LOST, TelemetryStatus.INVALID, TelemetryStatus.UNAVAILABLE)

    private fun envelope(json: String, kind: String, units: Map<String, String>): JSONObject {
        val root = jsonEnvelope(json, kind)
        val declared = root.obj("units")
        val extraUnits = if (kind == "weather") mapOf("cloudCoverPercent" to "percent", "visibilityMeters" to "m") else emptyMap()
        val keys = declared.keys().asSequence().toSet()
        require(keys.containsAll(units.keys) && keys.all { it in units || it in extraUnits }) { "Unexpected source units" }
        for ((key, value) in extraUnits) if (key in keys) require(declared.string(key) == value)
        for ((key, value) in units) require(declared.string(key) == value) { "Wrong source unit: $key" }
        return root
    }

    private fun jsonEnvelope(json: String, kind: String): JSONObject {
        require(json.toByteArray(Charsets.UTF_8).size <= 65_536) { "Pi JSON exceeds size limit" }
        StrictJsonSyntax.validate(json)
        val token = JSONTokener(json)
        val root = token.nextValue() as? JSONObject ?: error("Pi JSON must be an object")
        require(token.nextClean() == '\u0000') { "Unexpected trailing content" }
        require(root.integer("version") == 1L) { "Unsupported Pi protocol version" }
        require(root.string("kind") == kind) { "Wrong feature payload" }
        root.string("source")
        root.string("serverSessionId")
        val sourceKind = root.string("sourceKind")
        require(sourceKind in setOf("REAL", "SIMULATED") && root.boolean("simulated") == (sourceKind == "SIMULATED")) { "Conflicting source provenance" }
        require(root.integer("timestampEpochMs") > 0) { "Missing source timestamp" }
        return root
    }

    private val helmNumericValues = listOf(
        "portRpm", "starboardRpm", "portTrim", "starboardTrim", "heading", "speed", "depth", "waterTemp",
        "portVoltage", "starboardVoltage", "houseVoltage", "portFuel", "starboardFuel", "portCoolantC", "starboardCoolantC",
        "portOilBar", "starboardOilBar", "portFuelFlowLph", "starboardFuelFlowLph", "totalFuelFlowLph", "latitudeDegrees",
        "longitudeDegrees", "fuelRemainingLiters", "courseOverGroundDegrees",
    )
    private val helmNumericExtras = listOf("tripDistanceNm", "tripDurationSeconds", "fuelUsedLiters", "efficiencyNmPerLiter")

    private fun helmFields(qualities: JSONObject, values: JSONObject, extras: JSONObject, envelope: TelemetryQuality, oldestObservation: Long): Map<String, TelemetryValue> {
        val fields = LinkedHashMap<String, TelemetryValue>(40)
        var oldest: Long? = null
        fun addFields(names: List<String>, container: JSONObject) {
            for (name in names) {
                require(container.has(name)) { "Missing telemetry field: $name" }
                val value = if (container.isNull(name)) null else (container.get(name) as? Number)?.toDouble() ?: error("$name must be numeric or null")
                val declared = qualities.obj(name)
                val state = TelemetryStatus.valueOf(declared.string("status"))
                require(declared.has("timestampEpochMs")) { "Missing per-field source time" }
                val stamp = if (declared.isNull("timestampEpochMs")) null else declared.integer("timestampEpochMs").also { require(it > 0) }
                require(stamp == null || stamp <= envelope.timestampEpochMs!!) { "Observation cannot follow its publication" }
                val usable = state == TelemetryStatus.LIVE || state == TelemetryStatus.STALE
                require(!usable || value != null && stamp != null) { "Usable fields require value and source time" }
                require(usable || value == null) { "Unavailable fields cannot retain numeric values" }
                fields[name] = TelemetryValue(value, TelemetryQuality(state, stamp, envelope.source, simulated = envelope.simulated))
                if (value != null && stamp != null && (oldest == null || stamp < oldest!!)) oldest = stamp
            }
        }
        addFields(helmNumericValues, values)
        addFields(helmNumericExtras, extras)
        require(oldest != null && oldest == oldestObservation) { "Envelope time must be the oldest included observation" }
        return fields
    }

    private fun JSONObject.projectedNumber(key: String, range: ClosedFloatingPointRange<Double>): Double = optionalNumber(key, range) ?: 0.0

    private fun optionalSimulation(root: JSONObject): WeatherSimulation? {
        if (root.boolean("simulated")) return simulation(root.obj("simulation"))
        require(!root.has("simulation") || root.isNull("simulation")) { "Real sources cannot carry synthetic clocks" }
        return null
    }

    private fun simulation(root: JSONObject) = WeatherSimulation(
        elapsedSeconds = root.number("elapsedSeconds", 0.0..31_536_000_000.0),
        observedAtEpochMs = root.integer("observedAtEpochMs"), running = root.boolean("running"),
        timeScale = root.number("timeScale", 0.0..10_000.0), phase = root.string("phase"), scenario = root.string("scenario"),
    )

    private fun status(root: JSONObject): TelemetryStatus = TelemetryStatus.valueOf(root.string("status"))
    private fun JSONObject.obj(key: String) = get(key) as? JSONObject ?: error("$key must be an object")
    private fun JSONObject.string(key: String): String = (get(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) }
        ?: error("$key must be a nonempty string")
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("$key must be a boolean")
    private fun JSONObject.integer(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "$key must be an integer" }
        return (value as Number).toLong()
    }
    private fun JSONObject.number(key: String, range: ClosedFloatingPointRange<Double>): Double {
        val value = (get(key) as? Number)?.toDouble() ?: error("$key must be a number")
        require(value.isFinite() && value in range) { "$key is outside the protocol range" }
        return value
    }
    private fun JSONObject.optionalNumber(key: String, range: ClosedFloatingPointRange<Double>): Double? {
        require(has(key)) { "$key is missing" }
        return if (isNull(key)) null else number(key, range)
    }
    private fun JSONObject.gear(key: String): String? {
        require(has(key)) { "$key is missing" }
        return if (isNull(key)) null else string(key).also { require(it in setOf("F", "N", "R")) { "Unknown gear" } }
    }
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (error: Exception) {
        throw PiProtocolException("Invalid No5 gateway V1 response", error)
    }
}
