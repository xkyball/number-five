package com.no5.preparation.ui

import org.junit.Assert.*
import org.junit.Test

class AdapterContractsTest {
    @Test fun frozenSnapshotsCannotBeMutatedThroughInputOrDelegatedViews() {
        val input = mutableListOf("a", "b")
        val snapshot = FrozenList.copyOf(input)
        input.clear()
        assertEquals(listOf("a", "b"), snapshot.toList())
        val iterator = snapshot.iterator() as MutableIterator<String>
        iterator.next()
        assertThrows(UnsupportedOperationException::class.java) { iterator.remove() }
        assertThrows(UnsupportedOperationException::class.java) { (snapshot.subList(0, 1) as MutableList<String>).clear() }
        assertEquals(2, snapshot.size)
    }
    @Test fun projectionsRejectOldFrameResizeAndRendererSession() {
        val frame = ViewportSnapshot(1, 2000, 846, "session-a/camera-a")
        val batch = ProjectionBatch(frame, frozenListOf(ProjectedPoint("x", ScreenPixel(4f, 8f))))
        assertTrue(batch.matches(frame))
        assertFalse(batch.matches(frame.copy(revision = 2)))
        assertFalse(batch.matches(frame.copy(heightPx = 1116)))
        assertFalse(batch.matches(frame.copy(cameraToken = "session-b/camera-a")))
        assertFalse(batch.matches(null))
    }
    @Test fun invalidGeographyNeverEntersContract() {
        assertThrows(IllegalArgumentException::class.java) { Wgs84(Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Wgs84(91.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Wgs84(0.0, 181.0) }
    }
}
