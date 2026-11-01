package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*

class AudioMediaTest {
    private val stamp = 1_800_000_000_000L
    private val uri = "spotify:track:4uLU6hMCjMI75M1A2tKUQC"
    private val id = "a".repeat(64)
    private fun media(receiver: String = "session-a", track: String = uri, image: Boolean = true): String = """
        {"version":1,"kind":"spotify-media","receiverSessionId":"$receiver","available":true,"observedAtEpochMs":$stamp,
         "track":{"id":"${track.substringAfterLast(':')}","uri":"$track","title":"Actual track","artists":["Artist"],"album":null,"durationMs":100000},
         "artwork":${if(image) """{"id":"$id","path":"/v1/audio/artwork/$id","width":1,"height":1}""" else "null"},
         "queue":{"revision":null,"contextUri":null,"observedAtEpochMs":$stamp,"previous":[],"next":[],"complete":false}}
    """.trimIndent()
    private class Scheduler : PiPollScheduler {
        var stopped = false
        override fun schedule(intervalMs: Long, poll: () -> Unit) = SourceSubscription { stopped = true }
    }
    private class Transport(var response: String) : PiHttpTransport {
        var onGet: ((String) -> String)? = null
        var onImage: (() -> ByteArray)? = null
        var gets = 0; var downloads = 0; var closed = false
        override fun get(path: String): String { gets++; return onGet?.invoke(path) ?: response }
        override fun getBytes(path: String): ByteArray { downloads++; return onImage?.invoke() ?: ByteArray(20) }
        override fun close() { closed = true }
    }
    @Test fun decoderRejectsExternalPathsDuplicateKeysAndStaleObservations() {
        assertEquals(uri,AudioMediaDecoder.metadata(media(),stamp).trackUri)
        val root = JSONObject(media()); root.getJSONObject("artwork").put("path","https://evil/key")
        assertFailsWith<PiProtocolException> { AudioMediaDecoder.metadata(root.toString(),stamp) }
        assertFailsWith<PiProtocolException> { AudioMediaDecoder.metadata(media().replace("\"version\":1", "\"version\":1,\"version\":1"),stamp) }
        assertFailsWith<PiProtocolException> { AudioMediaDecoder.metadata(media(),stamp+15001) }
        root.getJSONObject("artwork").put("path","/v1/audio/artwork/$id").put("width",true)
        assertFailsWith<PiProtocolException> { AudioMediaDecoder.metadata(root.toString(),stamp) }
    }
    @Test fun queueNeverClaimsCompletePlaylistAndOversizedImageIsRejected() {
        val root = JSONObject(media()); root.getJSONObject("queue").put("complete",true)
        assertFailsWith<PiProtocolException> { AudioMediaDecoder.metadata(root.toString(),stamp) }
        root.getJSONObject("queue").put("complete",false); root.getJSONObject("artwork").put("width",2048).put("height",2048)
        assertFailsWith<PiProtocolException> { AudioMediaDecoder.metadata(root.toString(),stamp) }
    }
    @Test fun imageDownloadsOnceAcrossUnrelatedPollsAndOldReceiverCannotReturn() {
        val reads=Transport(media()); val images=Transport(""); val source=NetworkPiAudioMedia(reads,images,{stamp},Scheduler(),Scheduler())
        source.poll(); source.download(); source.poll(); source.download()
        assertEquals(1,images.downloads); assertNotNull(source.artwork.value)
        reads.response=media("session-b"); source.poll(); assertNull(source.artwork.value)
        reads.response=media(); source.poll(); assertEquals("session-b",source.metadata.value.receiverSessionId)
        source.close(); assertTrue(reads.closed&&images.closed)
    }
    @Test fun delayedImageFromPreviousTrackIsDropped() {
        val reads=Transport(media()); val images=Transport(""); val source=NetworkPiAudioMedia(reads,images,{stamp},Scheduler(),Scheduler())
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        images.onImage={entered.countDown(); assertTrue(release.await(2,TimeUnit.SECONDS)); ByteArray(20)}
        source.poll(); val worker=thread {source.download()}; assertTrue(entered.await(2,TimeUnit.SECONDS))
        reads.response=media(track="spotify:track:5uLU6hMCjMI75M1A2tKUQC"); source.poll(); release.countDown(); worker.join(2000)
        assertNull(source.artwork.value); source.close()
    }
    @Test fun cancelledBrowseCannotPublishLateReplyOrIssueCommand() {
        val reads=Transport(media(image=false)); val images=Transport(""); val source=NetworkPiAudioMedia(reads,images,{stamp},Scheduler(),Scheduler())
        source.poll(); source.browse(AudioBrowseQuery(AudioBrowseKind.PLAYLISTS))
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        reads.onGet={ path -> if(path.startsWith("/v1/audio/browse?")) {
            assertTrue(path.contains("receiverSessionId=session-a")); entered.countDown(); assertTrue(release.await(2,TimeUnit.SECONDS))
            """{"version":1,"kind":"spotify-browse","receiverSessionId":"session-a","observedAtEpochMs":$stamp,"page":{"offset":0,"limit":20,"revision":null,"items":[],"nextOffset":null,"complete":true}}"""
        } else media(image=false) }
        val worker=thread {source.poll()}; assertTrue(entered.await(2,TimeUnit.SECONDS)); source.cancelBrowse(); release.countDown(); worker.join(2000)
        assertNull(source.browsing.value.page); assertFalse(source.browsing.value.loading); source.close()
    }
    @Test fun closeDuringReadRejectsLateMetadataAndCancelsBothWorkers() {
        val reads=Transport(media()); val images=Transport(""); val a=Scheduler(); val b=Scheduler(); val source=NetworkPiAudioMedia(reads,images,{stamp},a,b)
        reads.onGet={source.close();media()}; source.poll()
        assertFalse(source.metadata.value.available); assertTrue(a.stopped&&b.stopped&&reads.closed&&images.closed)
    }
    @Test fun searchAndPlaylistInputsStayInsideReadContract() {
        assertFailsWith<IllegalArgumentException> { AudioMediaDecoder.validate(AudioBrowseQuery(AudioBrowseKind.SEARCH,text="bad\ntext")) }
        assertFailsWith<IllegalArgumentException> { AudioMediaDecoder.validate(AudioBrowseQuery(AudioBrowseKind.PLAYLIST,uri="https://evil")) }
        assertFailsWith<IllegalArgumentException> { AudioMediaDecoder.validate(AudioBrowseQuery(AudioBrowseKind.LIKED,offset=5001)) }
    }
    private val revision = "01234567-89ab-4cde-8fab-0123456789ab"
    private fun navigation() = JSONObject(media(image=false)).apply { getJSONObject("queue").put("revision",revision) }.toString()
    @Test fun selectionTimeoutIsConsumedOnceAndNeverReplaysOnPoll() {
        val reads=Transport(navigation()); var writes=0
        val commands=object:PiHttpTransport {
            override fun get(path:String)=error("No read")
            override fun post(path:String,body:String):String {writes++;assertEquals("/v1/audio/select",path);throw java.net.SocketTimeoutException()}
            override fun close()=Unit
        }
        val source=NetworkPiAudioMedia(reads,Transport(""),{stamp},Scheduler(),Scheduler(),commands,Scheduler())
        source.poll(); assertTrue(source.metadata.value.navigationAvailable)
        source.select(AudioMediaSelection("select:1","session-a",revision,0,uri))
        assertEquals(AudioMediaSelectionStatus.PENDING,source.selection.value.status)
        source.selectOnce(); source.poll();source.selectOnce();source.selectOnce()
        assertEquals(1,writes);assertEquals(AudioMediaSelectionStatus.UNKNOWN,source.selection.value.status);source.close()
    }
    @Test fun queuedSelectionIsRejectedBeforeWriteWhenSessionChanges() {
        val reads=Transport(navigation());var writes=0
        val commands=object:PiHttpTransport {
            override fun get(path:String)=error("No read")
            override fun post(path:String,body:String):String {writes++;error("A stale request must not write")}
            override fun close()=Unit
        }
        val source=NetworkPiAudioMedia(reads,Transport(""),{stamp},Scheduler(),Scheduler(),commands,Scheduler())
        source.poll();source.select(AudioMediaSelection("select:1","session-a",revision,0,uri))
        reads.response=navigation().replace("session-a","session-b");source.poll();source.selectOnce()
        assertEquals(0,writes);assertEquals(AudioMediaSelectionStatus.UNKNOWN,source.selection.value.status);source.close()
    }
    @Test fun oldGatewayFallbackReadsWithoutOfferingSelection() {
        val reads=Transport(media());reads.onGet={path->if(path=="/v1/audio/navigation")throw PiHttpStatusException(404)else media()}
        val source=NetworkPiAudioMedia(reads,Transport(""),{stamp},Scheduler(),Scheduler())
        source.poll();assertTrue(source.metadata.value.available);assertFalse(source.metadata.value.navigationAvailable);source.close()
    }
    @Test fun verifiedReceiptRequiresMatchingRequestSessionTargetAndNewPlayerSequence() {
        val request=AudioMediaSelection("select:1","session-a",revision,1,uri)
        val ack=JSONObject().put("version",1).put("kind","spotify-selection-ack").put("requestId",request.requestId)
            .put("receiverSessionId",request.receiverSessionId).put("status","VERIFIED").put("message","Observed player")
            .put("confirmation",JSONObject().put("version",1).put("kind","spotify-selection-readback").put("receiverSessionId",request.receiverSessionId)
                .put("trackUri",uri).put("entryId","second-occurrence").put("observedAtEpochMs",stamp).put("playerSequence",2))
        assertEquals(AudioMediaSelectionStatus.VERIFIED,AudioMediaDecoder.selection(ack.toString(),request,stamp).status)
        ack.getJSONObject("confirmation").put("playerSequence",0)
        assertFailsWith<PiProtocolException>{AudioMediaDecoder.selection(ack.toString(),request,stamp)}
        ack.getJSONObject("confirmation").put("playerSequence",2).put("trackUri","spotify:track:5uLU6hMCjMI75M1A2tKUQC")
        assertFailsWith<PiProtocolException>{AudioMediaDecoder.selection(ack.toString(),request,stamp)}
        val collection=request.copy(query=AudioBrowseQuery(AudioBrowseKind.SEARCH,text="Real query"),pageRevision="no5-page")
        val body=JSONObject(AudioMediaDecoder.selectionBody(collection)).getJSONObject("selection").getJSONObject("query")
        assertEquals("SEARCH",body.getString("type"));assertEquals("Real query",body.getString("query"))
    }

