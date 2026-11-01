package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Real production scheduler → bounded HTTP client → Python producer. No Android FPS claim. */
class SimulatorCadenceIntegrationTest {
    @Test fun productionClientMeasuresTwentyHzWithoutAcceleratingWeatherAtOneAndFiftyTimes() {
        val python = System.getenv("NO5_SIMULATOR_PYTHON") ?: "python3"
        assumeTrue("Python is needed for actual cadence evidence", runCatching {
            ProcessBuilder(python, "--version").start().waitFor(5, TimeUnit.SECONDS)
        }.getOrDefault(false))
        val root = sequenceOf(File("."), File(".."), File("../..")).first { File(it, "simulator/server.py").isFile }.canonicalFile
        val reportFolder = File(root, "verification/build").apply { mkdirs() }
        val serverReport = File(reportFolder, "temporal-server-cadence.json")
        val log = File(reportFolder, "temporal-server-cadence.log")
        val port = ServerSocket(0).use { it.localPort }
        val endpoint = "http://127.0.0.1:$port"
        // Inject only a deterministic provider response in the test launcher, never production fallback weather.
        val launcher = simulatorForecastFixturePython() + "\n" + """
            import sys
            import simulator.measure_cadence as cadence
            class FixtureMeasuredSimulation(cadence.MeasuredSimulation):
                def __init__(self):
                    super().__init__()
                    self.weather = fixture_weather(self)
            cadence.MeasuredSimulation = FixtureMeasuredSimulation
            sys.argv = ['simulator.measure_cadence'] + sys.argv[1:]
            cadence.main()
        """.trimIndent()
        val process = ProcessBuilder(python, "-c", launcher, "--port", "$port", "--report", serverReport.path)
            .directory(root).redirectErrorStream(true).redirectOutput(log).start()
        val runs = JSONArray()
        try {
            UrlConnectionPiTransport(endpoint, PiNetworkPolicy.HELM).use { readiness ->
                var ready = false
                repeat(100) {
                    if (!ready) { ready = runCatching { readiness.get("/v1/helm"); true }.getOrDefault(false); if (!ready) Thread.sleep(50) }
                }
                assertTrue(ready, "Cadence fixture did not start")
            }
            for (scale in listOf(1, 50)) {
                simulatorControlCommand(endpoint, "reset")
                if (scale == 1) {
                    // Warm the vessel past the initial 8 s stop; measurements use a moving boat.
                    simulatorControlCommand(endpoint, "apply") { it.put("timeScale", 50) }
                    simulatorControlCommand(endpoint, "start")
                    Thread.sleep(220)
                }
                simulatorControlCommand(endpoint, "apply") { it.put("timeScale", scale) }
                if (scale != 1) simulatorControlCommand(endpoint, "start")
                runs.put(measure(endpoint, scale))
            }
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS) }
            File(reportFolder, "temporal-jvm-cadence.json").writeText(JSONObject()
                .put("environment", "Host JVM running production Android transport/decoder/scheduler; no Android rendering measurement")
                .put("recordedAtEpochMs", System.currentTimeMillis()).put("runs", runs).toString(2) + "\n")
        }
        assertTrue(serverReport.isFile, "Server must report actual producer and request timing")
        val serverData = JSONObject(serverReport.readText())
        assertTrue(serverData.getInt("acceptedTcpConnections") < 30, "Persistent polling must reuse sockets instead of one connection per sample")
        assertTrue(serverData.getJSONArray("samples").length() >= 180)
    }

    private fun measure(endpoint: String, scale: Int): JSONObject {
        val receipts = Collections.synchronizedList(mutableListOf<JSONObject>())
        val requests = Collections.synchronizedList(mutableListOf<JSONObject>())
        val weatherReceipts = Collections.synchronizedList(mutableListOf<Long>())
        val complete = CountDownLatch(101)
        val identity = SourceIdentity("cadence-$scale", SourceMode.PI, "CADENCE TEST")
        val productionTransport = UrlConnectionPiTransport(endpoint, PiNetworkPolicy.HELM)
        val measuredTransport = object : PiHttpTransport {
            override fun get(path: String): String {
                val start = System.nanoTime()
                return try { productionTransport.get(path) } finally {
                    requests += JSONObject().put("startNs", start).put("durationMs", (System.nanoTime() - start) / 1e6)
                }
            }
            override fun close() = productionTransport.close()
        }
        val helm = NetworkPiHelmSource(identity, measuredTransport)
        val weather = NetworkPiWeatherSource(identity, UrlConnectionPiTransport(endpoint, PiNetworkPolicy.WEATHER))
        val subscription = helm.snapshots.subscribe { state ->
            if (state.quality.status == TelemetryStatus.LIVE) {
                receipts += JSONObject().put("receivedNs", System.nanoTime())
                    .put("sourceEpochMs", state.quality.timestampEpochMs)
                    .put("sourceAgeMs", System.currentTimeMillis() - state.quality.timestampEpochMs!!)
                    .put("simulationElapsedSeconds", state.simulationElapsedSeconds)
                    .put("speedKn", state.speed).put("rpm", state.portRpm)
                complete.countDown()
            }
        }
        val weatherSubscription = weather.snapshots.subscribe { if (it.status == TelemetryStatus.LIVE) weatherReceipts += System.nanoTime() }
        try { assertTrue(complete.await(12, TimeUnit.SECONDS), "101 fresh HELM samples must arrive without one-second polling") }
        finally { subscription.close(); weatherSubscription.close(); helm.close(); weather.close() }
        val delivered = receipts.toList()
        val intervals = delivered.zipWithNext { a, b -> (b.getLong("receivedNs") - a.getLong("receivedNs")) / 1e6 }
        val rate = 1000.0 / intervals.average()
        val sourceTimes = delivered.map { it.getLong("sourceEpochMs") }
        val result = JSONObject().put("timeScale", scale).put("count", delivered.size)
            .put("receivedRateHz", rate).put("intervalMs", stats(intervals))
            .put("requestDurationMs", stats(requests.map { it.getDouble("durationMs") }))
            .put("sourceAgeMs", stats(delivered.map { it.getDouble("sourceAgeMs") }))
            .put("distinctSourceTimestamps", sourceTimes.distinct().size)
            .put("weatherRequests", weatherReceipts.size).put("receipts", JSONArray(delivered))
            .put("requests", JSONArray(requests.toList()))
        println("Actual loopback cadence at $scale x: $rate Hz; ${weatherReceipts.size} weather samples")
        assertTrue(rate in 15.0..23.0, "Measured loopback delivery rate $rate Hz is outside the nominal 20 Hz budget")
        assertTrue(weatherReceipts.size in 2..4, "Weather must remain an independent 2 s feed")
        assertTrue(sourceTimes.zipWithNext().all { (a, b) -> b >= a }, "Source time cannot move backward")
        assertTrue(sourceTimes.distinct().size >= 90, "Frequent delivery cannot be faked by repeating a timestamp")
        return result
    }

    private fun stats(values: List<Double>): JSONObject {
        val sorted = values.sorted()
        return JSONObject().put("min", sorted.first()).put("p50", sorted[sorted.size / 2])
            .put("p95", sorted[((sorted.size - 1) * .95).toInt()]).put("max", sorted.last()).put("mean", values.average())
    }

}
