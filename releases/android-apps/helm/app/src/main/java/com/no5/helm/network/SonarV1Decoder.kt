package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** One canonical contract for REAL and SIMULATED producers. Provenance never bypasses validation. */
data class SonarV1Origin(val source: String, val simulated: Boolean, val serverSessionId: String, val generation: Long)
data class SonarV1State(
    val origin: SonarV1Origin, val revision: Long, val publishedAtEpochMs: Long,
    val running: Boolean, val status: String, val canStart: Boolean, val canStop: Boolean,
    val bottomDetection: Boolean, val calibration: Calibration, val cadenceHz: Double,
    val filterControl: Boolean = false, val confirmedTracks: Boolean = false,
    val sourceSelection: Boolean = false, val diagnostics: Boolean = false,
    val displaySelection: SonarDisplaySelection? = null, val amplitude: SonarAmplitude? = null,
    val observationReason: SonarObservationReason? = null,
)
data class SonarV1Ping(val origin: SonarV1Origin, val publishedAtEpochMs: Long,
    val frame: SonarFrame, val calibration: Calibration, val bottom: SonarBottom,
    val geometry: SonarGeometry? = null, val presentation: SonarPresentation? = null, val hostPackageVersion: String? = null,
    val displaySelection: SonarDisplaySelection? = null, val amplitude: SonarAmplitude? = null, val pingSource: SonarPingSource? = null)
data class SonarV1Ack(val clientId: String, val requestId: Long, val verified: Boolean,
    val reason: String?, val state: SonarV1State)

