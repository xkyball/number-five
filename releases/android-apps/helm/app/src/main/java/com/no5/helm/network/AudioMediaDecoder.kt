package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.json.JSONTokener

/** Strict additive media contract. No CDN URL or arbitrary image path crosses into the UI. */
internal object AudioMediaDecoder {
    private val session = Regex("[A-Za-z0-9_-]{1,100}")
    private val uri = Regex("spotify:(track|episode|playlist|album|artist):[A-Za-z0-9]{22}")
    private val imageId = Regex("[a-f0-9]{64}")
    fun metadata(json: String, now: Long): AudioMediaMetadata = decode {
        val root = parse(json)
        root.keysExactly("version", "kind", "receiverSessionId", "available", "observedAtEpochMs", "track", "artwork", "queue")
        root.header("spotify-media")
        val receiver = root.text("receiverSessionId").also { require(session.matches(it)) }
        val stamp = root.stamp(now)
        val available = root.bool("available")
        if (!available) {
            require(listOf("track", "artwork", "queue").all(root::isNull))
            return@decode AudioMediaMetadata(receiver, stamp, status = "Spotify context unavailable")
        }
        val track = root.getJSONObject("track")
        track.keysExactly("id", "uri", "title", "artists", "album", "durationMs")
        val trackUri = track.optionalText("uri")?.also { require(uri.matches(it) && (it.startsWith("spotify:track:") || it.startsWith("spotify:episode:"))) }
        listOf("id", "title", "album").forEach { track.optionalText(it) }
        val artists = track.getJSONArray("artists"); require(artists.length() <= 10)
        repeat(artists.length()) { text(artists.get(it)) }
        if (!track.isNull("durationMs")) require(track.integer("durationMs") in 1..86_400_000)
        val artwork = if (root.isNull("artwork")) null else root.getJSONObject("artwork").let {
            it.keysExactly("id", "path", "width", "height")
            val id = it.text("id").also { value -> require(imageId.matches(value)) }
            val path = it.text("path").also { value -> require(value == "/v1/audio/artwork/$id" && trackUri != null) }
            val w = it.integer("width").also { value -> require(value in 1..2048) }.toInt()
            val h = it.integer("height").also { value -> require(value in 1..2048 && value * w <= 1_048_576) }.toInt()
            AudioArtworkDescriptor(id, path, w, h)
        }
        val queue = if (root.isNull("queue")) null else root.getJSONObject("queue").let {
            it.keysExactly("revision", "contextUri", "observedAtEpochMs", "previous", "next", "complete")
            require(!it.bool("complete"))
            AudioObservedQueue(it.optionalText("revision"), it.optionalText("contextUri")?.also { value -> require(uri.matches(value)) },
                it.stamp(now), it.items("previous", 10), it.items("next", 80))
        }
        AudioMediaMetadata(receiver, stamp, true, trackUri, artwork, queue, "Observed Spotify context")
    }
    fun browse(json: String, expected: String, query: AudioBrowseQuery, now: Long): AudioBrowsePage = decode {
        val root = parse(json); root.keysExactly("version", "kind", "receiverSessionId", "observedAtEpochMs", "page")
        root.header("spotify-browse"); require(root.text("receiverSessionId") == expected); root.stamp(now)
        val page = root.getJSONObject("page")
        val allowed = setOf("offset", "limit", "revision", "items", "nextOffset", "complete", "unresolvedPages", "collectionRevision")
        require(page.keys().asSequence().toSet().let { it.containsAll(allowed - setOf("unresolvedPages", "collectionRevision")) && (it - allowed).isEmpty() })
        require(page.integer("offset") == query.offset.toLong() && page.integer("limit") == query.limit.toLong())
        val next = if (page.isNull("nextOffset")) null else page.integer("nextOffset").also { require(it in (query.offset + 1).toLong()..(query.offset + query.limit).toLong()) }.toInt()
        val complete = page.bool("complete"); require(!complete || next == null)
        AudioBrowsePage(expected, query.offset, page.optionalText("revision"), page.items("items", query.limit, query.kind == AudioBrowseKind.PLAYLISTS), next, complete,
            page.has("unresolvedPages") && page.bool("unresolvedPages"), if(page.has("collectionRevision"))page.optionalText("collectionRevision")else null)
    }
    fun validate(query: AudioBrowseQuery) {
        require(query.offset in 0..5000 && query.limit in 1..40)
        when (query.kind) {
            AudioBrowseKind.PLAYLIST -> require(Regex("spotify:playlist:[A-Za-z0-9]{22}").matches(query.uri.orEmpty()) && query.text == null)
            AudioBrowseKind.SEARCH -> require(query.text != null && text(query.text).isNotBlank() && query.uri == null)
            else -> require(query.uri == null && query.text == null)
        }
    }
    fun selectionBody(request: AudioMediaSelection): String {
        require(Regex("[A-Za-z0-9:_-]{1,200}").matches(request.requestId))
        require(session.matches(request.receiverSessionId))
        require(Regex("[a-f0-9-]{36}").matches(request.queueRevision))
        if (request.playlistShuffle != null) {
            require(Regex("spotify:playlist:[A-Za-z0-9]{22}").matches(request.uri))
            val query=requireNotNull(request.query)
            require(query.kind == AudioBrowseKind.PLAYLIST && query.uri == request.uri && query.offset == 0)
            require(!request.pageRevision.isNullOrBlank())
            return JSONObject().put("version",1).put("kind","spotify-selection").put("requestId",request.requestId)
                .put("receiverSessionId",request.receiverSessionId).put("queueRevision",request.queueRevision)
                .put("selection",JSONObject().put("type","PLAYLIST_START").put("uri",request.uri)
                    .put("shuffle",request.playlistShuffle).put("pageRevision",request.pageRevision)).toString()
        }
        require(Regex("spotify:(track|episode):[A-Za-z0-9]{22}").matches(request.uri))
        val target = JSONObject().put("type", if (request.query == null) "QUEUE" else "COLLECTION")
            .put("index", request.index).put("uri", request.uri)
        if (request.query == null) require(request.index in 0..79) else {
            val q = requireNotNull(request.query); validate(q); require(q.kind != AudioBrowseKind.PLAYLISTS && request.index in 0 until q.limit)
            val query = JSONObject().put("type", q.kind.name).put("offset", q.offset).put("limit", q.limit)
            q.uri?.let { query.put("uri", it) }; q.text?.let { query.put("query", it) }
            target.put("query", query).put("pageRevision", request.pageRevision ?: JSONObject.NULL)
        }
        return JSONObject().put("version", 1).put("kind", "spotify-selection").put("requestId", request.requestId)
            .put("receiverSessionId", request.receiverSessionId).put("queueRevision", request.queueRevision).put("selection", target).toString()
    }
    fun selection(json: String, request: AudioMediaSelection, now: Long): AudioMediaSelectionResult = decode {
        val root = parse(json); root.keysExactly("version", "kind", "requestId", "receiverSessionId", "status", "message", "confirmation")
        root.header("spotify-selection-ack")
        require(root.text("requestId") == request.requestId && root.text("receiverSessionId") == request.receiverSessionId)
        val status = AudioMediaSelectionStatus.valueOf(root.text("status"))
        require(status in setOf(AudioMediaSelectionStatus.VERIFIED, AudioMediaSelectionStatus.REJECTED, AudioMediaSelectionStatus.UNKNOWN))
        if (status == AudioMediaSelectionStatus.VERIFIED) {
            val c = root.getJSONObject("confirmation")
            if(request.playlistShuffle != null) {
                c.keysExactly("version","kind","receiverSessionId","playlistUri","shuffle","trackUri","entryId","observedAtEpochMs","playerSequence")
                c.header("spotify-playlist-start-readback")
                require(c.text("playlistUri")==request.uri && c.bool("shuffle")==request.playlistShuffle)
                require(Regex("spotify:(track|episode):[A-Za-z0-9]{22}").matches(c.text("trackUri")))
            } else {
                c.keysExactly("version", "kind", "receiverSessionId", "trackUri", "entryId", "observedAtEpochMs", "playerSequence")
                c.header("spotify-selection-readback"); require(c.text("trackUri") == request.uri)
            }
            require(c.text("receiverSessionId") == request.receiverSessionId)
            require(c.get("entryId") is String && c.getString("entryId").length <= 200)
            c.stamp(now); require(c.integer("playerSequence") > 0)
        }
        AudioMediaSelectionResult(request.requestId, request.uri, status, root.text("message"))
    }
    private fun JSONObject.items(key: String, maximum: Int, playlists: Boolean = false): List<AudioQueueItem> {
        val a = getJSONArray(key); require(a.length() <= maximum)
        return List(a.length()) { a.getJSONObject(it).let { item ->
            val fields = item.keys().asSequence().toSet()
            require(fields.containsAll(setOf("uri","title","artist")) && (fields-setOf("uri","title","artist")-(if(playlists)setOf("playlist")else emptySet())).isEmpty())
            val itemUri = item.text("uri").also { require(uri.matches(it)) }
            val details = if(!item.has("playlist")) null else item.getJSONObject("playlist").let { d ->
                require(itemUri.startsWith("spotify:playlist:")); d.keysExactly("owner","description","trackCount","collaborative","artwork")
                val cover = if(d.isNull("artwork")) null else d.getJSONObject("artwork").let { art ->
                    art.keysExactly("id","path"); val id=art.text("id").also { require(imageId.matches(it)) }
                    AudioPlaylistArtworkDescriptor(id,art.text("path").also {require(it=="/v1/audio/playlist-artwork/$id")})
                }
                AudioPlaylistDetails(d.optionalText("owner"),d.optionalText("description"),if(d.isNull("trackCount"))null else d.integer("trackCount").also {require(it in 0..1_000_000)}.toInt(),
                    if(d.isNull("collaborative"))null else d.bool("collaborative"),cover)
            }
            AudioQueueItem(itemUri,item.optionalText("title"),item.optionalText("artist"),details)
        } }
    }
    private fun JSONObject.keysExactly(vararg keys: String) { require(keys().asSequence().toSet() == keys.toSet()) }
    private fun JSONObject.header(kind: String) { require(integer("version") == 1L && text("kind") == kind) }
    private fun JSONObject.stamp(now: Long): Long = integer("observedAtEpochMs").also { require(it > 0 && now - it in -5000..15_000) }
    private fun JSONObject.integer(key: String): Long = get(key).let { require(it is Int || it is Long); (it as Number).toLong() }
    private fun JSONObject.bool(key: String): Boolean = get(key) as? Boolean ?: error("Invalid boolean")
    private fun JSONObject.text(key: String) = text(get(key))
    private fun JSONObject.optionalText(key: String): String? { require(has(key)); return if (isNull(key)) null else text(key) }
    private fun text(value: Any?): String = (value as? String)?.also { require(it.isNotBlank() && it.length <= 200 && it.none(Char::isISOControl)) } ?: error("Invalid text")
    private fun parse(json: String): JSONObject {
        require(json.toByteArray(Charsets.UTF_8).size <= 131_072); StrictJsonSyntax.validate(json)
        val t = JSONTokener(json); val root = t.nextValue() as? JSONObject ?: error("Expected object"); require(t.nextClean() == '\u0000'); return root
    }
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (e: Exception) { throw PiProtocolException("Invalid Spotify media response", e) }
}
