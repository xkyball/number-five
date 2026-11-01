package com.no5.home

/** Public diagnostic codes only. Never log arbitrary exceptions, addresses, headers or bodies. */
internal enum class GatewayCheckStage { TCP_CONNECT, HTTPS_STATUS }
internal data class GatewayCheckDiagnostic(val stage: GatewayCheckStage, val category: String, val errno: String?) {
    fun publicRecord() = "stage=${stage.name} category=$category errno=${errno ?: "UNKNOWN"}"
    companion object {
        private val allowed = setOf("EACCES", "EPERM", "ENETUNREACH", "EHOSTUNREACH", "ECONNREFUSED", "ETIMEDOUT", "ENETDOWN", "EADDRNOTAVAIL")
        private val token = Regex("(?<![A-Za-z0-9_])(?:${allowed.joinToString("|")})(?![A-Za-z0-9_])")
        fun from(stage: GatewayCheckStage, error: Exception): GatewayCheckDiagnostic {
            // Identity tracking makes malformed cyclic cause chains bounded as well.
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = error
            var errno: String? = null
            repeat(8) {
                val value = cause ?: return@repeat
                if (!seen.add(value)) { cause = null; return@repeat }
                if (value is java.net.SocketException || value is android.system.ErrnoException)
                    errno = errno ?: token.find(value.message.orEmpty())?.value
                cause = value.cause
            }
            val category = when (error) {
                is SecurityException -> "SECURITY"
                is javax.net.ssl.SSLException, is java.security.cert.CertificateException -> "TLS"
                is java.net.SocketTimeoutException -> "TIMEOUT"
                is java.net.SocketException -> "SOCKET"
                is com.no5.helm.network.PiProtocolException, is java.nio.charset.CharacterCodingException, is IllegalArgumentException -> "DATA"
                else -> "OTHER"
            }
            return GatewayCheckDiagnostic(stage, category, errno)
        }
    }
}
