package com.no5.helm.engine

import com.no5.helm.chart.ChartBufferTransformMode
import com.no5.helm.chart.ChartContentStage
import org.junit.Assert.*
import org.junit.Test

class ChartPresentationAckProgressTest {
    @Test fun originalCoverageLoanAckProducesFullWithoutNewInput() =
        assertLoanAckCompletesFull(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS)

    @Test fun paddedControlCoverageLoanAckProducesFullWithoutNewInput() =
        assertLoanAckCompletesFull(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_PADDED_CONTROL)

    @Test fun reserveCoverageLoanAckProducesFullWithoutNewInput() =
        assertLoanAckCompletesFull(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE)

    @Test fun nonCoverageModesDoNotResumeAFormerCoverageLoanWithoutInput() {
        val coverageModes = setOf(ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS,
            ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_PADDED_CONTROL,
            ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS_RESERVE)
        for (mode in ChartBufferTransformMode.entries.filterNot { it in coverageModes }) {
            val fixture = LoanAckFixture(mode)
            fixture.acquireAndCommitLoan()
            assertTrue("Debt is retained for $mode", fixture.policy.hasCheckpointDebt)
            assertEquals("No unsolicited job for $mode", 0, fixture.jobs.size)
            assertFalse(fixture.gate.busy())
        }
    }

    @Test fun ordinaryPendingWorkResumesAfterAckInEveryMode() {
        for (mode in ChartBufferTransformMode.entries) {
            assertTrue("Dirty work for $mode", presentationAckNeedsRender(mode, true, false, false))
            assertTrue("Latest camera for $mode", presentationAckNeedsRender(mode, false, true, false))
            assertFalse("No work for $mode", presentationAckNeedsRender(mode, false, false, false))
        }
    }

    private fun assertLoanAckCompletesFull(mode: ChartBufferTransformMode) {
        val fixture = LoanAckFixture(mode)
        // Native completion alone cannot release a slot or admit its next render.
        fixture.commit(1, fixture.loanStamp)
        assertTrue(fixture.gate.busy())
        assertEquals(0, fixture.jobs.size)
        assertTrue(fixture.policy.hasCheckpointDebt)
        fixture.acquireAndCommitLoan()
        assertEquals("The ACK must schedule the owed FULL for $mode", 1, fixture.jobs.size)
        fixture.commit(1, fixture.loanStamp) // Duplicate ACK must not produce another job.
        assertEquals(1, fixture.jobs.size)
        fixture.jobs.removeAt(0).invoke()
        assertEquals(listOf(ChartContentStage.FULL), fixture.renderedStages)
        assertFalse(fixture.policy.hasCheckpointDebt)
        assertEquals(0, fixture.jobs.size)
        assertTrue(fixture.gate.acquired(fixture.generation, 0, fixture.fullStamp))
        fixture.commit(0, fixture.fullStamp)
        assertEquals("FULL completion must not cause a render loop", 0, fixture.jobs.size)
    }

    /** Real presentation/progress gates plus the production ACK admission decision; no JNI/GPU mock claim. */
    private class LoanAckFixture(private val mode: ChartBufferTransformMode) {
        val generation = 5L
        val loanStamp = 710L
        val fullStamp = 900L
        val gate = ChartPresentationGate()
        val policy = ChartContentProgress(600, 6)
        val jobs = mutableListOf<() -> Unit>()
        val renderedStages = mutableListOf<ChartContentStage>()

        init {
            gate.reset(generation)
            policy.completed(full = true, nowNanos = 100)
            assertTrue(gate.reserve(generation, 1, loanStamp))
            assertTrue(policy.useReuse(moving = true, nowNanos = 700, coverageGap = true))
            policy.completed(full = false, nowNanos = loanStamp)
        }

        fun acquireAndCommitLoan() {
            assertTrue(gate.acquired(generation, 1, loanStamp))
            commit(1, loanStamp)
        }

        fun commit(slot: Int, stamp: Long) {
            if (!gate.committed(slot, stamp)) return
            // There is deliberately neither new camera input nor dirty work after the loan.
            if (presentationAckNeedsRender(mode, false, false, policy.hasCheckpointDebt)) jobs += {
                val writable = gate.writableSlot(generation)
                assertEquals(0, writable)
                assertTrue(gate.reserve(generation, writable!!, fullStamp))
                val reuse = policy.useReuse(moving = true, nowNanos = 711, coverageGap = true)
                renderedStages += if (reuse) ChartContentStage.REUSED_DETAILS_WITH_BASE else ChartContentStage.FULL
                policy.completed(full = !reuse, nowNanos = fullStamp)
            }
        }
    }
}
