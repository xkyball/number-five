package com.no5.gateway

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Address selection only. Never contains a key, CA, URL path or TLS override. */
data class GatewayOrigin(val host: String, val port: Int) {
    val url: String get() = "https://$host${if (port == 443) "" else ":$port"}"
    val mdnsInstance: String? get() = host.takeIf { it.endsWith(".local") }?.removeSuffix(".local")
    companion object {
        fun parse(value: String): GatewayOrigin? = runCatching {
            require(value.length <= 300 && value.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
            val uri = URI(value)
            require(uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            val host = requireNotNull(uri.host).trimEnd('.').lowercase(Locale.ROOT)
            require(host.length in 1..253 && host.any { it in 'a'..'z' })
            require(host.split('.').all { it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } })
            val port = if (uri.port == -1) 443 else uri.port
            require(port in 1..65535 && !uri.rawAuthority.endsWith(':'))
            GatewayOrigin(host, port)
        }.getOrNull()
    }
}

/** Numeric-only input: validation cannot silently perform another DNS lookup. */
fun gatewayAddress(value: String): InetAddress? = runCatching {
    require(value.isNotEmpty() && value.length <= 45 && '%' !in value)
    val address = if (':' in value) {
        require(value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' })
        InetAddress.getByName(value)
    } else {
        val octets = value.split('.')
        require(octets.size == 4 && octets.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 })
        InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
    }
    require(!address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress && !address.isLinkLocalAddress)
    if (address is java.net.Inet4Address) require((address.address[0].toInt() and 255) in 1..223)
    address
}.getOrNull()

enum class GatewayAddressSource { MDNS, MANUAL }
enum class GatewayAddressState { AVAILABLE, NEEDS_INPUT }

data class GatewayConfiguration(
    val origin: GatewayOrigin,
    val address: String,
    val source: GatewayAddressSource,
    val state: GatewayAddressState = GatewayAddressState.AVAILABLE,
    val checkedAt: Long,
    val consecutiveFailures: Int = 0,
    val checkRequested: Boolean = false,
) {
    val routingKey: String get() = "${origin.url}|$address|${state.name}"
    val usable: Boolean get() = state == GatewayAddressState.AVAILABLE && gatewayAddress(address) != null

    fun checked(reachable: Boolean, online: Boolean, now: Long): GatewayConfiguration {
        if (!online) return this // A disconnected device needs its network, not a new gateway IP.
        val failures = if (reachable) 0 else (consecutiveFailures + 1).coerceAtMost(2)
        return copy(state = if (failures >= 2) GatewayAddressState.NEEDS_INPUT else GatewayAddressState.AVAILABLE,
            checkedAt = now, consecutiveFailures = failures, checkRequested = false)
    }
}

object GatewayConfigurationCodec {
    fun encode(record: GatewayConfiguration): String = listOf("1", record.origin.url, record.address,
        record.source.name, record.state.name, record.checkedAt.toString(), record.consecutiveFailures.toString(),
        record.checkRequested.toString()).joinToString("\n")

    fun decode(value: String?): GatewayConfiguration? = runCatching {
        require(value != null && value.length <= 700)
        val f = value.split('\n'); require(f.size == 8 && f[0] == "1")
        val origin = requireNotNull(GatewayOrigin.parse(f[1]))
        val address = requireNotNull(gatewayAddress(f[2])).hostAddress!!
        val checkedAt = f[5].toLong(); require(checkedAt >= 0)
        val failures = f[6].toInt(); require(failures in 0..2)
        GatewayConfiguration(origin, address, GatewayAddressSource.valueOf(f[3]), GatewayAddressState.valueOf(f[4]),
            checkedAt, failures, f[7].toBooleanStrict())
    }.getOrNull()
}
