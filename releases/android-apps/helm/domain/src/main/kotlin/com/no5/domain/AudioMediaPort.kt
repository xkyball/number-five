package com.no5.domain

/** Session-bound media reads and explicit, individually confirmed track selections. */
interface AudioMediaPort : AutoCloseable {
    val metadata: SourceFeed<AudioMediaMetadata>
    val artwork: SourceFeed<AudioArtworkBytes?>
    val playlistArtwork: SourceFeed<Map<String, AudioPlaylistArtworkBytes>> get() = UnsupportedPlaylistArtwork.feed
    fun requestPlaylistArtwork(items: List<AudioQueueItem>) = Unit
    val browsing: SourceFeed<AudioBrowseResult>
    val selection: SourceFeed<AudioMediaSelectionResult> get() = UnsupportedMediaSelection.feed
    fun select(request: AudioMediaSelection) = Unit
    fun browse(query: AudioBrowseQuery)
    fun cancelBrowse()
    override fun close()
}

data class AudioArtworkDescriptor(val id: String, val path: String, val width: Int, val height: Int)
data class AudioArtworkBytes(val receiverSessionId: String, val trackUri: String, val id: String, val bytes: ByteArray)
data class AudioPlaylistArtworkDescriptor(val id: String, val path: String)
data class AudioPlaylistArtworkBytes(val receiverSessionId: String, val uri: String, val id: String, val bytes: ByteArray)
data class AudioPlaylistDetails(val owner: String?, val description: String?, val trackCount: Int?,
    val collaborative: Boolean?, val artwork: AudioPlaylistArtworkDescriptor?)
data class AudioQueueItem(val uri: String, val title: String?, val artist: String?, val playlist: AudioPlaylistDetails? = null)
/** A local text match: typing never requests Spotify search or changes playback. */
fun filterAudioPlaylists(items: List<AudioQueueItem>, filter: String): List<AudioQueueItem> {
    val words = filter.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return if (words.isEmpty()) items else items.filter { item ->
        val fields = listOfNotNull(item.title, item.playlist?.owner, item.playlist?.description)
        words.all { word -> fields.any { it.contains(word, ignoreCase = true) } }
    }
}
data class AudioObservedQueue(val revision: String?, val contextUri: String?, val observedAtEpochMs: Long,
    val previous: List<AudioQueueItem>, val next: List<AudioQueueItem>)
data class AudioMediaMetadata(val receiverSessionId: String? = null, val observedAtEpochMs: Long? = null,
    val available: Boolean = false, val trackUri: String? = null, val artwork: AudioArtworkDescriptor? = null,
    val queue: AudioObservedQueue? = null, val status: String = "Media unavailable",
    val navigationAvailable: Boolean = false)
enum class AudioBrowseKind { PLAYLISTS, PLAYLIST, LIKED, SEARCH }
data class AudioBrowseQuery(val kind: AudioBrowseKind, val offset: Int = 0, val limit: Int = 20,
    val uri: String? = null, val text: String? = null)
data class AudioBrowsePage(val receiverSessionId: String, val offset: Int, val revision: String?,
    val items: List<AudioQueueItem>, val nextOffset: Int?, val complete: Boolean, val unresolvedPages: Boolean, val collectionRevision: String? = null)
data class AudioBrowseResult(val query: AudioBrowseQuery? = null, val page: AudioBrowsePage? = null,
    val loading: Boolean = false, val status: String = "Choose a collection")

data class AudioMediaSelection(val requestId: String, val receiverSessionId: String, val queueRevision: String,
    val index: Int, val uri: String, val query: AudioBrowseQuery? = null, val pageRevision: String? = null, val playlistShuffle: Boolean? = null)
enum class AudioMediaSelectionStatus { IDLE, PENDING, VERIFIED, REJECTED, UNKNOWN }
data class AudioMediaSelectionResult(val requestId: String? = null, val uri: String? = null,
    val status: AudioMediaSelectionStatus = AudioMediaSelectionStatus.IDLE, val message: String = "")
private object UnsupportedMediaSelection { val feed = MutableSourceFeed(AudioMediaSelectionResult()) }

private object UnsupportedPlaylistArtwork { val feed = MutableSourceFeed<Map<String, AudioPlaylistArtworkBytes>>(emptyMap()) }
