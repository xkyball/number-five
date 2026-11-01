package com.no5.helm.network

import com.no5.domain.*
import com.no5.helm.audioUpcoming
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** An external Jam edit is observable without requiring a current-title change. */
class AudioQueueUpdateTest {
    private val now=1_800_000_000_000L
    private val track="spotify:track:4uLU6hMCjMI75M1A2tKUQC"
    private fun body(revision:String,titles:List<String>)=JSONObject().apply {
        put("version",1);put("kind","spotify-media");put("receiverSessionId","receiver");put("available",true);put("observedAtEpochMs",now)
        put("track",JSONObject().put("id",track.substringAfterLast(':')).put("uri",track).put("title","Current unchanged title")
            .put("artists",JSONArray().put("Current artist")).put("album",JSONObject.NULL).put("durationMs",100_000))
        put("artwork",JSONObject.NULL)
        put("queue",JSONObject().put("revision",revision).put("contextUri",JSONObject.NULL).put("observedAtEpochMs",now)
            .put("previous",JSONArray()).put("next",JSONArray().apply {
                for(title in titles)put(JSONObject().put("uri",track).put("title",title).put("artist","Observed artist"))
            }).put("complete",false))
    }.toString()
    private class Scheduler:PiPollScheduler {
        override fun schedule(intervalMs:Long,poll:()->Unit)=SourceSubscription{}
    }
    @Test fun pollingPublishesReorderedNextSongsWhileCurrentTrackAndArtworkStayTheSame() {
        var value=body("before",listOf("First","Second","Third"))
        val reads=mutableListOf<String>()
        val transport=object:PiHttpTransport {
            override fun get(path:String):String {reads+=path;return value}
            override fun close()=Unit
        }
        val source=NetworkPiAudioMedia(transport,transport,{now},Scheduler(),Scheduler())
        var updates=0
        val subscription=source.metadata.subscribe{updates++}
        source.poll()
        val old=source.metadata.value
        assertEquals(listOf("First","Second"),audioUpcoming(old,now).rows.map{it.title})
        value=body("after",listOf("Third","First","Second"))
        source.poll()
        val next=source.metadata.value
        assertEquals(old.trackUri,next.trackUri)
        assertEquals(old.artwork,next.artwork)
        assertNotEquals(old.queue!!.revision,next.queue!!.revision)
        assertEquals(listOf("Third","First"),audioUpcoming(next,now).rows.map{it.title})
        assertTrue(updates>=2)
        assertEquals(listOf("/v1/audio/navigation","/v1/audio/navigation"),reads)
        subscription.close();source.close()
    }
}
