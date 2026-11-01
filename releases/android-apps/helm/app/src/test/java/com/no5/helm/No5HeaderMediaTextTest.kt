package com.no5.helm

import com.no5.header.artwork.No5HeaderMediaText
import com.no5.ui.UiStateContract
import com.no5.ui.UiStateSnapshot
import org.junit.Assert.*
import org.junit.Test

class No5HeaderMediaTextTest {
    private fun state(connected: Boolean = true, playback: String = "PLAYING") = UiStateSnapshot(
        "de", 12, connected, UiStateSnapshot.NowPlaying("Original song title", "Original artist",
            playback, 7, 123_456, 1000, true))

    @Test fun titleArtistAndPlaybackComeFromTheSameObservedSnapshot() {
        val snapshot = state(playback = "PAUSED")
        val shown = No5HeaderMediaText.from(snapshot, 1001)
        assertEquals("Original song title", shown.title)
        assertEquals("Original artist · Pausiert", shown.detail)
        assertTrue(shown.accessibilityDescription.contains("Original song title"))
        assertTrue(shown.accessibilityDescription.contains("Original artist"))
        assertEquals(7, shown.sourceGeneration)
        assertEquals(123_456, snapshot.media.observedAtEpochMs)
        assertEquals(1000, snapshot.media.receivedAtElapsedMs)
    }

    @Test fun readingAnExpiredSnapshotCannotMakeItsTitleCurrentAgain() {
        val snapshot = state()
        val shown = No5HeaderMediaText.from(snapshot, 1000 + UiStateContract.MEDIA_FRESH_MS)
        assertEquals("Original song title", shown.title)
        assertEquals("Original artist · Veraltet", shown.detail)
        assertFalse(shown.detail.contains("Wiedergabe"))
        assertEquals(123_456, snapshot.media.observedAtEpochMs)
    }

    @Test fun disconnectedBrokerDoesNotPresentItsPriorSourceAsCurrentMusic() {
        val shown = No5HeaderMediaText.from(state(connected = false), 1001)
        assertEquals("Keine Wiedergabe", shown.title)
        assertEquals("Nicht verfügbar", shown.detail)
        assertFalse(shown.accessibilityDescription.contains("Original song title"))
        assertFalse(shown.accessibilityDescription.contains("Original artist"))
    }

    @Test fun anEmptySourceClearsTitleAndArtistInsteadOfRetainingItsPredecessor() {
        val shown = No5HeaderMediaText.from(UiStateSnapshot("de", 13, true,
            UiStateSnapshot.NowPlaying.empty()), 1001)
        assertEquals("Keine Wiedergabe", shown.title)
        assertEquals("Nicht verfügbar", shown.detail)
    }
}
