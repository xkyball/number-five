package com.no5.helm

import android.content.Context
import android.content.SharedPreferences
import com.no5.domain.AudioChannel
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * App-local presentation preferences for physical Camilla faders.  The gateway
 * identifier remains the key: neither an alias nor visibility can change the
 * physical command target or the gateway-provided limits.
 */
data class AudioFaderPresentation(
    val alias: String? = null,
    val visible: Boolean = true,
) {
    init {
        require(alias == null || (alias.isNotBlank() && alias.length <= MAX_ALIAS_LENGTH && alias.none(Char::isISOControl)))
    }

    companion object { const val MAX_ALIAS_LENGTH = 80 }
}

data class AudioPresentationSettings(
    val faders: Map<AudioChannel, AudioFaderPresentation> = emptyMap(),
) {
    fun presentation(channel: AudioChannel): AudioFaderPresentation =
        faders[channel] ?: AudioFaderPresentation()

    fun alias(channel: AudioChannel): String? = presentation(channel).alias
    fun visible(channel: AudioChannel): Boolean = presentation(channel).visible

    fun update(channel: AudioChannel, alias: String?, visible: Boolean): AudioPresentationSettings {
        val normalizedAlias = alias?.trim()?.takeIf { it.isNotEmpty() }
        val next = AudioFaderPresentation(normalizedAlias, visible)
        val changed = faders.toMutableMap()
        if (next == AudioFaderPresentation()) changed.remove(channel) else changed[channel] = next
        return copy(faders = changed)
    }

    companion object { val DEFAULT = AudioPresentationSettings() }
}

/** Testable boundary for the app-only presentation record. */
interface AudioPresentationBackend {
    fun read(): String?
    fun write(value: String): Boolean
}

/**
 * Durable local aliases/visibility.  Bad or obsolete local records fall back
 * to the safe defaults; no gateway state, key or DSP value is ever stored here.
 */
class AndroidAudioPresentationStore(private val backend: AudioPresentationBackend) {
    constructor(context: Context) : this(SharedPreferencesAudioPresentationBackend(
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
    ))

    fun load(): AudioPresentationSettings {
        val record = try { backend.read() } catch (_: RuntimeException) { null } ?: return AudioPresentationSettings.DEFAULT
        return decode(record) ?: AudioPresentationSettings.DEFAULT
    }

    fun save(settings: AudioPresentationSettings) {
        check(backend.write(encode(settings))) { "Audio presentation settings could not be saved" }
    }

    private fun encode(settings: AudioPresentationSettings): String {
        val rows = settings.faders.toSortedMap(compareBy { it.name }).map { (channel, presentation) ->
            val alias = presentation.alias?.let { encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8)) }.orEmpty()
            "${channel.name}\t${presentation.visible}\t$alias"
        }
        return (listOf(VERSION, rows.size.toString()) + rows).joinToString("\n")
    }

    private fun decode(record: String): AudioPresentationSettings? {
        val lines = record.split('\n')
        if (lines.size < 2 || lines[0] != VERSION) return null
        val count = lines[1].toIntOrNull() ?: return null
        if (count !in 0..AudioChannel.entries.size || lines.size != count + 2) return null
        val values = linkedMapOf<AudioChannel, AudioFaderPresentation>()
        lines.drop(2).forEach { row ->
            val columns = row.split('\t', limit = 3)
            if (columns.size != 3) return null
            val channel = AudioChannel.entries.firstOrNull { it.name == columns[0] } ?: return null
            if (values.containsKey(channel)) return null
            val visible = columns[1].toBooleanStrictOrNull() ?: return null
            val alias = if (columns[2].isEmpty()) null else try {
                String(decoder.decode(columns[2]), StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) { return null }
            val presentation = try { AudioFaderPresentation(alias, visible) } catch (_: IllegalArgumentException) { return null }
            if (presentation != AudioFaderPresentation()) values[channel] = presentation
        }
        return AudioPresentationSettings(values)
    }

    private companion object {
        const val VERSION = "1"
        const val PREFERENCES = "no5_audio_presentation"
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val decoder = Base64.getUrlDecoder()
    }
}

private class SharedPreferencesAudioPresentationBackend(private val preferences: SharedPreferences) : AudioPresentationBackend {
    override fun read(): String? = preferences.getString(KEY, null)
    override fun write(value: String): Boolean = preferences.edit().putString(KEY, value).commit()
    private companion object { const val KEY = "presentation" }
}
