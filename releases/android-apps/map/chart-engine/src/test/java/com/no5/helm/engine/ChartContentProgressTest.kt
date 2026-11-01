package com.no5.helm.engine
import org.junit.Assert.*
import org.junit.Test

class ChartContentProgressTest {
    @Test fun continuousCameraChangesCannotPostponeFullDetailCheckpoint() {
        val policy = ChartContentProgress(600, 6)
        assertFalse(policy.useReuse(true, 0)); policy.completed(true, 100)
        for (i in 1..6) { assertTrue(policy.useReuse(true, 100L + i)); policy.completed(false, 100L + i) }
        assertFalse(policy.useReuse(true, 107)); policy.completed(true, 200)
        assertTrue(policy.useReuse(true, 799)); assertFalse(policy.useReuse(true, 800))
    }
    @Test fun idleAlwaysRequestsFullAndInvalidationCannotReuseOldStyle() {
        val policy = ChartContentProgress(); policy.completed(true, 100)
        assertFalse(policy.useReuse(false, 101)); assertTrue(policy.useReuse(true, 101))
        policy.reset(); assertFalse(policy.useReuse(true, 102))
    }
    @Test fun dueTimeAllowsExactlyOneCoverageImageBeforeFull() {
        val policy = ChartContentProgress(600, 6)
        policy.completed(true, 100)
        assertTrue(policy.useReuse(true, 699, coverageGap = true))
        assertFalse(policy.hasCheckpointDebt)
        assertTrue(policy.useReuse(true, 700, coverageGap = true))
        assertTrue(policy.hasCheckpointDebt)
        // Later camera selections, including another gap, must select FULL.
        assertFalse(policy.useReuse(true, 701, coverageGap = true))
        assertFalse(policy.useReuse(true, 800, coverageGap = false))
    }
    @Test fun partialCountAllowsOneLoanAndPartialAckKeepsDebtWithoutNewInput() {
        val policy = ChartContentProgress(600, 2)
        policy.completed(true, 100)
        for (now in listOf(101L, 102L)) {
            assertTrue(policy.useReuse(true, now, coverageGap = true))
            policy.completed(false, now)
        }
        assertTrue(policy.useReuse(true, 103, coverageGap = true))
        policy.completed(false, 104)
        // This is the UI's continuation signal; it requires no new camera.
        assertTrue(policy.hasCheckpointDebt)
        assertFalse(policy.useReuse(true, 104, coverageGap = true))
        assertFalse(policy.useReuse(false, 104, coverageGap = true))
        assertTrue(policy.hasCheckpointDebt)
    }
    @Test fun fullCompletionForgivesDebtAndRestartsNormalInterval() {
        val policy = ChartContentProgress(600, 6)
        policy.completed(true, 100)
        assertTrue(policy.useReuse(true, 700, coverageGap = true))
        policy.completed(false, 710)
        policy.completed(true, 900)
        assertFalse(policy.hasCheckpointDebt)
        assertTrue(policy.useReuse(true, 1499))
        assertFalse(policy.useReuse(true, 1500))
    }
    @Test fun invalidationClearsDebtAndNeedsNewFullBasis() {
        val policy = ChartContentProgress(600, 6)
        policy.completed(true, 100)
        assertTrue(policy.useReuse(true, 700, coverageGap = true))
        policy.reset()
        assertFalse(policy.hasCheckpointDebt)
        assertFalse(policy.useReuse(true, 701, coverageGap = true))
        policy.completed(true, 800)
        assertTrue(policy.useReuse(true, 801, coverageGap = true))
    }
    @Test fun idleAndFirstBasisNeverBorrowCoverageWork() {
        val policy = ChartContentProgress(600, 6)
        assertFalse(policy.useReuse(true, 0, coverageGap = true))
        assertFalse(policy.hasCheckpointDebt)
        policy.completed(true, 100)
        assertFalse(policy.useReuse(false, 700, coverageGap = true))
        assertFalse(policy.hasCheckpointDebt)
    }
    @Test fun falseCoverageFlagPreservesFixedCheckpointPolicy() {
        val defaultFlag = ChartContentProgress(600, 6)
        val explicitFlag = ChartContentProgress(600, 6)
        defaultFlag.completed(true, 100); explicitFlag.completed(true, 100)
        for (now in 101L..106L) {
            assertEquals(defaultFlag.useReuse(true, now), explicitFlag.useReuse(true, now, coverageGap = false))
            defaultFlag.completed(false, now); explicitFlag.completed(false, now)
        }
        assertFalse(defaultFlag.useReuse(true, 107))
        assertFalse(explicitFlag.useReuse(true, 107, coverageGap = false))
        defaultFlag.completed(true, 200); explicitFlag.completed(true, 200)
        for (now in listOf(799L, 800L, 900L))
            assertEquals(defaultFlag.useReuse(true, now), explicitFlag.useReuse(true, now, coverageGap = false))
        assertFalse(defaultFlag.hasCheckpointDebt)
        assertFalse(explicitFlag.hasCheckpointDebt)
    }
}
