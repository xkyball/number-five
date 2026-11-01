package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener

/** Canonical No5 gateway audio V1. Provenance is data, never a different API. */
data class AudioV1Snapshot(val serverSessionId: String, val timestampEpochMs: Long, val state: AudioState)
data class AudioV1Ack(val sessionId: String, val requestId: String, val status: AudioCommandStatus, val message: String, val snapshot: AudioV1Snapshot)

object AudioV1Decoder {
    fun state(json: String): AudioV1Snapshot = decode { snapshot(parse(json)) }
    fun ack(json: String): AudioV1Ack = decode {
        val root = parse(json)
        header(root, "audio-ack")
        val state = snapshot(root.obj("state"))
        require(root.string("serverSessionId") == state.serverSessionId && root.integer("revision") == state.state.revision)
        require(root.string("source") == state.state.source.quality.source && root.bool("simulated") == state.state.source.quality.simulated)
        val status = AudioCommandStatus.valueOf(root.string("status"))
        require(status == AudioCommandStatus.VERIFIED || status == AudioCommandStatus.REJECTED)
        AudioV1Ack(root.string("sessionId"), root.string("requestId"), status, root.string("message"), state)
    }
    fun command(sessionId: String, requestId: String, serverSessionId: String, revision: Long, command: AudioCommand): String {
        val payload = when (command) {
            is AudioCommand.Fader -> JSONObject().put("type", "fader").put("channel", command.channel.name).put("valueDb", command.valueDb)
            is AudioCommand.Mute -> JSONObject().put("type", "mute").put("channel", command.channel.name).put("muted", command.muted)
            is AudioCommand.Preset -> JSONObject().put("type", "preset").put("id", command.id)
            is AudioCommand.Equalizer -> JSONObject().put("type", "equalizer").put("bankId", command.bankId)
                .put("configGeneration", command.configGeneration).put("gainsDb", JSONArray(command.gainsDb))
            is AudioCommand.Transport -> JSONObject().put("type", "transport").put("action", command.action.name).also {
                if (command.action == TransportAction.SEEK) it.put("positionMs", command.positionMs)
            }
        }
        return JSONObject().put("version", 1).put("kind", "audio-command").put("sessionId", sessionId)
            .put("requestId", requestId).put("serverSessionId", serverSessionId).put("expectedRevision", revision).put("command", payload).toString()
    }
    private fun snapshot(root: JSONObject): AudioV1Snapshot {
        header(root, "audio")
        val sourceId = root.string("source")
        val simulated = root.bool("simulated")
        val stamp = root.integer("timestampEpochMs").also { require(it > 0) }
        val revision = root.integer("revision").also { require(it >= 0) }
        val units = root.obj("units")
        require(units.keys().asSequence().toSet() == setOf("faders", "meters", "position"))
        require(units.string("faders") == "dB" && units.string("meters") == "dBFS" && units.string("position") == "ms")
        fun quality(q: JSONObject): TelemetryQuality {
            require(q.string("source") == sourceId && q.bool("simulated") == simulated)
            val status = TelemetryStatus.valueOf(q.string("status"))
            val timestamp = q.optionalInteger("timestampEpochMs")?.also { require(it > 0 && it <= stamp) }
            require(timestamp != null || status != TelemetryStatus.LIVE)
            return TelemetryQuality(status, timestamp, sourceId, demo = false, simulated = simulated)
        }
        val input = root.obj("audioSource")
        val media = root.obj("media")
        val duration = media.optionalInteger("durationMs")?.also { require(it in 1..86_400_000) }
        val position = media.optionalInteger("positionMs")?.also { require(it in 0..86_400_000 && (duration == null || it <= duration)) }
        val artists = media.getJSONArray("artists").let { array ->
            require(array.length() <= 10)
            List(array.length()) { array.get(it).let { a -> require(a is String && a.isNotBlank() && a.length <= 200 && a.none(Char::isISOControl)); a } }
        }
        val dsp = root.obj("dsp")
        val faders = dsp.obj("faders")
        require(root.has("limits"))
        val limits = if (root.isNull("limits")) null else root.obj("limits")
        val legacyChannels=AudioChannel.entries.filter { it!=AudioChannel.FRONT }
        require(faders.keys().asSequence().toSet() == legacyChannels.map { it.name }.toSet())
        val permittedLimitNames = legacyChannels.map { it.name }.toSet()
        require(limits == null || limits.keys().asSequence().toSet().let { names -> names.isNotEmpty() && names.all { it in permittedLimitNames } })
        var mappedLimits = limits?.let { values -> values.keys().asSequence().associate { name ->
            val channel = AudioChannel.valueOf(name)
            channel to values.obj(name).let { limit ->
                FaderLimit(limit.number("minDb", -150.0..24.0), limit.number("maxDb", -150.0..24.0), limit.number("stepDb", .01..24.0))
            }
        } }
        var mappedFaders = legacyChannels.associateWith { c -> faders.obj(c.name).let { fader ->
            require(fader.has("db"))
            val range = mappedLimits?.get(c)?.let { it.minDb..it.maxDb } ?: (-150.0..24.0)
            AudioFader(if (fader.isNull("db")) null else fader.number("db", range), fader.bool("muted"), AudioWriteState.VERIFIED)
        } }
        // Optional extension leaves the five legacy faders unchanged for older clients.
        if(dsp.has("zoneControls")) {
            val zones=dsp.obj("zoneControls")
            require(zones.keys().asSequence().toSet()==setOf("FRONT"))
            val front=zones.obj("FRONT"); val limit=front.obj("limit")
            val validated=FaderLimit(limit.number("minDb",-150.0..24.0),limit.number("maxDb",-150.0..24.0),limit.number("stepDb",.01..24.0))
            require(front.has("db"))
            mappedFaders=mappedFaders+(AudioChannel.FRONT to AudioFader(
                if(front.isNull("db")) null else front.number("db",validated.minDb..validated.maxDb),front.bool("muted"),AudioWriteState.VERIFIED))
            mappedLimits=(mappedLimits?:emptyMap())+(AudioChannel.FRONT to validated)
        }
        val faderControls = if (!dsp.has("faderControls")) emptyList() else {
            require(!dsp.isNull("faderControls"))
            val values = dsp.getJSONArray("faderControls")
            require(values.length() == legacyChannels.size)
            List(values.length()) { position -> values.getJSONObject(position).let { control ->
                require(control.keys().asSequence().toSet() == setOf("id", "label", "index", "db", "muted", "limit"))
                val channel = AudioChannel.valueOf(control.string("id"))
                require(channel in legacyChannels)
                val label = control.string("label").also { require(it.length <= 80) }
                val index = control.integer("index").also { require(it in 0..1024) }.toInt()
                val limit = if (control.isNull("limit")) null else control.obj("limit").let { declared ->
                    require(declared.keys().asSequence().toSet() == setOf("minDb", "maxDb", "stepDb"))
                    FaderLimit(declared.number("minDb", -150.0..24.0), declared.number("maxDb", -150.0..24.0), declared.number("stepDb", .01..24.0))
                }
                val legacy = mappedFaders.getValue(channel)
                val range = limit?.let { it.minDb..it.maxDb } ?: (-150.0..24.0)
                val db = if (control.isNull("db")) null else control.number("db", range)
                require(db == legacy.db && control.bool("muted") == legacy.muted)
                mappedLimits?.get(channel)?.let { require(limit == it) }
                AudioFaderControl(channel, index, label, db, legacy.muted, limit)
            }}.also { controls ->
                require(controls.map { it.gatewayId }.toSet() == legacyChannels.toSet())
                require(controls.map { it.physicalIndex }.distinct().size == controls.size)
                require(controls.single { it.gatewayId == AudioChannel.MASTER }.physicalIndex == 0)
            }.sortedBy { it.physicalIndex }
        }
        val presets = if (!dsp.has("presets")) emptyList() else {
            require(!dsp.isNull("presets"))
            val values = dsp.getJSONArray("presets")
            require(values.length() <= 64)
            List(values.length()) { position -> values.getJSONObject(position).let { preset ->
                require(preset.keys().asSequence().toSet() == setOf("id", "name", "active"))
                AudioPreset(preset.string("id"), preset.string("name"), preset.bool("active"))
            }}.also { catalog ->
                require(catalog.map { it.id }.distinct().size == catalog.size)
                require(catalog.count { it.active } <= 1)
            }
        }
        val caps = root.obj("capabilities")
        val equalizer = if (!dsp.has("equalizer") || dsp.isNull("equalizer")) null else runCatching { dsp.obj("equalizer").let { eq ->
            fun numbers(name: String): List<Double> {
                val values = eq.getJSONArray(name); require(values.length() == 5)
                return List(5) { index -> (values.get(index) as? Number)?.toDouble()?.also { require(it.isFinite()) } ?: error("Invalid EQ number") }
            }
            AudioEqualizer(eq.string("bankId"), eq.string("configGeneration"), numbers("frequenciesHz"), numbers("gainsDb"),
                eq.number("minDb", -6.0..6.0), eq.number("maxDb", -6.0..6.0), eq.number("stepDb", .01..12.0),
                eq.number("headroomDb", 0.0..Double.MAX_VALUE), eq.bool("writable"), eq.integer("observedAtEpochMs").also { require(it in 1..stamp) },
                eq.string("verification"))
        } }.getOrNull() // A broken optional bank must not disable existing media/fader controls.
        require(dsp.has("meters"))
        val meters = if (dsp.isNull("meters")) null else dsp.obj("meters")
        val meterFrame = meters?.let { meterData ->
        val meters = meterData
        val channels = meters.getJSONArray("channels")
        require(channels.length() == 5)
        // Simulated signal paths never establish physical routing, even if a buggy sender claims it.
        val routing = meters.bool("routingVerified") && !simulated
        val groups=if(meters.has("groups")) meters.obj("groups").let { objectValue ->
            objectValue.keys().asSequence().associate { key ->
                val ch=AudioChannel.valueOf(key)
                require(ch in setOf(AudioChannel.MASTER,AudioChannel.FRONT,AudioChannel.REAR,AudioChannel.SUB))
                val array=objectValue.getJSONArray(key)
                require(array.length() in 1..5)
                val indices=List(array.length()) { index -> val n=array.get(index); require(n is Int && n in 0..4); n as Int }
                require(indices.distinct().size==indices.size)
                ch to indices
            }
        } else emptyMap()
        AudioMeterFrame(List(5) { i -> channels.getJSONObject(i).let { m ->
            require(m.integer("index") == i.toLong())
            AudioMeter(i, m.number("peakDb", -160.0..0.0), m.number("rmsDb", -160.0..0.0), m.bool("clip"),
                m.number("peakHoldDb", -160.0..0.0), if (routing && m.has("label")) m.optionalString("label") else null)
        } }, quality(meters.obj("quality")), routing, if(simulated || routing) groups else emptyMap())
        }
        val state = AudioState(mode = OperatingMode.LIVE,
            source = AudioSource(input.bool("connected"), input.bool("active"), input.optionalString("name"), quality(input.obj("quality"))),
            media = AudioMedia(MediaState.valueOf(media.string("state")), media.optionalString("title"), artists, duration, position,
                media.optionalBool("shuffle"), media.optionalBool("repeat"), media.optionalString("trackId"),
                media.optionalString("artworkId")?.takeIf { it in AudioArtworkCatalog.ids }, media.optionalString("album")),
            dsp = AudioDsp(DspState.valueOf(dsp.string("state")), dsp.optionalString("preset"), mappedFaders, quality(dsp.obj("quality")),
                dsp.optionalBool("hardwareAvailable"), dsp.optionalInteger("clippedSamples")?.also { require(it >= 0) },
                faderControls = faderControls, presets = presets, equalizer = equalizer),
            capabilities = AudioCapabilities(caps.bool("playPause"), caps.bool("nextPrevious"), caps.bool("seek"), caps.bool("shuffleRepeat"),
                caps.bool("faderWrite"), caps.bool("mute"), caps.bool("presetWrite"), caps.opt("equalizer") == true && equalizer != null),
            authority = VolumeAuthority.valueOf(root.string("authority")), limits = mappedLimits, revision = revision)
        return AudioV1Snapshot(root.string("serverSessionId"), stamp, if (meterFrame == null) state else AudioModel.meters(state, meterFrame))
    }
    private fun parse(json: String): JSONObject {
        require(json.toByteArray(Charsets.UTF_8).size <= 65_536)
        StrictJsonSyntax.validate(json)
        val token = JSONTokener(json)
        val root = token.nextValue() as? JSONObject ?: error("Expected JSON object")
        require(token.nextClean() == '\u0000')
        return root
    }
    private fun header(root: JSONObject, kind: String) {
        require(root.integer("version") == 1L && root.string("kind") == kind)
        root.string("source")
        val kindValue = root.string("sourceKind")
        require(kindValue in setOf("REAL", "SIMULATED") && root.bool("simulated") == (kindValue == "SIMULATED"))
    }
    private fun JSONObject.obj(key: String) = get(key) as? JSONObject ?: error("$key must be an object")
    private fun JSONObject.bool(key: String) = get(key) as? Boolean ?: error("$key must be boolean")
    private fun JSONObject.string(key: String): String = (get(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) } ?: error("Invalid $key")
    private fun JSONObject.optionalString(key: String): String? { require(has(key)); return if (isNull(key)) null else string(key) }
    private fun JSONObject.optionalBool(key: String): Boolean? { require(has(key)); return if (isNull(key)) null else bool(key) }
    private fun JSONObject.optionalInteger(key: String): Long? { require(has(key)); return if (isNull(key)) null else integer(key) }
    private fun JSONObject.integer(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
    private fun JSONObject.number(key: String, range: ClosedFloatingPointRange<Double>): Double = ((get(key) as? Number)?.toDouble() ?: error("Invalid $key")).also { require(it.isFinite() && it in range) }
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (error: Exception) { throw PiProtocolException("Invalid No5 audio V1 response", error) }
}
