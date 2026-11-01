package com.no5.helm.network

import org.junit.Test
import kotlin.test.assertEquals

class AudioSoundWssDiagnosticsTest {
    @Test fun closeReasonsAreReducedToFixedCategories(){
        assertEquals("AUTHORITY_ENDED",AudioSoundWssDiagnostics.closeClass(1008,"sound stream authority ended"))
        assertEquals("STREAM_UNAVAILABLE",AudioSoundWssDiagnostics.closeClass(1008,"sound stream unavailable"))
        assertEquals("REQUEST_UNCONFIRMED",AudioSoundWssDiagnostics.closeClass(1008,"sound request unconfirmed; reconcile without replay"))
        assertEquals("POLICY_CLOSE_OTHER",AudioSoundWssDiagnostics.closeClass(1008,"secret or arbitrary peer text"))
        assertEquals("NORMAL",AudioSoundWssDiagnostics.closeClass(1000,"anything"))
        assertEquals("PEER_CLOSE",AudioSoundWssDiagnostics.closeClass(1011,"anything"))
    }

    @Test fun failuresAreClassifiedWithoutSerializingExceptionText(){
        assertEquals("TIMEOUT",AudioSoundWssDiagnostics.failureClass(java.net.SocketTimeoutException("private text")))
        assertEquals("DNS",AudioSoundWssDiagnostics.failureClass(java.net.UnknownHostException("private host")))
        assertEquals("IO",AudioSoundWssDiagnostics.failureClass(java.io.IOException("private text")))
        assertEquals("OTHER",AudioSoundWssDiagnostics.failureClass(IllegalStateException("private text")))
    }
}
