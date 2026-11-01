package com.no5.domain

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

enum class WeatherProviderMode { SIMULATED, OPEN_METEO, DISABLED }

/** The editable default is an example local address, not a discovered backend. */
data class SourceConfig(
    val mode: SourceMode = SourceMode.DEMO,
    val endpoint: String = "http://raspberrypi.local:8080",
    val weatherProvider: WeatherProviderMode = WeatherProviderMode.SIMULATED,
    val allowWeatherLocationSharing: Boolean = false,
    /** False only for unsafe restored records; explicit Apply re-enables the connection. */
    val connectionEnabled: Boolean = true,
    val displayUnits: DisplayUnits = DisplayUnits(),
)

/** Implementations persist a validated config atomically; no credentials are stored here. */
interface SourceSettingsStore {
    fun load(): SourceConfig
    fun save(config: SourceConfig)
    /** Confirmed canonical endpoint addresses only; secrets never belong in this history. */
    fun endpointHistory(): List<String> = emptyList()
}

/**
 * Pure, bounded recency policy shared by persistence and the Settings screen.
 * Every candidate is revalidated before it can be displayed, so a damaged persisted history
 * cannot surface credentials or bypass the normal endpoint validator.
 */
object EndpointHistory {
    const val MAX_ENTRIES = 5

    fun record(endpoint: String, existing: List<String>): List<String> = prioritized(endpoint, existing)

    fun migrated(endpoint: String): List<String> = prioritized(endpoint, emptyList())

    fun restored(activeEndpoint: String, persisted: List<String>): List<String> =
        prioritized(activeEndpoint, persisted)

    private fun prioritized(activeEndpoint: String, candidates: List<String>): List<String> {
        val active = canonical(activeEndpoint) ?: return emptyList()
        return buildList {
            add(active)
            candidates.forEach { candidate ->
                canonical(candidate)?.takeIf { it !in this }?.let(::add)
            }
        }.take(MAX_ENTRIES)
    }

    private fun canonical(candidate: String): String? =
        SourceConfigValidator.validate(SourceConfig(mode = SourceMode.PI, endpoint = candidate)).config?.endpoint
}

data class SourceConfigValidation(val config: SourceConfig? = null, val error: String? = null) {
    val isValid: Boolean get() = config != null
}

/** Pure syntax validation: no DNS resolution, network access, or connectivity claim. */
object SourceConfigValidator {
    fun validate(config: SourceConfig): SourceConfigValidation = try {
        SourceConfigValidation(config = config.copy(endpoint = normalizeEndpoint(config.endpoint)))
    } catch (failure: IllegalArgumentException) {
        SourceConfigValidation(error = failure.message ?: "Invalid Pi address")
    } catch (_: URISyntaxException) {
        SourceConfigValidation(error = "Enter a valid HTTP or HTTPS host or URL")
    }

    private fun normalizeEndpoint(input: String): String {
        require(input.none { it.isISOControl() }) { "The Pi address cannot contain control characters" }
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Enter the Pi host or URL" }
        require(trimmed.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) { "The Pi address cannot contain spaces or control characters" }
        val uri = URI(if (trimmed.contains("://")) trimmed else "http://$trimmed")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "Use HTTP or HTTPS for the Pi address" }
        require(uri.rawUserInfo == null && uri.rawAuthority?.contains('@') != true) { "Credentials are not allowed in the Pi address" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "The Pi address cannot include a query or fragment" }
        val authority = uri.rawAuthority ?: throw IllegalArgumentException("Enter a valid Pi host")
        var host = uri.host?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("Enter a valid hostname or IP address")
        require('%' !in host) { "Scoped IP addresses are not supported" }
        val isIpv6 = host.startsWith('[') && host.endsWith(']')
        if (!isIpv6) {
            host = host.removeSuffix(".")
            require(host.length in 1..253) { "Enter a valid hostname or IP address" }
            if (host.all { it.isDigit() || it == '.' }) {
                val octets = host.split('.')
                require(octets.size == 4 && octets.all { it.isNotEmpty() && it.length <= 3 && (it.toIntOrNull() ?: -1) in 0..255 }) { "Enter a valid IPv4 address" }
                host = octets.joinToString(".") { it.toInt().toString() }
            } else {
                require(host.split('.').all { label -> label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } }) { "Enter a valid hostname" }
            }
        }
        val port = uri.port
        val hasPort = if (isIpv6) authority.substringAfter(']').startsWith(':') else ':' in authority
        require(!hasPort || port in 1..65_535) { "The port must be between 1 and 65535" }
        val effectivePort = port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
        val path = uri.normalize().rawPath.orEmpty().trimEnd('/')
        require(!Regex("%0[0-9a-f]|%1[0-9a-f]|%7f", RegexOption.IGNORE_CASE).containsMatchIn(path)) { "The Pi address cannot contain encoded control characters" }
        return "$scheme://$host${effectivePort?.let { ":$it" }.orEmpty()}$path"
    }
}
