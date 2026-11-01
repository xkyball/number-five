package com.no5.home

import com.no5.helm.network.PiProtocolException
import org.junit.Assert.*
import org.junit.Test

class GatewayCheckFailureTest {
    @Test fun androidSocketDenialRequestsHomeRestartRatherThanAnotherAddress() {
        assertEquals(GatewayCheckFailure.NETWORK_PERMISSION,
            GatewayCheckFailure.from(java.net.SocketException("socket failed: EACCES (Permission denied)")))
        assertEquals(GatewayCheckFailure.NETWORK_PERMISSION, GatewayCheckFailure.from(SecurityException("denied")))
        assertTrue(GatewayCheckFailure.NETWORK_PERMISSION.message.contains("Home erneut starten"))
    }
    @Test fun certificateAndTransportFailuresNeedDifferentRepair() {
        assertEquals(GatewayCheckFailure.TLS, GatewayCheckFailure.from(javax.net.ssl.SSLPeerUnverifiedException("wrong identity")))
        assertEquals(GatewayCheckFailure.CONNECTION, GatewayCheckFailure.from(java.net.ConnectException("refused")))
        assertEquals(GatewayCheckFailure.CONNECTION, GatewayCheckFailure.from(java.net.SocketTimeoutException("timed out")))
    }
    @Test fun decoderAndUtf8RejectionsIdentifyTheServiceContract() {
        assertEquals(GatewayCheckFailure.DATA, GatewayCheckFailure.from(PiProtocolException("invalid payload")))
        assertEquals(GatewayCheckFailure.DATA, GatewayCheckFailure.from(java.nio.charset.MalformedInputException(1)))
    }
    @Test fun exceptionDetailsCannotAppearInAnyFailureMessage() {
        val secret = "secret-header-or-response-body"
        val failures = listOf(java.net.SocketException(secret), SecurityException(secret),
            javax.net.ssl.SSLHandshakeException(secret), PiProtocolException(secret))
        failures.forEach { assertFalse(GatewayCheckFailure.from(it).message.contains(secret)) }
        GatewayCheckFailure.entries.forEach { assertFalse(it.message.contains(secret)) }
    }
}
