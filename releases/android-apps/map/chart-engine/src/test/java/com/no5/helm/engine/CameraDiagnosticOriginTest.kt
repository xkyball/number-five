package com.no5.helm.engine

import com.no5.helm.chart.RendererInputOrigin
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CameraDiagnosticOriginTest {
    private fun input(buffer: RendererLiveInputBuffer) = buffer.input(100, 200, 202, 101, 2, 7, -1, 1,
        intArrayOf(7), floatArrayOf(10f, 20f), RendererInputOrigin.VIEW_INJECTED)

    @Test fun originalThreadCapturesImmutableOriginAndAnotherThreadDoesNotReadItsThreadLocal() {
        val buffer = RendererLiveInputBuffer("run")
        val id = input(buffer)
        val origin = buffer.withInput(id) { CameraDiagnosticOrigin.capture(buffer, 3, 7) }
        assertNotNull(origin)
        val fromWorker = AtomicReference<CameraDiagnosticOrigin?>()
        val worker = thread {
            assertEquals(0L, buffer.currentInput())
            assertNull(CameraDiagnosticOrigin.capture(buffer, 3, 7))
            fromWorker.set(origin)
        }
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertSame(origin, fromWorker.get())
        assertEquals(id, fromWorker.get()!!.inputId)
        assertTrue(fromWorker.get()!!.matches(buffer, 3, 7))
    }

    @Test fun sameRunAndNumericIdCannotRelabelAnotherCaptureInstance() {
        val old = RendererLiveInputBuffer("reused-run")
        val current = RendererLiveInputBuffer("reused-run")
        assertEquals(input(old), input(current))
        val origin = old.withInput(1) { CameraDiagnosticOrigin.capture(old, 3, 7) }!!
        assertTrue(origin.matches(old, 3, 7))
        assertFalse(origin.matches(current, 3, 7))
        assertFalse(origin.matches(null, 3, 7))
    }

    @Test fun closedCaptureAndWrongSourceOrSurfaceNeverMatch() {
        val buffer = RendererLiveInputBuffer("run")
        val origin = buffer.withInput(input(buffer)) { CameraDiagnosticOrigin.capture(buffer, 3, 7) }!!
        assertFalse(origin.matches(buffer, 4, 7))
        assertFalse(origin.matches(buffer, 3, 8))
        buffer.finishReport()
        assertFalse(origin.matches(buffer, 3, 7))
        assertNull(buffer.withInput(1) { CameraDiagnosticOrigin.capture(buffer, 3, 7) })
    }

    @Test fun noCaptureNoInputOrOverflowHasNoOrigin() {
        assertNull(CameraDiagnosticOrigin.capture(null, 3, 7))
        val buffer = RendererLiveInputBuffer("bounded", maxInputs = 1)
        assertNull(CameraDiagnosticOrigin.capture(buffer, 3, 7))
        assertNull(buffer.withInput(1) { CameraDiagnosticOrigin.capture(buffer, 3, 7) })
        assertNotNull(buffer.withInput(input(buffer)) { CameraDiagnosticOrigin.capture(buffer, 3, 7) })
        assertNull(buffer.withInput(input(buffer)) { CameraDiagnosticOrigin.capture(buffer, 3, 7) })
        assertNull(buffer.withInput(-1) { CameraDiagnosticOrigin.capture(buffer, 3, 7) })
    }
}
