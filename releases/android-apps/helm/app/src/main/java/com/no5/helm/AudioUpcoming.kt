package com.no5.helm

import com.no5.domain.AudioMediaMetadata
import com.no5.domain.AudioQueueItem

internal enum class UpcomingQuality { UNAVAILABLE, STALE, OBSERVED, PARTIAL }
internal data class AudioUpcoming(val rows: List<AudioQueueItem>, val quality: UpcomingQuality) {
    val label: String get() = when (quality) {
        UpcomingQuality.UNAVAILABLE -> "Nächste Titel nicht verfügbar"
        UpcomingQuality.STALE -> "Warteschlange veraltet"
        UpcomingQuality.OBSERVED -> "Als Nächstes · beobachteter Ausschnitt"
        UpcomingQuality.PARTIAL -> "Als Nächstes · unvollständiger Ausschnitt"
    }
}

/** Read-only projection of the observed queue, never a playlist prediction.
 * Uses the same 15-second observation bound as AudioMediaDecoder, independently
 * of the current track URI: a Jam participant can reorder the queue mid-track.
 */
internal fun audioUpcoming(metadata: AudioMediaMetadata, nowEpochMs: Long): AudioUpcoming {
    val queue = metadata.queue
    if (!metadata.available || metadata.receiverSessionId.isNullOrBlank() || queue == null)
        return AudioUpcoming(emptyList(), UpcomingQuality.UNAVAILABLE)
    val stamp = metadata.observedAtEpochMs
    if (stamp == null || stamp <= 0 || queue.observedAtEpochMs <= 0 ||
        nowEpochMs.toDouble() - stamp.toDouble() !in -5_000.0..15_000.0 ||
        nowEpochMs.toDouble() - queue.observedAtEpochMs.toDouble() !in -5_000.0..15_000.0)
        return AudioUpcoming(emptyList(), UpcomingQuality.STALE)
    val rows = queue.next.take(2)
    return AudioUpcoming(rows, if (rows.size == 2) UpcomingQuality.OBSERVED else UpcomingQuality.PARTIAL)
}
