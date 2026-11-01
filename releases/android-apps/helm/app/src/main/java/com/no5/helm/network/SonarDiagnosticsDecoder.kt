package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.json.JSONTokener

/** A bounded, read-only snapshot. Text preserves full 64-bit counters instead of rounding via Double. */
internal object SonarDiagnosticsDecoder {
    const val MAX_BYTES = 32_768
    fun decode(json: String, identity: SourceIdentity): SonarDiagnostics {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        StrictJsonSyntax.validate(json)
        val tokens = JSONTokener(json)
        val root = tokens.nextValue() as? JSONObject ?: error("Expected SONAR diagnostics")
        require(tokens.nextClean() == '\u0000')
        require(root.get("version") == 1 && root.getString("kind") == "sonar-diagnostics")
        fun integer(name: String): Long = root.get(name).let { require(it is Int || it is Long); (it as Number).toLong() }
        fun optionalInteger(name: String): Long? = if (!root.has(name) || root.isNull(name)) null else integer(name)
        fun count(name: String): Int = if (!root.has(name)) 0 else integer(name).also { require(it in 0..1_000_000) }.toInt()
        val session = root.getString("serverSessionId").also { require(it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl)) }
        val fields = root.optJSONArray("fields")
        require(fields == null || fields.length() <= 128)
        val parsed = if (fields == null) emptyList() else List(fields.length()) { index ->
            val field = fields.getJSONObject(index)
            fun text(name: String, limit: Int): String = field.getString(name).also {
                require(it.length <= limit && it.none { ch -> ch.isISOControl() && ch != '\n' && ch != '\t' })
            }
            SonarDiagnosticField(text("group", 80), text("name", 80), text("value", 2048),
                if (!field.has("unit") || field.isNull("unit")) null else text("unit", 40))
        }
        return SonarDiagnostics(identity, integer("generation").also { require(it >= 0) }, session,
            optionalInteger("pingId")?.also { require(it >= 0) }, optionalInteger("observedAtEpochMs")?.also { require(it > 0) },
            SonarV1Decoder.optionalPingSource(root, "source"), parsed,
            count("trackTotal"), count("trackOmitted"), count("blockTotal"), count("blockOmitted"),
            root.opt("available") as? Boolean ?: false, root.opt("stale") as? Boolean ?: true,
            optionalInteger("statusObservedAtEpochMs")?.also { require(it > 0) }, root.opt("statusStale") as? Boolean,
            if (!root.has("statusTimeBasis") || root.isNull("statusTimeBasis")) null else root.getString("statusTimeBasis").also {
                require(it.length <= 80 && it.none(Char::isISOControl))
            })
    }
}