    private fun playlistPage(offset:Int,next:Int?,revision:String="root-a",details:Boolean=false):String {
        val row=JSONObject().put("uri","spotify:playlist:"+(if(offset==0)"4"else"5")+"uLU6hMCjMI75M1A2tKUQC").put("title","Playlist $offset").put("artist",JSONObject.NULL)
        if(details)row.put("playlist",JSONObject().put("owner","Owner").put("description","Actual description").put("trackCount",42).put("collaborative",false)
            .put("artwork",JSONObject().put("id",id).put("path","/v1/audio/playlist-artwork/$id")))
        return JSONObject().put("version",1).put("kind","spotify-browse").put("receiverSessionId","session-a").put("observedAtEpochMs",stamp)
            .put("page",JSONObject().put("offset",offset).put("limit",20).put("revision","page-$offset").put("collectionRevision",revision)
                .put("items",org.json.JSONArray().put(row)).put("nextOffset",next?:JSONObject.NULL).put("complete",next==null)).toString()
    }
    @Test fun rootlistLoadsFurtherPagesAndLocalFilteringNeverUsesSearchOrWrites() {
        val reads=Transport(media(image=false));val paths=mutableListOf<String>()
        reads.onGet={path->paths+=path;if(path.contains("/browse?"))playlistPage(if(path.contains("offset=20"))20 else 0,if(path.contains("offset=20"))null else 20)else media(image=false)}
        val source=NetworkPiAudioMedia(reads,Transport(""),{stamp},Scheduler(),Scheduler())
        source.poll();source.browse(AudioBrowseQuery(AudioBrowseKind.PLAYLISTS));source.poll()
        assertTrue(source.browsing.value.loading);assertEquals(1,source.browsing.value.page?.items?.size)
        source.poll();assertFalse(source.browsing.value.loading);assertTrue(source.browsing.value.page?.complete==true)
        val all=requireNotNull(source.browsing.value.page).items;assertEquals(2,all.size)
        val before=paths.size;assertEquals(1,filterAudioPlaylists(all,"20").size);assertEquals(before,paths.size)
        assertEquals(2,paths.count{it.contains("type=PLAYLISTS")});assertFalse(paths.any{it.contains("type=SEARCH")});source.close()
    }
    @Test fun changedRootlistRevisionRetainsAnExplicitlyPartialListWithoutMixingPages() {
        val reads=Transport(media(image=false));reads.onGet={path->if(path.contains("/browse?")) {
            if(path.contains("offset=20"))playlistPage(20,null,"changed")else playlistPage(0,20)
        }else media(image=false)}
        val source=NetworkPiAudioMedia(reads,Transport(""),{stamp},Scheduler(),Scheduler());source.poll();source.browse(AudioBrowseQuery(AudioBrowseKind.PLAYLISTS));source.poll();source.poll()
        assertFalse(source.browsing.value.loading);assertFalse(source.browsing.value.page?.complete==true);assertTrue(source.browsing.value.page?.unresolvedPages==true)
        assertEquals(1,source.browsing.value.page?.items?.size);source.close()
    }
    @Test fun playlistDetailsAreStrictAndOnlyObservedSameSessionCoversCanDownload() {
        val reads=Transport(media(image=false));val images=Transport("");reads.onGet={path->if(path.contains("/browse?"))playlistPage(0,null,details=true)else media(image=false)}
        val source=NetworkPiAudioMedia(reads,images,{stamp},Scheduler(),Scheduler());source.poll();source.browse(AudioBrowseQuery(AudioBrowseKind.PLAYLISTS));source.poll()
        val row=requireNotNull(source.browsing.value.page).items.single();assertEquals(42,row.playlist?.trackCount)
        source.requestPlaylistArtwork(listOf(row.copy(uri="spotify:playlist:6uLU6hMCjMI75M1A2tKUQC")));source.download();assertEquals(0,images.downloads)
        source.requestPlaylistArtwork(listOf(row));source.download();assertEquals(1,images.downloads);assertEquals(row.uri,source.playlistArtwork.value[row.uri]?.uri)
        source.requestPlaylistArtwork(listOf(row));source.download();assertEquals(1,images.downloads)
        reads.onGet={media("session-b",image=false)};source.poll();assertTrue(source.playlistArtwork.value.isEmpty());source.close()
        val raw=JSONObject(playlistPage(0,null,details=true));raw.getJSONObject("page").getJSONArray("items").getJSONObject(0).getJSONObject("playlist").getJSONObject("artwork").put("path","https://evil/cover")
        assertFailsWith<PiProtocolException>{AudioMediaDecoder.browse(raw.toString(),"session-a",AudioBrowseQuery(AudioBrowseKind.PLAYLISTS),stamp)}
    }
    @Test fun scrollingBackReloadsAnEvictedCoverWithoutReloadingThePlaylistCollection() {
        val rows=(1..33).map { i ->
            val cover=i.toString(16).padStart(64,'0')
            JSONObject().put("uri","spotify:playlist:"+i.toString().padStart(22,'0')).put("title","Playlist $i").put("artist",JSONObject.NULL)
                .put("playlist",JSONObject().put("owner",JSONObject.NULL).put("description",JSONObject.NULL).put("trackCount",0).put("collaborative",false)
                    .put("artwork",JSONObject().put("id",cover).put("path","/v1/audio/playlist-artwork/$cover")))
        }
        val reads=Transport(media(image=false));val images=Transport("")
        reads.onGet={path->if(path.contains("/browse?")) {
            val offset=if(path.contains("offset=20"))20 else 0
            JSONObject(playlistPage(offset,if(offset==0)20 else null)).apply {
                getJSONObject("page").put("items",org.json.JSONArray(rows.drop(offset).take(20)))
            }.toString()
        } else media(image=false)}
        val source=NetworkPiAudioMedia(reads,images,{stamp},Scheduler(),Scheduler())
        source.poll();source.browse(AudioBrowseQuery(AudioBrowseKind.PLAYLISTS));source.poll();source.poll()
        val all=requireNotNull(source.browsing.value.page).items
        assertEquals(33,all.size)
        all.forEach {source.requestPlaylistArtwork(listOf(it));source.download()}
        assertEquals(32,source.playlistArtwork.value.size);assertNull(source.playlistArtwork.value[all.first().uri])
        val readsBefore=reads.gets
        source.requestPlaylistArtwork(listOf(all.first()));source.download()
        assertEquals(34,images.downloads);assertNotNull(source.playlistArtwork.value[all.first().uri]);assertEquals(readsBefore,reads.gets)
        source.close()
    }
    @Test fun playlistStartRequiresMatchingContextShuffleAndActualPlayerReadback() {
        val playlist="spotify:playlist:4uLU6hMCjMI75M1A2tKUQC"
        val request=AudioMediaSelection("start:1","session-a",revision,0,playlist,AudioBrowseQuery(AudioBrowseKind.PLAYLIST,uri=playlist),"page-id",true)
        val body=JSONObject(AudioMediaDecoder.selectionBody(request)).getJSONObject("selection");assertEquals("PLAYLIST_START",body.getString("type"));assertTrue(body.getBoolean("shuffle"));assertFalse(body.has("index"))
        val ack=JSONObject().put("version",1).put("kind","spotify-selection-ack").put("requestId",request.requestId).put("receiverSessionId",request.receiverSessionId).put("status","VERIFIED").put("message","Actual playlist readback")
            .put("confirmation",JSONObject().put("version",1).put("kind","spotify-playlist-start-readback").put("receiverSessionId",request.receiverSessionId).put("playlistUri",playlist).put("shuffle",true)
                .put("trackUri",uri).put("entryId","occurrence").put("observedAtEpochMs",stamp).put("playerSequence",2))
        assertEquals(AudioMediaSelectionStatus.VERIFIED,AudioMediaDecoder.selection(ack.toString(),request,stamp).status)
        ack.getJSONObject("confirmation").put("shuffle",false);assertFailsWith<PiProtocolException>{AudioMediaDecoder.selection(ack.toString(),request,stamp)}
    }

