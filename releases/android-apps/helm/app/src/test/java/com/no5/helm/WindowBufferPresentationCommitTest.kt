package com.no5.helm

import android.os.Looper
import android.view.TextureView
import no5.sonar.windowrecovery.WindowBufferPresentation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Window callbacks may arrive after the UI has already recorded another draw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class WindowBufferPresentationCommitTest {
    private class HardwareTexture : TextureView(RuntimeEnvironment.getApplication()) {
        override fun isHardwareAccelerated() = true
        override fun isAvailable() = true
    }
    private class Harness : AutoCloseable {
        val view = HardwareTexture()
        val presented = mutableListOf<Long>()
        val discarded = mutableListOf<Long>()
        val binding = WindowBufferPresentation(view, object : WindowBufferPresentation.Listener {
            override fun viewportReady() = Unit
            override fun presented(ticket: Long) { presented += ticket }
            override fun discarded(ticket: Long) { discarded += ticket }
        }, true)
        fun draw(record: Long? = null): List<Runnable> {
            ReflectionHelpers.callInstanceMethod<Boolean>(binding, "beforeDraw")
            record?.let(binding::compositeRecorded)
            return ReflectionHelpers.callInstanceMethod<ArrayList<Runnable>>(
                view.viewTreeObserver, "captureFrameCommitCallbacks") ?: emptyList()
        }
        fun submitted(): Long {
            draw().forEach(Runnable::run)
            val ticket = binding.beginSubmission()
            assertTrue(ticket > 0)
            binding.completeSubmission(ticket)
            shadowOf(Looper.getMainLooper()).idle()
            binding.textureUpdated()
            return ticket
        }
        override fun close() = binding.close()
    }
    @Test fun firstMatchingCommitRemainsValidAfterAnotherDrawHasBeenRecorded() {
        Harness().use { h ->
            val ticket = h.submitted()
            val firstFrame = h.draw(ticket)
            val nextFrame = h.draw(ticket)
            assertTrue(h.presented.isEmpty())
            firstFrame.forEach(Runnable::run)
            assertEquals(listOf(ticket), h.presented)
            nextFrame.forEach(Runnable::run)
            assertEquals("each submitted buffer is acknowledged once", listOf(ticket), h.presented)
        }
    }
    @Test fun aLaterMatchingDrawCannotValidateAnEarlierFrameWithoutThatBuffer() {
        Harness().use { h ->
            val ticket = h.submitted()
            val unrelatedFrame = h.draw()
            val matchingFrame = h.draw(ticket)
            unrelatedFrame.forEach(Runnable::run)
            assertTrue(h.presented.isEmpty())
            matchingFrame.forEach(Runnable::run)
            assertEquals(listOf(ticket), h.presented)
        }
    }
    @Test fun aCommitFromAClosedBindingCannotAcknowledgeAReplacedView() {
        val h = Harness()
        val ticket = h.submitted()
        val frame = h.draw(ticket)
        h.close()
        frame.forEach(Runnable::run)
        assertTrue(h.presented.isEmpty())
    }
    @Test fun aCommitFromAFailedSubmissionCannotAcknowledgeItsReplacement() {
        Harness().use { h ->
            val old = h.submitted()
            val oldFrame = h.draw(old)
            h.binding.failedSubmission(old)
            val replacement = h.binding.beginSubmission()
            h.binding.completeSubmission(replacement)
            shadowOf(Looper.getMainLooper()).idle()
            h.binding.textureUpdated()
            val replacementFrame = h.draw(replacement)
            oldFrame.forEach(Runnable::run)
            assertTrue(h.presented.isEmpty())
            replacementFrame.forEach(Runnable::run)
            assertEquals(listOf(replacement), h.presented)
        }
    }
    @Test fun viewportChangeDrainsTheOldBufferWithoutClaimingNewViewportPixels() {
        Harness().use { h ->
            val ticket = h.submitted()
            val frame = h.draw(ticket)
            h.binding.viewportChanged()
            frame.forEach(Runnable::run)
            assertTrue(h.presented.isEmpty())
            assertEquals(listOf(ticket), h.discarded)
            assertTrue(h.binding.ready())
        }
    }
    @Test fun destroyedSurfaceRejectsItsOutstandingCommit() {
        Harness().use { h ->
            val ticket = h.submitted()
            val frame = h.draw(ticket)
            h.binding.surfaceDestroyed()
            frame.forEach(Runnable::run)
            assertTrue(h.presented.isEmpty())
            assertFalse(h.binding.ready())
        }
    }
}
