package com.no5.helm

import com.no5.domain.*
import org.junit.Assert.*
import org.junit.Test

class AudioUpcomingTest {
    private val now=100_000L
    private fun metadata(rows:List<AudioQueueItem>,queueStamp:Long=now)=AudioMediaMetadata("receiver",now,true,"same-current-track",
        queue=AudioObservedQueue("revision",null,queueStamp,emptyList(),rows))
    private val one=AudioQueueItem("one","First",null)
    private val two=AudioQueueItem("two","Second","Artist")
    private val three=AudioQueueItem("three","Third",null)
    @Test fun onlyTwoActuallyObservedRowsAreProjectedInTheirCurrentOrder() {
        val value=metadata(listOf(one,two,three))
        assertEquals(listOf(one,two),audioUpcoming(value,now).rows)
        assertEquals(UpcomingQuality.OBSERVED,audioUpcoming(value,now).quality)
        val jam=value.copy(queue=value.queue!!.copy(revision="new",next=listOf(two,three,one)))
        assertEquals(value.trackUri,jam.trackUri)
        assertEquals(listOf(two,three),audioUpcoming(jam,now).rows)
    }
    @Test fun oneOrNoObservedRowsRemainExplicitlyPartial() {
        assertEquals(UpcomingQuality.PARTIAL,audioUpcoming(metadata(listOf(one)),now).quality)
        assertEquals(listOf(one),audioUpcoming(metadata(listOf(one)),now).rows)
        assertEquals(UpcomingQuality.PARTIAL,audioUpcoming(metadata(emptyList()),now).quality)
        assertTrue(audioUpcoming(metadata(emptyList()),now).rows.isEmpty())
    }
    @Test fun sourceAndReceiverLossDoNotRetainAnotherSessionsNextSongs() {
        val value=metadata(listOf(one,two))
        for(invalid in listOf(value.copy(available=false),value.copy(receiverSessionId=null),value.copy(queue=null))) {
            assertEquals(UpcomingQuality.UNAVAILABLE,audioUpcoming(invalid,now).quality)
            assertTrue(audioUpcoming(invalid,now).rows.isEmpty())
        }
    }
    @Test fun queueObservationExpiresIndependentlyOfCurrentPublication() {
        val oldQueue=metadata(listOf(one,two),now-15_001)
        assertEquals(UpcomingQuality.STALE,audioUpcoming(oldQueue,now).quality)
        assertTrue(audioUpcoming(oldQueue,now).rows.isEmpty())
        assertEquals(UpcomingQuality.STALE,audioUpcoming(metadata(listOf(one,two)).copy(observedAtEpochMs=now-15_001),now).quality)
        assertEquals(UpcomingQuality.STALE,audioUpcoming(metadata(listOf(one,two),now+5_001),now).quality)
        assertEquals(UpcomingQuality.OBSERVED,audioUpcoming(metadata(listOf(one,two),now-15_000),now).quality)
    }
    @Test fun missingTrackTitleIsNotGuessedOrSkipped() {
        val unknown=one.copy(title=null,artist=null)
        assertEquals(listOf(unknown,two),audioUpcoming(metadata(listOf(unknown,two)),now).rows)
    }
}
