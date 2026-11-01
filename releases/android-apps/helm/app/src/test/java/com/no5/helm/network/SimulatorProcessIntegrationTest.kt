package com.no5.helm.network

import com.no5.domain.TelemetryStatus
import com.no5.domain.WeatherProviderKind
import org.json.JSONObject
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/** Actual Python service → TCP/HTTP → Android transport/decoder. No Android raster claim. */
class SimulatorProcessIntegrationTest {
    @Test fun standaloneServiceFeedsTypedVesselAndIndependentWeather() {
        val python = System.getenv("NO5_SIMULATOR_PYTHON") ?: if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        val available = runCatching { ProcessBuilder(python, "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
        assumeTrue("Python 3 is required for standalone simulator integration", available)
        val root = sequenceOf(File("."), File(".."), File("../..")).first { File(it, "simulator/server.py").isFile }.canonicalFile
        val port = ServerSocket(0).use { it.localPort }
        val endpoint = "http://127.0.0.1:$port"
        val log = File.createTempFile("no5-simulator-integration", ".log")
        // Only this test launcher injects model response bytes. Production keeps its real provider and has no fallback.
        val launcher = simulatorForecastFixturePython() + "\n" + """
            from simulator.server import Simulation, SimulationServer
            simulation = Simulation(running=False)
            simulation.weather = fixture_weather(simulation)
            server = SimulationServer(('127.0.0.1', $port), simulation)
            try:
                server.serve_forever()
            finally:
                server.server_close()
        """.trimIndent()
        val process = ProcessBuilder(python, "-c", launcher)
            .directory(root).redirectErrorStream(true).redirectOutput(log).start()
        val helm = UrlConnectionPiTransport(endpoint, PiNetworkPolicy.HELM)
        val weather = UrlConnectionPiTransport(endpoint, PiNetworkPolicy.WEATHER)
        try {
            var ready = false
            repeat(100) {
                if (!ready) { ready = runCatching { helm.get("/v1/helm"); true }.getOrDefault(false); if (!ready) Thread.sleep(50) }
            }
            assertTrue("Standalone simulator did not become ready", ready)
            val stopped = PiV1Decoder.helm(helm.get("/v1/helm"))
            assertEquals(0.0, stopped.speed, 0.0)
            assertTrue(stopped.quality.simulated); assertFalse(stopped.quality.demo)
            assertEquals(12.0, stopped.depth!!, 0.0); assertNull(stopped.waterTemp)
            simulatorControlCommand(endpoint, "apply") { it.put("timeScale", 50) }
            simulatorControlCommand(endpoint, "start")
            var moving = stopped
            repeat(30) {
                if (moving.speed <= 0) { Thread.sleep(100); moving = PiV1Decoder.helm(helm.get("/v1/helm")) }
            }
            assertTrue("Full-throttle scenario must depart the stop", moving.speed > 0)
            assertTrue(moving.portRpm > 0); assertTrue(moving.totalFuelFlowLph!! > 0)
            assertTrue(moving.fuelRemainingLiters!! < stopped.fuelRemainingLiters!!)
            simulatorControlCommand(endpoint, "pause")
            val pausedHelm = PiV1Decoder.helm(helm.get("/v1/helm"))
            val pausedWeather = PiV1Decoder.weather(weather.get("/v1/weather"))
            assertFalse(pausedHelm.running)
            assertFalse(pausedWeather.simulated)
            assertNull(pausedWeather.simulation)
            assertEquals(WeatherProviderKind.FORECAST, pausedWeather.provider?.kind)
            assertEquals("OPEN_METEO", pausedWeather.provider?.id)
            // Provider grid-cell coordinates are not falsely claimed as boat-location precision.
            assertEquals(-34.27, pausedWeather.values.latitudeDegrees!!, 0.000001)
            assertEquals(18.63, pausedWeather.values.longitudeDegrees!!, 0.000001)
            assertTrue(pausedWeather.observedAtEpochMs!! <= pausedWeather.timestampEpochMs!!)
            assertTrue(System.currentTimeMillis() - pausedWeather.observedAtEpochMs!! < 16 * 60_000)
            val observed = pausedWeather.observedAtEpochMs
            simulatorControlCommand(endpoint, "apply") { it.put("timeScale", 1) }
            assertEquals(observed, PiV1Decoder.weather(weather.get("/v1/weather")).observedAtEpochMs)
            simulatorControlCommand(endpoint, "apply") { it.put("weatherFault", "failed") }
            assertTrue(runCatching { weather.get("/v1/weather") }.isFailure)
            assertEquals(TelemetryStatus.LIVE, PiV1Decoder.helm(helm.get("/v1/helm")).quality.status)
            simulatorControlCommand(endpoint, "apply") { it.put("weatherFault", "none") }
            assertEquals(TelemetryStatus.LIVE, PiV1Decoder.weather(weather.get("/v1/weather")).status)
        } finally {
            helm.close(); weather.close(); process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS) }
            log.delete()
        }
    }

}

/** Test-only fixture injection: real-provider wire shape, never a production synthetic fallback. */
internal fun simulatorForecastFixturePython(): String = """
    import io, json, time
    from simulator.weather import RealWeatherProvider
    def fixture_weather(simulation):
        def open_fixture(request, timeout):
            now = int(time.time())
            payload = {
                'latitude': -34.27, 'longitude': 18.63,
                'current_units': {'time': 'unixtime', 'temperature_2m': '°C',
                    'relative_humidity_2m': '%', 'pressure_msl': 'hPa',
                    'wind_speed_10m': 'kn', 'wind_direction_10m': '°'},
                'current': {'time': now // 900 * 900, 'temperature_2m': 14.2,
                    'relative_humidity_2m': 68, 'pressure_msl': 1014.2,
                    'wind_speed_10m': 11.0, 'wind_direction_10m': 225.0}}
            return io.BytesIO(json.dumps(payload).encode())
        provider = RealWeatherProvider(simulation.weather_coordinates,
            simulation.monotonic, simulation.wall_epoch_ms, opener=open_fixture)
        assert provider.refresh(), 'Test-only FORECAST fixture did not validate'
        return provider
""".trimIndent()

/** Raw development-control HTTP only; the production Pi transport must keep rejecting this write path. */
internal fun simulatorControlCommand(
    endpoint: String,
    action: String,
    settingsChanges: ((JSONObject) -> Unit)? = null,
): JSONObject {
    val read = URL("$endpoint/v1/simulation").openConnection() as HttpURLConnection
    val state = try {
        read.connectTimeout = 2000; read.readTimeout = 2000
        assertEquals(200, read.responseCode)
        read.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    } finally { read.disconnect() }
    val command = JSONObject().put("version", 1)
        .put("serverInstanceId", state.getString("serverInstanceId"))
        .put("expectedRevision", state.getLong("revision"))
        .put("requestId", UUID.randomUUID().toString()).put("action", action)
    if (action == "apply") {
        val settings = state.getJSONObject("appliedSettings")
        settingsChanges?.invoke(settings)
        command.put("settings", settings)
    } else require(settingsChanges == null)
    val connection = URL("$endpoint/v1/simulation").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 2000; connection.readTimeout = 2000
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(command.toString().toByteArray(Charsets.UTF_8)) }
        assertEquals(200, connection.responseCode)
        val receipt = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        assertEquals(command.getString("requestId"), receipt.getString("requestId"))
        assertEquals("APPLIED", receipt.getString("outcome"))
        return receipt
    } finally { connection.disconnect() }
}
