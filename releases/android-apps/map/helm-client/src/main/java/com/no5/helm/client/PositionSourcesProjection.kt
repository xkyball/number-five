package com.no5.helm.client

import com.no5.helm.model.*
import org.json.JSONObject

/** Additive read-only projection from the already syntax-checked /v1/helm response. */
internal object PositionSourcesProjection {
    private val gnssKeys = setOf("methodQuality", "integrity", "type", "satellites", "horizontalDilution", "positionDilution")
    fun decode(payload: String, now: Long): PositionSources? {
        val root = JSONObject(payload)
        if (!root.has("positionSources")) return null // Explicit legacy provenance in the controller.
        return try {
            val block = root.getJSONObject("positionSources")
            require(block.toString().toByteArray(Charsets.UTF_8).size <= 24576)
            require(integer(block, "version") == 1L)
            require(block.get("sourceKind") == "REAL" && block.get("simulated") == false)
            val total = integer(block, "total").also { require(it in 0..65536) }.toInt()
            val omitted = integer(block, "omitted").also { require(it in 0..total.toLong()) }.toInt()
            val freshness = block.getJSONObject("freshness")
            require(integer(freshness, "liveForMs") == 5000L && integer(freshness, "lostAfterMs") == 30000L)
            val values = block.getJSONArray("sources")
            require(total == values.length() + omitted)
            val seen = HashSet<String>()
            val sources = (0 until values.length()).map { i ->
                val c = values.getJSONObject(i)
                val id = text(c, "sourceId"); val ref = text(c, "sourceRef"); val label = text(c, "label")
                require(id == ref && label == ref && seen.add(id))
                var status = PositionStatus.valueOf(text(c, "status"))
                val observed = nullableTime(c, "observedAtEpochMs")
                val p = if (c.isNull("position")) null else c.getJSONObject("position")
                val lat = p?.let { number(it, "latitudeDegrees") }
                val lon = p?.let { number(it, "longitudeDegrees") }
                val position = if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
                val altitude = if (p == null || p.isNull("altitudeMetres")) null else number(p, "altitudeMetres")
                if (position == null && status in setOf(PositionStatus.LIVE, PositionStatus.STALE) || observed != null && (observed < 0 || observed > now)) status = PositionStatus.INVALID
                if (status == PositionStatus.LIVE && (observed == null || now - observed > 5000)) status = if (observed == null) PositionStatus.UNAVAILABLE else if (now - observed > 30000) PositionStatus.LOST else PositionStatus.STALE
                val gnss = c.optJSONObject("gnss")?.let { g ->
                    gnssKeys.mapNotNull { key ->
                        if (!g.has(key)) null else {
                            val item = g.getJSONObject(key)
                            val source = text(item, "sourceRef"); require(source == ref)
                            val value = item.opt("value").takeUnless { it == null || it === JSONObject.NULL }
                            require(value == null || value is String || value is Number && value.toDouble().isFinite())
                            val valueText = value?.toString()?.also { require(it.length <= 200 && it.none(Char::isISOControl)) }
                            val stamp = nullableTime(item, "observedAtEpochMs")
                            var quality = PositionStatus.valueOf(text(item, "status"))
                            if (stamp != null && (stamp < 0 || stamp > now)) quality = PositionStatus.INVALID
                            else if (quality == PositionStatus.LIVE && (stamp == null || now - stamp > 5000)) quality = if (stamp == null) PositionStatus.UNAVAILABLE else PositionStatus.STALE
                            key to GnssObservation(valueText, source, stamp, quality)
                        }
                    }.toMap()
                }.orEmpty()
                PositionCandidate(id, ref, label, status, observed, position, altitude, gnss)
            }
            val primary = if (block.isNull("primarySourceId")) null else text(block, "primarySourceId")
            require(primary == null || sources.any { it.sourceId == primary } || omitted > 0)
            val combination = block.getJSONObject("combination")
            require(combination.get("available") is Boolean)
            val available = combination.getBoolean("available")
            val reason = text(combination, "reason")
            val result = if (available) decodeCombination(combination, sources, now) else null
            PositionSources(primarySourceId = primary, total = total, omitted = omitted, sources = sources,
                combinationAvailable = available, combinationReason = reason, combination = result)
        } catch (_: Exception) { PositionSources(valid = false) }
    }
    private fun decodeCombination(o: JSONObject, sources: List<PositionCandidate>, now: Long): PositionCombination {
        require(integer(o, "version") == 1L && text(o, "method") == "EQUAL_WEIGHT_SPHERICAL_V1")
        val mode = text(o, "mode"); require(mode in setOf("NONE", "CONSENSUS", "SINGLE_SOURCE"))
        val reason = text(o, "reason")
        val stamp = nullableTime(o, "observedAtEpochMs")
        var status = PositionStatus.valueOf(text(o, "status"))
        val sourceById = sources.associateBy { it.sourceId }
        val items = o.getJSONArray("contributors"); require(items.length() <= sourceById.size)
        val seen = HashSet<String>()
        val contributors = (0 until items.length()).map { i ->
            val item = items.getJSONObject(i); val id = text(item, "sourceId")
            val time = integer(item, "observedAtEpochMs")
            require(seen.add(id) && time >= 0 && time <= now)
            require(sourceById[id]?.observedAtEpochMs == time)
            CombinationContributor(id, time)
        }
        val p = if (o.isNull("position")) null else o.getJSONObject("position")
        val point = p?.let { GeoPoint(number(it, "latitudeDegrees"), number(it, "longitudeDegrees")) }
        require(point == null || point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0)
        if (stamp != null) require(stamp >= 0 && stamp <= now && stamp == contributors.minOfOrNull { it.observedAtEpochMs })
        if (status == PositionStatus.LIVE) {
            require(point != null && stamp != null)
            require(mode == "SINGLE_SOURCE" && contributors.size == 1 && reason == "SINGLE_SOURCE" ||
                mode == "CONSENSUS" && contributors.size >= 2 && reason == "OK")
            require(contributors.all { item -> sourceById[item.sourceId]?.let { it.position != null &&
                it.status in setOf(PositionStatus.LIVE, PositionStatus.STALE) } == true })
            if (now - stamp > 5000) status = if (now - stamp > 30000) PositionStatus.LOST else PositionStatus.STALE
            else if (contributors.any { item -> sourceById[item.sourceId]?.status == PositionStatus.STALE }) status = PositionStatus.STALE
        } else require(point == null && mode == "NONE")
        return PositionCombination(method = text(o,"method"), mode = mode, status = status, reason = reason,
            observedAtEpochMs = stamp, position = point, contributors = contributors)
    }
    private fun text(o: JSONObject, key: String) = (o.get(key) as? String ?: error("Expected text")).also {
        require(it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl))
    }
    private fun integer(o: JSONObject, key: String): Long {
        val n = o.get(key); require(n is Int || n is Long); return (n as Number).toLong()
    }
    private fun nullableTime(o: JSONObject, key: String) = if (o.isNull(key)) null else integer(o, key)
    private fun number(o: JSONObject, key: String) = (o.get(key) as? Number ?: error("Expected number")).toDouble().also { require(it.isFinite()) }
}
