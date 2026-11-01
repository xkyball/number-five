package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkSonarSourceSelectionTest {
    private val identity = SourceIdentity("helm-test", SourceMode.PI, "SONAR")
    private fun state(generation: Long = 1) = SonarV1State(SonarV1Origin("SONARD", false, "server", generation),
        0, 1_000_000, true, "LIVE", false, false, true, Calibration(), 2.5, sourceSelection = true,
        displaySelection = SonarDisplaySelection(SonarPingSource.OWN, 1))
    private class Http : PiHttpTransport {
        var puts = 0
        var fail = false
        var resultStatus = "APPLIED"
        var request: JSONObject? = null
        var onPut: (() -> Unit)? = null
        override fun put(path: String, body: String): String {
            assertEquals("/v1/sonar/source", path); puts++; request = JSONObject(body); onPut?.invoke()
            if (fail) throw java.io.IOException("lost reply")
            return result("ACCEPTED")
        }
        private fun result(status: String) = JSONObject().put("version", 1).put("kind", "sonar-command-result")
            .put("clientId", "helm-test").put("requestId", request!!.getLong("requestId"))
            .put("serverSessionId", "server").put("generation", request!!.getLong("generation"))
            .put("commandId", 7).put("status", status).put("confirmedSource", request!!.getString("source")).toString()
        override fun get(path: String): String = if (path == "/v1/sonar/source") JSONObject().put("version", 1)
            .put("kind", "sonar-source").put("serverSessionId", "server").put("generation", 1)
            .put("displaySelection", JSONObject().put("version", 1).put("source", "native").put("generation", 2)).toString()
        else result(resultStatus)
        override fun close() = Unit
    }
    @Test fun newestPendingSelectionWinsAndAcceptanceDoesNotChangeTheObservedSource() {
        val http = Http(); val select = NetworkSonarSourceSelection(identity, http) { 1_000_000 }; select.observe(state())
        val old = select.submit(SonarSourceSelectionRequest(identity.sessionId, 1, SonarPingSource.ALL))
        val latest = select.submit(SonarSourceSelectionRequest(identity.sessionId, 2, SonarPingSource.NATIVE))
        assertEquals(0, http.puts); assertEquals(SonarFilterCommandStatus.SUPERSEDED, select.result(old.commandId!!)!!.status)
        select.pump(); select.tick()
        assertEquals(SonarFilterCommandStatus.RUNNING, select.result(latest.commandId!!)!!.status)
        assertEquals(SonarPingSource.OWN, select.state.value.selection!!.source)
        select.pump(); select.tick(); assertEquals(SonarFilterCommandStatus.APPLIED, select.result(latest.commandId!!)!!.status)
        // Source/generation is confirmed by state readback, not invented by a command acknowledgment.
        select.pump(); select.tick(); assertEquals(SonarPingSource.NATIVE, select.state.value.selection!!.source)
        assertEquals(2L, select.state.value.selection!!.generation); assertEquals(1, http.puts); select.close()
    }
    @Test fun ambiguousWriteIsNeverReplayedAndRetiredResponseCannotUpdateANewEpoch() {
        val http = Http(); val select = NetworkSonarSourceSelection(identity, http) { 1_000_000 }; select.observe(state())
        http.fail = true
        val request = select.submit(SonarSourceSelectionRequest(identity.sessionId, 1, SonarPingSource.ALL))
        select.pump(); select.pump(); assertEquals(1, http.puts)
        assertEquals(SonarFilterCommandStatus.UNKNOWN, select.result(request.commandId!!)!!.status)
        http.fail = false
        val second = select.submit(SonarSourceSelectionRequest(identity.sessionId, 2, SonarPingSource.NATIVE))
        http.onPut = { select.observe(state(2).copy(origin = SonarV1Origin("SONARD", false, "other-server", 2))) }; select.pump(); select.tick()
        assertEquals(SonarFilterCommandStatus.UNKNOWN, select.result(second.commandId!!)!!.status)
        assertEquals(SonarPingSource.OWN, select.state.value.selection!!.source); select.close()
    }
    @Test fun theOwnGenerationTransitionDoesNotLoseTheAcceptedCommandReceipt() {
        val http = Http(); val select = NetworkSonarSourceSelection(identity, http) { 1_000_000 }; select.observe(state())
        val request = select.submit(SonarSourceSelectionRequest(identity.sessionId, 1, SonarPingSource.NATIVE))
        http.onPut = { select.observe(state(2).copy(displaySelection = SonarDisplaySelection(SonarPingSource.NATIVE, 2))) }
        select.pump(); select.tick()
        assertEquals(SonarPingSource.NATIVE, select.state.value.selection!!.source)
        assertEquals(SonarFilterCommandStatus.RUNNING, select.result(request.commandId!!)!!.status)
        select.pump(); select.tick()
        assertEquals(SonarFilterCommandStatus.APPLIED, select.result(request.commandId!!)!!.status)
        assertEquals(SonarPingSource.NATIVE, select.state.value.selection!!.source)
        assertEquals(2L, select.state.value.selection!!.generation)
        select.pump(); assertEquals(1, http.puts); select.close()
    }
    @Test fun aRemoteSupersededResultRemainsSupersededAndIsNeverRepeated() {
        val http = Http(); val select = NetworkSonarSourceSelection(identity, http) { 1_000_000 }; select.observe(state())
        val request = select.submit(SonarSourceSelectionRequest(identity.sessionId, 1, SonarPingSource.ALL))
        select.pump(); http.resultStatus = "SUPERSEDED"; select.pump()
        assertEquals(SonarFilterCommandStatus.SUPERSEDED, select.result(request.commandId!!)!!.status)
        select.pump(); assertEquals(1, http.puts); select.close()
    }
    @Test fun sourceSelectionRequiresCapabilityAndMatchingClientSession() {
        val select = NetworkSonarSourceSelection(identity, Http()) { 1_000_000 }
        assertEquals("UNSUPPORTED", select.submit(SonarSourceSelectionRequest(identity.sessionId, 1, SonarPingSource.ALL)).reason)
        select.observe(state())
        assertEquals("WRONG_SESSION", select.submit(SonarSourceSelectionRequest("other", 2, SonarPingSource.ALL)).reason)
        select.close()
        assertEquals("CLOSED", select.submit(SonarSourceSelectionRequest(identity.sessionId, 3, SonarPingSource.ALL)).reason)
    }
}
