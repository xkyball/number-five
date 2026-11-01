package com.no5.home

import org.junit.Assert.*
import org.junit.Test

class GatewayCheckDiagnosticTest {
    @Test fun realErrnoRemainsDistinctFromAndroidSecurityDenial() {
        val socket = GatewayCheckDiagnostic.from(GatewayCheckStage.TCP_CONNECT, java.net.SocketException("socket failed: EACCES (Permission denied)"))
        assertEquals("EACCES", socket.errno); assertEquals("SOCKET", socket.category)
        val security = GatewayCheckDiagnostic.from(GatewayCheckStage.HTTPS_STATUS, SecurityException("EACCES secret"))
        assertNull(security.errno); assertEquals("SECURITY", security.category)
    }
    @Test fun wrappedNativeFailureIsBoundedAndPublicRecordCannotLeakSecrets() {
        val error = java.io.IOException("https://user:secret@192.0.2.1/ body=secret", java.net.ConnectException("ECONNREFUSED token=secret"))
        assertEquals("stage=HTTPS_STATUS category=OTHER errno=ECONNREFUSED", GatewayCheckDiagnostic.from(GatewayCheckStage.HTTPS_STATUS,error).publicRecord())
        val a = RuntimeException("secret"); val b = RuntimeException("secret", a); a.initCause(b)
        assertNull(GatewayCheckDiagnostic.from(GatewayCheckStage.TCP_CONNECT,a).errno)
    }
    @Test fun unknownAndEmbeddedTokensAreNotGuessed() {
        assertNull(GatewayCheckDiagnostic.from(GatewayCheckStage.TCP_CONNECT, java.net.SocketException("secretEACCESvalue Permission denied")).errno)
        assertNull(GatewayCheckDiagnostic.from(GatewayCheckStage.TCP_CONNECT, java.net.SocketException("ECONNRESET secret")).errno)
    }
}
