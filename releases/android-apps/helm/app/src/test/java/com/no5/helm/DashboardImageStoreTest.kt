package com.no5.helm

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DashboardImageStoreTest {
    @Test fun mainThreadCannotStartDiskOrDecodeWork() {
        val decodes = AtomicInteger()
        val store = DashboardImageStore { decodes.incrementAndGet(); null }
        assertThrows(IllegalStateException::class.java) { store.load() }
        assertEquals(0, decodes.get())
    }
    @Test fun simultaneousPageLoadsDecodeOnceAndReuseTheSameBitmap() {
        val decodes = AtomicInteger()
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        val store = DashboardImageStore { decodes.incrementAndGet(); bitmap }
        val worker = Executors.newFixedThreadPool(2)
        try {
            val a = worker.submit<Bitmap?> { store.load() }
            val b = worker.submit<Bitmap?> { store.load() }
            assertSame(bitmap, a.get(2, TimeUnit.SECONDS))
            assertSame(bitmap, b.get(2, TimeUnit.SECONDS))
            assertEquals(1, decodes.get())
            assertFalse(bitmap.isRecycled)
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS)); bitmap.recycle() }
    }
    @Test fun failedArtworkIsNotDecodedAgainOnEveryNavigation() {
        val decodes = AtomicInteger()
        val store = DashboardImageStore { decodes.incrementAndGet(); throw IllegalStateException("missing asset") }
        val worker = Executors.newSingleThreadExecutor()
        try {
            repeat(3) { assertNull(worker.submit<Bitmap?> { store.load() }.get(2, TimeUnit.SECONDS)) }
            assertEquals(1, decodes.get())
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS)) }
    }
}
