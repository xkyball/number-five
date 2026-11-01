package com.no5.domain

import org.junit.Assert.*
import org.junit.Test

class SourceFeedTest {
    @Test fun initialAndChangedSnapshotsArriveUntilSubscriptionCloses() {
        val feed = MutableSourceFeed(1)
        val values = mutableListOf<Int>()
        val subscription = feed.subscribe(values::add)
        assertEquals(listOf(1), values)
        assertTrue(feed.emit(2))
        subscription.close()
        subscription.close()
        assertTrue(feed.emit(3))
        assertEquals(listOf(1, 2), values)
        assertEquals(3, feed.value)
    }

    @Test fun unsubscribeDuringDispatchPreventsRemainingCallback() {
        val feed = MutableSourceFeed(0)
        val values = mutableListOf<Int>()
        var later: SourceSubscription? = null
        feed.subscribe { if (it == 1) later?.close() }
        later = feed.subscribe(values::add)
        feed.emit(1)
        assertEquals(listOf(0), values)
    }

    @Test fun selfUnsubscribeIsSafeAndDoesNotRemoveOtherObservers() {
        val feed = MutableSourceFeed(0)
        val own = mutableListOf<Int>()
        val other = mutableListOf<Int>()
        var subscription: SourceSubscription? = null
        subscription = feed.subscribe { own += it; if (it == 1) subscription?.close() }
        feed.subscribe(other::add)
        feed.emit(1)
        feed.emit(2)
        assertEquals(listOf(0, 1), own)
        assertEquals(listOf(0, 1, 2), other)
    }

    @Test fun closeDuringDispatchDropsQueuedAndRemainingCallbacks() {
        val feed = MutableSourceFeed(0)
        val values = mutableListOf<Int>()
        feed.subscribe { if (it == 1) { feed.emit(2); feed.close() } }
        feed.subscribe(values::add)
        assertTrue(feed.emit(1))
        assertEquals(listOf(0), values)
        assertEquals(1, feed.value)
        assertFalse(feed.emit(3))
        var lateCallback = false
        feed.subscribe { lateCallback = true }.close()
        assertFalse(lateCallback)
        feed.close()
    }

    @Test fun reentrantEmissionsKeepEveryObserverInSnapshotOrder() {
        val feed = MutableSourceFeed(0)
        val first = mutableListOf<Int>()
        val second = mutableListOf<Int>()
        feed.subscribe { first += it; if (it == 1) feed.emit(2) }
        feed.subscribe(second::add)
        feed.emit(1)
        assertEquals(listOf(0, 1, 2), first)
        assertEquals(first, second)
    }

    @Test fun callbackFailureDoesNotRetainFailedObserverOrStarveHealthyObservers() {
        val feed = MutableSourceFeed(0)
        val values = mutableListOf<Int>()
        feed.subscribe { if (it == 1) error("observer failed") }
        feed.subscribe(values::add)
        assertThrows(IllegalStateException::class.java) { feed.emit(1) }
        assertTrue(feed.emit(2))
        assertEquals(listOf(0, 1, 2), values)
    }

    @Test fun failedInitialCallbackIsNotSubscribed() {
        val feed = MutableSourceFeed(0)
        var callbacks = 0
        assertThrows(IllegalStateException::class.java) {
            feed.subscribe { callbacks++; error("initial failed") }
        }
        feed.emit(1)
        assertEquals(1, callbacks)
    }

    @Test fun replacedSourceCannotDeliverAfterItsFeedIsClosed() {
        val oldFeed = MutableSourceFeed("old initial")
        val newFeed = MutableSourceFeed("new initial")
        val observed = mutableListOf<String>()
        oldFeed.subscribe(observed::add)
        oldFeed.close()
        newFeed.subscribe(observed::add)
        assertFalse(oldFeed.emit("late old frame"))
        newFeed.emit("new frame")
        assertEquals(listOf("old initial", "new initial", "new frame"), observed)
    }
}