    @Test fun sameTrackArtworkRetriesWithCappedBackoffAndSuccessStopsIt() {
        val reads = Transport(media()); val images = Transport(""); var clock = 100L; var fail = true
        images.onImage = { if (fail) throw java.io.IOException("synthetic unavailable") else ByteArray(20) }
        val source = NetworkPiAudioMedia(reads, images, { stamp }, Scheduler(), Scheduler(), retryUptimeMs = { clock })
        try {
            source.poll(); source.download(); assertEquals(1, images.downloads)
            for (delay in listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L)) {
                val before = images.downloads
                source.poll(); clock += delay - 1; source.download(); assertEquals(before, images.downloads)
                clock++; source.download(); assertEquals(before + 1, images.downloads)
            }
            fail = false; clock += 30_000; source.download(); assertNotNull(source.artwork.value)
            val before = images.downloads; clock += 60_000; source.poll(); source.download()
            assertEquals(before, images.downloads)
        } finally { source.close() }
    }
    @Test fun artworkRetryCannotPublishAcrossSameVisibleTrackAbaOrAfterClose() {
        val reads = Transport(media()); val images = Transport(""); val source = NetworkPiAudioMedia(reads, images, { stamp }, Scheduler(), Scheduler())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val returned = AtomicInteger(); val failure = AtomicReference<Throwable?>(); var worker: Thread? = null
        images.onImage = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); returned.incrementAndGet(); ByteArray(20) }
        try {
            source.poll()
            val activeWorker = thread { try { source.download() } catch (t: Throwable) { failure.set(t) } }; worker = activeWorker
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            source.download(); assertEquals(1, images.downloads) // single existing download worker
            reads.response = media(track = "spotify:track:5uLU6hMCjMI75M1A2tKUQC"); source.poll()
            reads.response = media(); source.poll(); release.countDown(); activeWorker.join(2_000)
            assertFalse(activeWorker.isAlive); assertNull(failure.get()); assertEquals(1, returned.get())
            assertNull(source.artwork.value) // actual returned A bytes are stale after A -> B -> A
            source.close(); source.download(); assertEquals(1, images.downloads)
        } finally { release.countDown(); source.close(); worker?.join(2_000) }
    }
    @Test fun closeDuringArtworkReadDropsReturnedBytesAndCancelsOwnedWorkers() {
        val reads = Transport(media()); val images = Transport(""); val scheduler = Scheduler(); val imageScheduler = Scheduler()
        val source = NetworkPiAudioMedia(reads, images, { stamp }, scheduler, imageScheduler)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val returned = AtomicInteger(); val failure = AtomicReference<Throwable?>(); var worker: Thread? = null
        images.onImage = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); returned.incrementAndGet(); ByteArray(20) }
        try {
            source.poll()
            val activeWorker = thread { try { source.download() } catch (t: Throwable) { failure.set(t) } }; worker = activeWorker
            assertTrue(entered.await(2, TimeUnit.SECONDS)); source.close()
            assertTrue(scheduler.stopped && imageScheduler.stopped); assertTrue(reads.closed && images.closed)
            release.countDown(); activeWorker.join(2_000)
            assertFalse(activeWorker.isAlive); assertNull(failure.get()); assertEquals(1, returned.get())
            assertNull(source.artwork.value); source.download(); assertEquals(1, images.downloads)
        } finally { release.countDown(); source.close(); worker?.join(2_000) }
    }
    @Test fun mediaFailureStatusSeparatesHttpFallbackSchemaAndContextWithoutRawPayload() {
        val reads = Transport(media()); val source = NetworkPiAudioMedia(reads, Transport(""), { stamp }, Scheduler(), Scheduler())
        try {
            reads.onGet = { throw PiHttpStatusException(401) }; source.poll()
            assertEquals(AudioMediaReadPhase.NAVIGATION_READ, source.readDiagnostic.phase)
            assertEquals(AudioMediaReadReason.HTTP_STATUS, source.readDiagnostic.reason)
            assertEquals(401, source.readDiagnostic.httpStatus); assertFalse(source.metadata.value.available)
            reads.onGet = { throw PiHttpStatusException(404) }; source.poll()
            assertEquals(AudioMediaReadPhase.MEDIA_FALLBACK_READ, source.readDiagnostic.phase)
            assertEquals(AudioMediaReadReason.HTTP_STATUS, source.readDiagnostic.reason)
            assertEquals(404, source.readDiagnostic.httpStatus); assertFalse(source.metadata.value.available)
            assertFalse(source.metadata.value.navigationAvailable)
            reads.onGet = { path -> if (path == "/v1/audio/navigation") throw PiHttpStatusException(404) else throw java.io.IOException("DO_NOT_EXPOSE_secret") }
            source.poll(); assertEquals(AudioMediaReadPhase.MEDIA_FALLBACK_READ, source.readDiagnostic.phase)
            assertEquals(AudioMediaReadReason.TRANSPORT_UNAVAILABLE, source.readDiagnostic.reason)
            assertFalse(source.metadata.value.status.contains("DO_NOT_EXPOSE"))
            reads.onGet = { media().replace("spotify-media", "wrong-kind") }; source.poll()
            assertEquals(AudioMediaReadPhase.MEDIA_DECODE, source.readDiagnostic.phase)
            assertEquals(AudioMediaReadReason.SCHEMA_INVALID, source.readDiagnostic.reason)
            reads.onGet = { """{"version":1,"kind":"spotify-media","receiverSessionId":"session-a","available":false,"observedAtEpochMs":$stamp,"track":null,"artwork":null,"queue":null}""" }
            source.poll(); assertEquals(AudioMediaReadReason.CONTEXT_UNAVAILABLE, source.readDiagnostic.reason)
            assertFalse(source.metadata.value.available)
            reads.onGet = { path -> if (path == "/v1/audio/navigation") throw PiHttpStatusException(404) else media() }
            source.poll(); assertEquals(AudioMediaReadReason.READY, source.readDiagnostic.reason)
            assertTrue(source.metadata.value.available); assertFalse(source.metadata.value.navigationAvailable)
        } finally { source.close() }
    }

}
