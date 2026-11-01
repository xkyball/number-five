package com.no5.helm.gpu

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class SonarGpuSnapshotFenceTest {
    @Test fun fullRingContinuesWhenOldestSnapshotRowsExpireDuringPagedUpload(){
        val ring=SonarRingBuffer(8)
        repeat(8){ring.push(SonarFrame(ShortArray(1024),it.toLong(),it*50L))}
        val head=ring.head;val newest=ring.pingIdAt(0)
        val ids=LongArray(8){slot->ring.pingIdAt((ring.head-1-slot+8)%8)}
        // Two arrivals during driver upload: they are retained, never discarded.
        repeat(2){ring.push(SonarFrame(ShortArray(1024),(8+it).toLong(),(8+it)*50L))}
        for(slot in 0..1){val actual=ring.pingIdAt((ring.head-1-slot+8)%8);assertTrue(SonarGpuSnapshotFence.expired(ids[slot],actual,newest));ids[slot]=Long.MIN_VALUE}
        assertEquals(6,SonarGpuSnapshotFence.retainedCount(head,8,ids))
        assertEquals(9L,ring.pingIdAt(0));assertEquals(8,ring.count);assertEquals(2L,ring.pingIdAt(7))
        // Next delta fills the two new slots, restoring the entire retained window.
        ids[0]=8;ids[1]=9;assertEquals(8,SonarGpuSnapshotFence.retainedCount(ring.head,8,ids))
    }
    @Test fun mismatchWithinTheSnapshotOrUnknownUploadIsNotSilentlyDiscarded(){
        assertFalse(SonarGpuSnapshotFence.expired(4,5,7));assertFalse(SonarGpuSnapshotFence.expired(4,3,7))
        assertFalse(SonarGpuSnapshotFence.expired(Long.MIN_VALUE,9,7))
        val ids=longArrayOf(4,5,6,Long.MIN_VALUE);assertEquals(0,SonarGpuSnapshotFence.retainedCount(0,4,ids))
    }
}
