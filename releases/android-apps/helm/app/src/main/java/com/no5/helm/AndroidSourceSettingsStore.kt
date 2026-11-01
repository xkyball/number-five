package com.no5.helm

import android.content.Context
import android.content.SharedPreferences
import com.no5.domain.*
import com.no5.domain.SourceConfigValidator
import com.no5.domain.SourceMode
import com.no5.domain.WeatherProviderMode
import com.no5.domain.SourceSettingsStore

/** One atomic record keeps the gateway, weather approval and display units together. */
interface SourcePreferencesBackend {
    fun read(): String?
    fun write(value: String): Boolean
}

/** App-only preferences. This store never contacts or changes the Raspberry Pi. */
class AndroidSourceSettingsStore(
    private val backend: SourcePreferencesBackend,
    private val includeKnownLanEndpoint: Boolean = false,
) : SourceSettingsStore {
    constructor(context: Context) : this(SharedPreferencesSourceBackend(
        context.applicationContext.getSharedPreferences("no5_source_settings", Context.MODE_PRIVATE)
    ), includeKnownLanEndpoint = true)

    var warning: String? = null
        private set
    private var history: List<String> = emptyList()

    override fun load(): SourceConfig {
        warning = null; history = emptyList()
        val encoded = try {
            backend.read()
        } catch (_: RuntimeException) {
            return fallback()
        } ?: return approvedWeatherDefaults().also { history = withKnownLanEndpoint(emptyList()) }
        val fields = encoded.split('\n')
        val legacy = fields.size == 3 && fields[0] == "1"
        val previous = fields.size == 6 && fields[0] == "2"
        val v3 = fields.size == 9 && fields[0] == "3"
        val v4 = fields.firstOrNull() == VERSION && fields.size >= 10
        if (!legacy && !previous && !v3 && !v4) return fallback()
        if (SourceMode.entries.none { it.name == fields[1] }) return fallback()
        val provider = if (legacy) WeatherProviderMode.OPEN_METEO else WeatherProviderMode.entries.firstOrNull { it.name == fields[3] } ?: return fallback()
        val consent = if (legacy) true else fields[4].toBooleanStrictOrNull() ?: return fallback()
        val enabled = if (legacy) true else fields[5].toBooleanStrictOrNull() ?: return fallback()
        val units = if (legacy || previous) DisplayUnits() else DisplayUnits(
            SpeedUnit.entries.firstOrNull { it.name == fields[6] } ?: return fallback(),
            ConsumptionUnit.entries.firstOrNull { it.name == fields[7] } ?: return fallback(),
            DepthUnit.entries.firstOrNull { it.name == fields[8] } ?: return fallback(),
        )
        // Legacy local-demo selections migrate to the same external gateway path.
        val result = SourceConfigValidator.validate(SourceConfig(SourceMode.PI, fields[2], provider, consent, enabled, units))
        val config = result.config ?: return fallback()
        history = withKnownLanEndpoint(if (v4) EndpointHistory.restored(config.endpoint, persistedHistory(fields)) else EndpointHistory.migrated(config.endpoint))
        return config
    }

    override fun save(config: SourceConfig) {
        val checked = SourceConfigValidator.validate(config.copy(mode = SourceMode.PI))
        requireNotNull(checked.config) { checked.error ?: "Invalid app source configuration" }
        val normalized = checked.config!!
        val nextHistory = withKnownLanEndpoint(EndpointHistory.record(normalized.endpoint, history))
        val encoded = "$VERSION\n${normalized.mode.name}\n${normalized.endpoint}\n${normalized.weatherProvider.name}\n${normalized.allowWeatherLocationSharing}\n${normalized.connectionEnabled}\n${normalized.displayUnits.speed.name}\n${normalized.displayUnits.consumption.name}\n${normalized.displayUnits.depth.name}\n${nextHistory.size}\n${nextHistory.joinToString("\n")}".trimEnd('\n')
        check(backend.write(encoded)) { "Source settings could not be saved" }
        history = nextHistory
        warning = null
    }

    override fun endpointHistory(): List<String> = history

    private fun withKnownLanEndpoint(existing: List<String>): List<String> =
        if (includeKnownLanEndpoint) KnownLanEndpointHistory.include(existing) else existing

    private fun approvedWeatherDefaults() = SourceConfig(mode = SourceMode.PI, weatherProvider = WeatherProviderMode.OPEN_METEO, allowWeatherLocationSharing = true)

    private fun persistedHistory(fields: List<String>): List<String> {
        val count = fields.getOrNull(9)?.toIntOrNull() ?: return emptyList()
        if (count !in 0..EndpointHistory.MAX_ENTRIES || fields.size != 10 + count) return emptyList()
        return fields.drop(10)
    }

    private fun fallback(): SourceConfig {
        // Do not echo damaged preference contents; they could contain pasted credentials.
        warning = "Saved source settings could not be read safely. Connections are disabled. Review and explicitly apply the app connection settings."
        // Corruption must not activate connections or local replacement data.
        return SourceConfig(mode = SourceMode.PI, connectionEnabled = false)
    }

    private companion object { const val VERSION = "4" }
}

private class SharedPreferencesSourceBackend(private val preferences: SharedPreferences) : SourcePreferencesBackend {
    override fun read(): String? = preferences.getString(KEY, null)

    override fun write(value: String): Boolean {
        val priorValues = preferences.all
        val previous = sourceRecordForRollback(priorValues[KEY], priorValues.containsKey(KEY))
        if (preferences.edit().putString(KEY, value).commit()) return true
        // commit() can update the process cache even if the disk write fails. Restore the prior
        // record as well, so an Activity recreation cannot activate a failed source change.
        val rollback = preferences.edit()
        if (previous == null) rollback.remove(KEY) else rollback.putString(KEY, previous)
        rollback.commit()
        return false
    }

    private companion object { const val KEY = "configuration" }
}

/** Preserve the distinction between fresh installation and an unreadable existing record. */
internal fun sourceRecordForRollback(previous: Any?, wasPresent: Boolean): String? = when {
    previous is String -> previous
    wasPresent -> "invalid-source-settings"
    else -> null
}
