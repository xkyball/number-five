package com.no5.helm.network

import com.no5.domain.*
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

internal enum class AudioMediaReadPhase { IDLE, NAVIGATION_READ, MEDIA_FALLBACK_READ, MEDIA_DECODE }
internal enum class AudioMediaReadReason { NOT_STARTED, IN_FLIGHT, READY, CONTEXT_UNAVAILABLE, HTTP_STATUS, TRANSPORT_UNAVAILABLE, SCHEMA_INVALID }
/** Fixed fields only: no remote payload, track, origin or exception text. */
internal data class AudioMediaReadDiagnostic(val phase: AudioMediaReadPhase, val reason: AudioMediaReadReason,
    val httpStatus: Int? = null)

/** Independent bounded read workers; no AUDIO command lock, retries or UI-thread I/O. */
internal class NetworkPiAudioMedia(
    private val reads: PiHttpTransport,
    private val images: PiHttpTransport,
    private val now: () -> Long = System::currentTimeMillis,
    scheduler: PiPollScheduler = BackgroundPiPollScheduler("audio-media"),
    imageScheduler: PiPollScheduler = BackgroundPiPollScheduler("audio-artwork"),
    private val commands: PiHttpTransport? = null,
    selectionScheduler: PiPollScheduler = BackgroundPiPollScheduler("audio-selection"),
    private val retryUptimeMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) : AudioMediaPort {
    private val lock = Any()
    private var closed = false
    private var generation = 0L
    private var queued: Pair<Long, AudioBrowseQuery>? = null
    private data class ArtworkRequest(val target: AudioMediaMetadata, val generation: Long,
        val failures: Int = 0, val notBeforeUptimeMs: Long = Long.MIN_VALUE)
    private var artworkGeneration = 0L
    private var imagePending: ArtworkRequest? = null
    @Volatile var readDiagnostic = AudioMediaReadDiagnostic(AudioMediaReadPhase.IDLE, AudioMediaReadReason.NOT_STARTED)
        private set
    private data class PlaylistLoad(val receiver: String, val query: AudioBrowseQuery, val items: List<AudioQueueItem>, val revision: String?)
    private var playlistLoad: PlaylistLoad? = null
    private val playlistDescriptors = LinkedHashMap<String, AudioPlaylistArtworkDescriptor>()
    private val playlistPending = LinkedHashMap<String, AudioPlaylistArtworkDescriptor>()
    private val playlistAttempted = mutableSetOf<String>()
    private var playlistImageGeneration = 0L
    private val playlistArtworkFeed = MutableSourceFeed<Map<String, AudioPlaylistArtworkBytes>>(emptyMap())
    private val retired = LinkedHashSet<String>()
    private val polling = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)
    private val metadataFeed = MutableSourceFeed(AudioMediaMetadata())
    private val artworkFeed = MutableSourceFeed<AudioArtworkBytes?>(null)
    private val browseFeed = MutableSourceFeed(AudioBrowseResult())
    private val selectionFeed = MutableSourceFeed(AudioMediaSelectionResult())
    private var pendingSelection: AudioMediaSelection? = null
    private val selecting = AtomicBoolean(false)
    override val metadata: SourceFeed<AudioMediaMetadata> get() = metadataFeed
    override val artwork: SourceFeed<AudioArtworkBytes?> get() = artworkFeed
    override val playlistArtwork: SourceFeed<Map<String, AudioPlaylistArtworkBytes>> get() = playlistArtworkFeed
    override val browsing: SourceFeed<AudioBrowseResult> get() = browseFeed
    override val selection: SourceFeed<AudioMediaSelectionResult> get() = selectionFeed
    private val job: SourceSubscription
    private val imageJob: SourceSubscription
    private val selectionJob: SourceSubscription?
    init {
        try { job = scheduler.schedule(1000, ::poll) } catch (e: RuntimeException) { reads.close(); images.close(); throw e }
        try { imageJob = imageScheduler.schedule(250, ::download) } catch (e: RuntimeException) { job.close(); reads.close(); images.close(); throw e }
        try { selectionJob = commands?.let { selectionScheduler.schedule(250, ::selectOnce) } }
        catch (e: RuntimeException) { job.close(); imageJob.close(); reads.close(); images.close(); commands?.close(); throw e }
    }
    override fun select(request: AudioMediaSelection) {
        AudioMediaDecoder.selectionBody(request)
        synchronized(lock) {
            if (closed || selection.value.status == AudioMediaSelectionStatus.PENDING) return
            val current = metadata.value
            if (commands == null || !current.navigationAvailable || !current.available || current.receiverSessionId != request.receiverSessionId || current.queue?.revision != request.queueRevision) {
                selectionFeed.emit(AudioMediaSelectionResult(request.requestId, request.uri, AudioMediaSelectionStatus.REJECTED, "Liste geändert; kein Befehl gesendet")); return
            }
            pendingSelection = request
            selectionFeed.emit(AudioMediaSelectionResult(request.requestId, request.uri, AudioMediaSelectionStatus.PENDING, "Wird bestätigt …"))
        }
    }
    internal fun selectOnce() {
        if (!selecting.compareAndSet(false, true)) return
        try {
            val request = synchronized(lock) { if (closed) null else pendingSelection.also { pendingSelection = null } } ?: return
            // Recheck before sending. A request is consumed exactly once, including timeouts.
            val result = try {
                val valid = synchronized(lock) { !closed && metadata.value.available && metadata.value.receiverSessionId == request.receiverSessionId && metadata.value.queue?.revision == request.queueRevision }
                if (!valid) AudioMediaSelectionResult(request.requestId, request.uri, AudioMediaSelectionStatus.REJECTED, "Liste geändert; kein Befehl gesendet")
                else AudioMediaDecoder.selection(requireNotNull(commands).post("/v1/audio/select", AudioMediaDecoder.selectionBody(request)), request, now())
            } catch (_: Exception) { AudioMediaSelectionResult(request.requestId, request.uri, AudioMediaSelectionStatus.UNKNOWN, "Ausgang unklar · keine Wiederholung") }
            synchronized(lock) { if (!closed) selectionFeed.emit(if(metadata.value.receiverSessionId == request.receiverSessionId) result
                else AudioMediaSelectionResult(request.requestId,request.uri,AudioMediaSelectionStatus.UNKNOWN,"Sitzung gewechselt · keine Wiederholung")) }
        } finally { selecting.set(false) }
    }
    override fun requestPlaylistArtwork(items: List<AudioQueueItem>) = synchronized(lock) {
        if(closed || !metadata.value.available) return@synchronized
        items.take(8).forEach { item -> item.playlist?.artwork?.let { art ->
            if(playlistDescriptors[item.uri] == art && playlistArtwork.value[item.uri]?.id != art.id && art.id !in playlistAttempted && playlistPending.size < 16)
                playlistPending[item.uri] = art
        } }
    }
    override fun browse(query: AudioBrowseQuery) {
        AudioMediaDecoder.validate(query)
        synchronized(lock) {
            if (closed) return
            generation++; playlistLoad = null
            if(query.kind == AudioBrowseKind.PLAYLISTS) {
                playlistImageGeneration++; playlistPending.clear(); playlistDescriptors.clear(); playlistAttempted.clear()
            }
            if (!metadata.value.available) { browseFeed.emit(AudioBrowseResult(query, status = "Connect session unavailable")); return }
            queued = generation to query
            browseFeed.emit(AudioBrowseResult(query, loading = true, status = "Reading existing Connect session"))
        }
    }
    override fun cancelBrowse() = synchronized(lock) {
        generation++; queued = null; playlistLoad = null; playlistImageGeneration++; playlistPending.clear()
        if (!closed) browseFeed.emit(AudioBrowseResult())
    }
    internal fun poll() {
        if (!polling.compareAndSet(false, true)) return
        var phase = AudioMediaReadPhase.NAVIGATION_READ
        try {
            if (synchronized(lock) { closed }) return
            readDiagnostic = AudioMediaReadDiagnostic(phase, AudioMediaReadReason.IN_FLIGHT)
            var navigation = true
            val json = try { reads.get("/v1/audio/navigation") } catch (e: PiHttpStatusException) {
                if (e.statusCode != 404) throw e
                navigation = false; phase = AudioMediaReadPhase.MEDIA_FALLBACK_READ
                readDiagnostic = AudioMediaReadDiagnostic(phase, AudioMediaReadReason.IN_FLIGHT)
                reads.get("/v1/audio/media")
            }
            phase = AudioMediaReadPhase.MEDIA_DECODE
            readDiagnostic = AudioMediaReadDiagnostic(phase, AudioMediaReadReason.IN_FLIGHT)
            val next = AudioMediaDecoder.metadata(json, now()).copy(navigationAvailable = navigation && commands != null)
            synchronized(lock) {
                if (closed || next.receiverSessionId in retired) return
                val old = metadata.value
                if (old.receiverSessionId != next.receiverSessionId) {
                    old.receiverSessionId?.let { retired += it; if (retired.size > 16) retired.remove(retired.first()) }
                    generation++; queued = null; playlistLoad = null; playlistImageGeneration++; playlistPending.clear(); playlistDescriptors.clear(); playlistAttempted.clear(); playlistArtworkFeed.emit(emptyMap()); browseFeed.emit(AudioBrowseResult(status = "Connect session changed"))
                } else if (old.observedAtEpochMs != null && requireNotNull(next.observedAtEpochMs) < requireNotNull(old.observedAtEpochMs)) return
                if (!sameImage(old, next)) {
                    artworkGeneration++; artworkFeed.emit(null)
                    imagePending = next.takeIf { it.available && it.artwork != null }?.let { ArtworkRequest(it, artworkGeneration) }
                }
                if (!next.available) { generation++; queued = null; playlistLoad = null; playlistImageGeneration++; playlistPending.clear(); playlistArtworkFeed.emit(emptyMap()); browseFeed.emit(AudioBrowseResult(status = "Connect session unavailable")) }
                readDiagnostic = AudioMediaReadDiagnostic(phase,
                    if (next.available) AudioMediaReadReason.READY else AudioMediaReadReason.CONTEXT_UNAVAILABLE)
                metadataFeed.emit(next)
            }
            val work = synchronized(lock) { queued.also { queued = null } } ?: return
            val receiver = next.receiverSessionId ?: return
            val query = work.second
            try {
                val page = AudioMediaDecoder.browse(reads.get(path(receiver, query)), receiver, query, now())
                synchronized(lock) {
                    if (!closed && generation == work.first && metadata.value.available && metadata.value.receiverSessionId == receiver) {
                        if(query.kind == AudioBrowseKind.PLAYLISTS) {
                            val previous = playlistLoad
                            require(previous == null || previous.receiver == receiver)
                            require(previous == null || previous.revision == page.collectionRevision) { "Rootlist changed during pagination" }
                            val all = (previous?.items.orEmpty() + page.items).distinctBy { it.uri }
                            require(all.size <= 5000)
                            val initial = previous?.query ?: query
                            playlistLoad = PlaylistLoad(receiver,initial,all,page.collectionRevision)
                            all.forEach { item -> item.playlist?.artwork?.let { playlistDescriptors[item.uri] = it } }
                            val nextOffset = page.nextOffset?.takeIf { it <= 5000 }
                            val more = nextOffset != null && !page.complete
                            val combined = page.copy(offset=initial.offset,items=all,unresolvedPages=page.unresolvedPages || (!page.complete && !more))
                            if(more) queued = work.first to query.copy(offset=requireNotNull(nextOffset))
                            browseFeed.emit(AudioBrowseResult(initial,combined,loading=more,status=if(page.complete)"Observed complete rootlist" else "Partial observed rootlist"))
                        } else browseFeed.emit(AudioBrowseResult(query,page,status=if(page.complete)"Observed page" else "Partial observed collection"))
                    }
                }
            } catch (_: Exception) {
                synchronized(lock) { if (!closed && generation == work.first) {
                    val partial = browseFeed.value.takeIf { query.kind == AudioBrowseKind.PLAYLISTS && it.page != null }
                    browseFeed.emit(partial?.copy(loading=false,page=partial.page?.copy(complete=false,unresolvedPages=true),status="Partial rootlist; refresh required")
                        ?: AudioBrowseResult(query,status="Existing-session access unavailable; no replay"))
                } }
            }
        } catch (failure: Exception) {
            synchronized(lock) {
                if (!closed) {
                    val reason = when (failure) {
                        is PiHttpStatusException -> AudioMediaReadReason.HTTP_STATUS
                        is PiProtocolException -> AudioMediaReadReason.SCHEMA_INVALID
                        else -> AudioMediaReadReason.TRANSPORT_UNAVAILABLE
                    }
                    val http = (failure as? PiHttpStatusException)?.statusCode?.takeIf { it in 100..599 }
                    readDiagnostic = AudioMediaReadDiagnostic(phase, reason, http)
                    val safeStatus = "MEDIA_${phase.name}_${reason.name}" + (http?.let { "_$it" } ?: "")
                    generation++; artworkGeneration++; queued = null; playlistLoad = null; imagePending = null; artworkFeed.emit(null); playlistImageGeneration++; playlistPending.clear(); playlistArtworkFeed.emit(emptyMap())
                    metadataFeed.emit(AudioMediaMetadata(receiverSessionId = metadata.value.receiverSessionId, observedAtEpochMs = metadata.value.observedAtEpochMs, status = safeStatus))
                    browseFeed.emit(AudioBrowseResult(status = safeStatus))
                }
            }
        } finally { polling.set(false) }
    }
    internal fun download() {
        if (!downloading.compareAndSet(false, true)) return
        var request: ArtworkRequest? = null
        try {
            val work = synchronized(lock) {
                if (closed) null else imagePending?.takeIf { retryUptimeMs() >= it.notBeforeUptimeMs }
                    ?.also { imagePending = null }
            }
            if (work == null) { downloadPlaylist(); return }
            request = work
            val target = work.target
            val descriptor = target.artwork ?: return
            val bytes = images.getBytes(descriptor.path); require(bytes.size in 20..1_048_576)
            synchronized(lock) {
                if (!closed && work.generation == artworkGeneration && sameImage(target, metadata.value))
                    artworkFeed.emit(AudioArtworkBytes(requireNotNull(target.receiverSessionId), requireNotNull(target.trackUri), descriptor.id, bytes))
            }
        } catch (_: Exception) {
            // Read-only retry uses the SAME scheduler. Never queue or replay a music command.
            val work = request
            if (work != null) synchronized(lock) {
                if (!closed && work.generation == artworkGeneration && sameImage(work.target, metadata.value)) {
                    val delay = ARTWORK_RETRY_MS[work.failures.coerceAtMost(ARTWORK_RETRY_MS.lastIndex)]
                    val clock = retryUptimeMs()
                    if (clock <= Long.MAX_VALUE - delay) imagePending = work.copy(
                        failures = (work.failures + 1).coerceAtMost(ARTWORK_RETRY_MS.lastIndex), notBeforeUptimeMs = clock + delay)
                }
            }
        } finally { downloading.set(false) }
    }
    private fun downloadPlaylist() {
        val work = synchronized(lock) {
            if(closed || !metadata.value.available) null else playlistPending.entries.firstOrNull()?.let {
                val target=Triple(it.key,it.value,playlistImageGeneration); playlistPending.remove(it.key); playlistAttempted += it.value.id
                target to requireNotNull(metadata.value.receiverSessionId)
            }
        } ?: return
        val (target, receiver)=work
        val bytes=images.getBytes(target.second.path); require(bytes.size in 20..1_048_576)
        synchronized(lock) {
            if(!closed && metadata.value.available && metadata.value.receiverSessionId==receiver && target.third==playlistImageGeneration && playlistDescriptors[target.first]==target.second) {
                val entries=LinkedHashMap(playlistArtwork.value); entries.remove(target.first)
                entries[target.first]=AudioPlaylistArtworkBytes(receiver,target.first,target.second.id,bytes)
                while(entries.size>32) {
                    val evicted=entries.remove(entries.keys.first())
                    evicted?.let { playlistAttempted.remove(it.id) }
                }
                playlistArtworkFeed.emit(entries)
            }
        }
    }
    private companion object { val ARTWORK_RETRY_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000) }
    private fun sameImage(a: AudioMediaMetadata, b: AudioMediaMetadata) = a.available == b.available && a.receiverSessionId == b.receiverSessionId && a.trackUri == b.trackUri && a.artwork == b.artwork
    private fun path(receiver: String, q: AudioBrowseQuery): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "/v1/audio/browse?receiverSessionId=${enc(receiver)}&type=${q.kind}&offset=${q.offset}&limit=${q.limit}" +
            (q.uri?.let { "&uri=${enc(it)}" } ?: "") + (q.text?.let { "&query=${enc(it)}" } ?: "")
    }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true; generation++; artworkGeneration++; queued = null; imagePending = null
            pendingSelection = null; playlistLoad = null; playlistImageGeneration++; playlistPending.clear(); playlistDescriptors.clear(); playlistAttempted.clear(); playlistArtworkFeed.close(); metadataFeed.close(); artworkFeed.close(); browseFeed.close(); selectionFeed.close() }
        try { job.close(); imageJob.close(); selectionJob?.close() } finally { try { reads.close() } finally { try { images.close() } finally { commands?.close() } } }
    }
}