object SonarV1Decoder {
    const val SUBPROTOCOL = "no5.sonar.v1"
    const val MAX_BINARY_BYTES = 4 + 4096 + SONAR_BINS * 2
    const val MAX_STATE_BYTES = 16_384
    // Acquisition and publication can originate on different hosts. Keep their
    // real timestamps and use the same bounded clock skew as the source's
    // freshness validation, rather than requiring synchronized wall clocks.
    internal const val MAX_CLOCK_SKEW_MS = 5_000L
    fun state(json: String): SonarV1State = decode { state(parse(json, MAX_STATE_BYTES)) }
    fun ping(bytes: ByteArray): SonarV1Ping = decode {
        require(bytes.size in (5 + SONAR_BINS * 2)..MAX_BINARY_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = buffer.int
        require(headerSize in 1..4096 && bytes.size == 4 + headerSize + SONAR_BINS * 2)
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 4, headerSize)).toString()
        val root = parse(json, 4096)
        val origin = origin(root, "sonar.ping")
        format(root)
        val observed = root.integer("observedAtEpochMs").also { require(it > 0) }
        val published = root.integer("publishedAtEpochMs").also {
            require(it > 0 && (it >= observed || observed - it <= MAX_CLOCK_SKEW_MS))
        }
        val id = root.integer("pingId").also { require(it >= 0) }
        val bottom = root.obj("bottom")
        val geometry = if (root.has("geometry")) geometry(root.obj("geometry")) else null
        var presentation = root.optJSONObject("presentation")?.let { presentation(it, id) }
        require(presentation == null || geometry != null)
        val sourceName = (bottom.opt("source") as? String)?.uppercase(Locale.ROOT)
        val source = sourceName?.let { name -> SonarBottomSource.entries.firstOrNull { it.name == name } }
        val unsupportedSelection = sourceName != null && source == null
        var selected = SonarBottom(bottom.optionalNumber("bin", 0.0..1023.0),
            bottom.optionalNumber("confidence", 0.0..1.0), bottom.optionalNumber("strength", 0.0..1.0),
            source, if (!bottom.has("trackId") || bottom.isNull("trackId")) null else bottom.integer("trackId"),
            (bottom.opt("classification") as? String)?.takeIf { it in CLASSIFICATIONS })
        // Unsupported optional analysis cannot confirm a depth or preserve a prior LOCKED claim.
        // The raw display profile, mandatory ping timing and verified geometry remain usable.
        if (unsupportedSelection || geometry != null && (presentation == null || selected.bin != null && source == null)) {
            selected = SonarBottom()
            presentation = null
        }
        if (geometry != null) {
            if (selected.bin != null) {
                require(geometry.metersPerBin != null && presentation?.quality == SonarAnalysisQuality.LOCKED)
                require(source != null && selected.validated && selected.strength != null)
                require((source == SonarBottomSource.M1_RAW) == (selected.trackId == null))
            } else require(selected.confidence == null && selected.strength == null && selected.source == null)
        } else require(source == null)
        SonarV1Ping(origin, published,
            SonarFrame.fromLittleEndian(bytes.copyOfRange(4 + headerSize, bytes.size), id, observed),
            calibration(root.obj("calibration")), selected, geometry, presentation,
            if (root.has("hostPackageVersion")) root.optionalString("hostPackageVersion") else null,
            displaySelection(root), amplitude(root), optionalPingSource(root, "pingSource"))
    }
    fun ack(json: String): SonarV1Ack = decode {
        val root = parse(json, 65_536)
        require(root.integer("version") == 1L && root.string("kind") == "sonar.ack")
        val state = state(root.obj("state"))
        require(root.string("serverSessionId") == state.origin.serverSessionId)
        require(root.integer("generation") == state.origin.generation)
        val outcome = root.string("outcome").also { require(it == "VERIFIED" || it == "REJECTED") }
        SonarV1Ack(root.string("clientId"), root.integer("requestId").also { require(it >= 0) },
            outcome == "VERIFIED", root.optionalString("reason"), state)
    }
    fun command(clientId: String, requestId: Long, state: SonarV1State, start: Boolean): String =
        JSONObject().put("version", 1).put("serverSessionId", state.origin.serverSessionId)
            .put("generation", state.origin.generation).put("expectedRevision", state.revision)
            .put("clientId", clientId).put("requestId", requestId).put("command", if (start) "start" else "stop").toString()

    private fun state(root: JSONObject): SonarV1State {
        val origin = origin(root, "sonar.state")
        format(root)
        val running = root.bool("running")
        val status = root.string("status").also { require(it in setOf("LIVE", "IDLE", "STALE", "UNAVAILABLE")) }
        require(status != "LIVE" || running)
        require(status != "IDLE" && status != "UNAVAILABLE" || !running)
        val caps = root.obj("capabilities")
        return SonarV1State(origin, root.integer("revision").also { require(it >= 0) },
            root.integer("publishedAtEpochMs").also { require(it > 0) }, running, status,
            caps.bool("canStart"), caps.bool("canStop"), caps.bool("bottomDetection"),
            calibration(root.obj("calibration")), root.number("cadenceHz", 0.1..20.0),
            caps.has("filterControl") && caps.bool("filterControl"), caps.has("confirmedTracks") && caps.bool("confirmedTracks"),
            caps.opt("sourceSelection") == true, caps.opt("diagnostics") == true,
            displaySelection(root), amplitude(root), observationReason(root))
    }
    /** Unknown or malformed optional reasons do not reject a compatible sonar state/stream. */
    internal fun observationReason(root: JSONObject): SonarObservationReason? = runCatching {
        val value=root.optJSONObject("observationReason") ?: return null
        if(value.integer("version")!=1L || value.opt("code")!="ORIGINAL_REFERENCE_LOST") return null
        val detail=value.optionalString("detail")
        val observed=if(value.isNull("observedAtEpochMs")) null else value.integer("observedAtEpochMs").also{require(it>0)}
        require(value.has("observedAtEpochMs"))
        SonarObservationReason("ORIGINAL_REFERENCE_LOST",detail,observed,
            value.integer("statusObservedAtEpochMs"),value.bool("latched"),value.bool("statusFresh"))
    }.getOrNull()

    /** Additive metadata never makes a legacy compatible frame unavailable. Unknown versions stay unknown. */
    internal fun displaySelection(root: JSONObject): SonarDisplaySelection? {
        val value = root.optJSONObject("displaySelection") ?: return null
        if (value.opt("version") != 1) return null
        val source = optionalPingSource(value, "source")
        val generation = value.integer("generation").also { require(it >= 0) }
        val hz = if (!value.has("publishedHz")) null else value.optionalNumber("publishedHz", 0.0..10_000.0)
        val observed = if (!value.has("observedAtEpochMs") || value.isNull("observedAtEpochMs")) null else value.integer("observedAtEpochMs").also { require(it > 0) }
        val ping = if (!value.has("lastPublishedPingId") || value.isNull("lastPublishedPingId")) null else value.integer("lastPublishedPingId").also { require(it >= 0) }
        val selectionGeneration = if (!value.has("selectionGeneration") || value.isNull("selectionGeneration")) null
            else value.integer("selectionGeneration").also { require(it >= 0) }
        return SonarDisplaySelection(source, generation, hz, observed, ping, selectionGeneration)
    }
    internal fun amplitude(root: JSONObject): SonarAmplitude? {
        val value = root.optJSONObject("amplitude") ?: return if (root.has("amplitude")) SonarAmplitude(null) else null
        val fullScale = value.opt("fullScaleCode").takeIf { it is Int || it is Long }?.let { (it as Number).toLong() }?.takeIf { it in 1..65535 }?.toInt()
        val bits = value.opt("significantBits").takeIf { it is Int || it is Long }?.let { (it as Number).toLong() }?.takeIf { it in 1..16 }?.toInt()
        val producer = (value.opt("normalizationSource") as? String)?.takeIf { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) }
        val transform = (value.opt("displayTransform") as? String)?.takeIf { it.length <= 100 && it.none(Char::isISOControl) }
        val noiseRevision = value.opt("noiseRevision").takeIf { it is Int || it is Long }?.let { (it as Number).toLong() }?.takeIf { it >= 0 }
        val noiseActive = value.opt("noiseActive") as? Boolean
        val contextPresent = listOf("displayTransform", "noiseRevision", "noiseActive").any { value.has(it) }
        val contextComplete = transform != null && noiseRevision != null && noiseActive != null
        val verified = value.opt("version") == 1 && value.opt("encoding") == "UINT16_LE" &&
            value.opt("unit") == "ADC_CODE" && value.opt("rawSamplesModified") == false &&
            bits == 12 && fullScale == 4095 && producer == "HELLO_12bit_checked_host_contract" &&
            (!contextPresent || contextComplete)
        return SonarAmplitude(fullScale, bits, verified, producer, transform, noiseRevision, noiseActive)
    }
    internal fun optionalPingSource(root: JSONObject, name: String): SonarPingSource? =
        (root.opt(name) as? String)?.let { value -> SonarPingSource.entries.firstOrNull { it.name.equals(value, true) } }

    private fun origin(root: JSONObject, kind: String): SonarV1Origin {
        require(root.integer("version") == 1L && root.string("kind") == kind)
        val sourceKind = root.string("sourceKind")
        require(sourceKind in setOf("REAL", "SIMULATED"))
        val simulated = root.bool("simulated")
        require(simulated == (sourceKind == "SIMULATED"))
        return SonarV1Origin(root.string("source"), simulated, root.string("serverSessionId"),
            root.integer("generation").also { require(it >= 0) })
    }
    private fun format(root: JSONObject) {
        val format = root.obj("format")
        require(format.integer("bins") == SONAR_BINS.toLong() && format.string("sampleType") == "UINT16" && format.string("byteOrder") == "LITTLE_ENDIAN")
    }
    private fun calibration(root: JSONObject): Calibration {
        val samplePeriod = root.optionalNumber("samplePeriodUs", Double.MIN_VALUE..1000.0)
        val valid = root.bool("valid")
        require(!valid || samplePeriod != null)
        return Calibration(valid, samplePeriod, root.number("soundSpeedMps", 1300.0..1800.0),
            root.optionalNumber("transducerOffsetM", -50.0..50.0), root.optionalNumber("keelOffsetM", -50.0..50.0),
            DepthReference.valueOf(root.string("depthReference")), root.integer("revision").also { require(it >= 0) })
    }
    private fun geometry(root: JSONObject): SonarGeometry {
        val value = SonarGeometry(root.optionalNumber("displayedMaxDepthM", Double.MIN_VALUE..1_000_000.0),
            SonarTxReference.valueOf(root.string("txReference")), root.integer("flags"),
            if (root.has("adcSamplePeriodUs")) root.optionalNumber("adcSamplePeriodUs", Double.MIN_VALUE..1000.0) else null)
        if (value.txReference != SonarTxReference.UNVERIFIED) {
            require(value.flags and 1L != 0L && value.flags and 32L == 0L)
            require((value.txReference == SonarTxReference.EMPIRICAL_FALLBACK) == (value.flags and 128L != 0L))
        }
        return value
    }
    private val CLASSIFICATIONS = setOf("bottom_like", "fish_like", "weed_like", "bubble_like", "multipath_like", "unknown")
    private fun presentation(root: JSONObject, pingId: Long): SonarPresentation? {
        // Wrong-ping correlation and payload bounds are protocol corruption, even for optional analysis.
        if (!root.has("pingId") || root.isNull("pingId")) return null
        require(root.integer("pingId") == pingId)
        val values = root.optJSONArray("tracks")
        require(values == null || values.length() <= 8)
        val quality = (root.opt("quality") as? String)?.let { name -> SonarAnalysisQuality.entries.firstOrNull { it.name == name } } ?: return null
        val tracks = ArrayList<SonarTrack>(8)
        if (values != null && quality != SonarAnalysisQuality.UNVERIFIED_TX) {
            repeat(values.length()) { index ->
                // A new class/detector or incomplete optional track only removes that overlay.
                val track = runCatching {
                    val item = values.getJSONObject(index)
                    SonarTrack(item.integer("trackId"), item.number("bin", 0.0..1023.0), item.number("confidence", 0.0..1.0),
                        item.string("classification"), item.string("classificationSource"), item.string("detector"))
                }.getOrNull()
                if (track != null && tracks.none { it.trackId == track.trackId }) tracks.add(track)
            }
        }
        return runCatching {
            val filter = root.obj("filter"); val recommendation = root.obj("recommendations")
            val range = recommendation.string("range").also { require(it in setOf("FOLLOW_BOTTOM", "HOLD")) }
            val total = if (root.has("trackTotal")) root.integer("trackTotal").also { require(it in 0..100_000) }.toInt()
                else values?.length() ?: 0
            val omitted = if (root.has("trackOmitted")) root.integer("trackOmitted").also { require(it in 0..100_000) }.toInt()
                else total - (values?.length() ?: 0)
            require(total >= (values?.length() ?: 0) && omitted == total - (values?.length() ?: 0))
            val reason = (root.opt("reason") as? String)?.takeIf { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) }
            SonarPresentation(pingId, quality, reason, filter.string("requestedMode"), filter.string("effectiveMode"), filter.bool("active"),
                range == "FOLLOW_BOTTOM", if (!recommendation.has("rangeEndBin")) null else recommendation.optionalNumber("rangeEndBin", 32.0..1023.0),
                recommendation.bool("freeze"), tracks, total, total - tracks.size)
        }.getOrNull()
    }
    private fun parse(json: String, max: Int): JSONObject {
        require(json.toByteArray(Charsets.UTF_8).size <= max)
        StrictJsonSyntax.validate(json)
        val token = JSONTokener(json)
        val root = token.nextValue() as? JSONObject ?: error("Expected JSON object")
        require(token.nextClean() == '\u0000')
        return root
    }
    private fun JSONObject.obj(key: String) = get(key) as? JSONObject ?: error("$key must be an object")
    private fun JSONObject.bool(key: String) = get(key) as? Boolean ?: error("$key must be boolean")
    private fun JSONObject.string(key: String): String = (get(key) as? String)?.takeIf { it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl) } ?: error("Invalid $key")
    private fun JSONObject.optionalString(key: String): String? { require(has(key)); return if (isNull(key)) null else string(key) }
    private fun JSONObject.integer(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
    private fun JSONObject.number(key: String, range: ClosedFloatingPointRange<Double>): Double = ((get(key) as? Number)?.toDouble() ?: error("Invalid $key")).also { require(it.isFinite() && it in range) }
    private fun JSONObject.optionalNumber(key: String, range: ClosedFloatingPointRange<Double>): Double? { require(has(key)); return if (isNull(key)) null else number(key, range) }
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (error: Exception) { throw PiProtocolException("Invalid No5 SONAR V1 response", error) }
}
