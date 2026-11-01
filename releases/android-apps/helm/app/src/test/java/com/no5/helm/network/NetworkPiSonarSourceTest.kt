package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NetworkPiSonarSourceTest {
    private val identity = SourceIdentity("android-1", SourceMode.PI, "GATEWAY")
    private val stamp = 1_000_000L
    private open class Scheduler : PiPollScheduler {
        lateinit var action: () -> Unit
        var closed = false
        override fun schedule(intervalMs: Long, poll: () -> Unit): SourceSubscription {
            action = poll; return SourceSubscription { closed = true }
        }
    }
    private class ReadScheduler:Scheduler() {
        var task:Runnable?=null;var accept=true
        override fun schedule(intervalMs:Long,poll:()->Unit):SourceSubscription {
            val base=super.schedule(intervalMs,poll)
            return object:ReadTaskPollSubscription {
                override fun requestRead(value:Runnable):Boolean {
                    if(closed||!accept)return false
                    if(task!=null)return task===value
                    task=value;return true
                }
                override fun close(){task=null;base.close()}
            }
        }
        fun consumeRead(){task.also{task=null}?.run()}
    }
    private class Stream : SonarStreamTransport {
        val listeners = mutableListOf<SonarStreamTransport.Listener>()
        var disconnected = 0
        var closed = false
        override fun connect(listener: SonarStreamTransport.Listener): SourceSubscription {
            listeners += listener
            return SourceSubscription { disconnected++ }
        }
        override fun close() { closed = true }
        fun state(root: JSONObject) = listeners.last().text(root.toString())
        fun ping(root: JSONObject) = listeners.last().binary(binary(root))
    }
    private class Http : PiHttpTransport {
        var calls = 0
        var response = ""
        var body: JSONObject? = null
        var closed = false
        val gets = mutableListOf<String>()
        var read: (String) -> String = { error("WS state must drive acquisition") }
        override fun get(path: String): String { gets += path; return read(path) }
        override fun post(path: String, body: String): String {
            assertEquals("/v1/sonar/command", path); calls++; this.body = JSONObject(body); return response
        }
        override fun close() { closed = true }
    }
    private fun harness(dispatcher:OriginProofConsumerDispatcher?=null, timer:Scheduler=Scheduler(), clock: () -> Long = { stamp }): Harness {
        val stream = Stream(); val http = Http()
        val source = NetworkPiSonarSource(identity, http, stream, clock, timer,
            originProofDispatcher=dispatcher,originClockNanos={1_000_000_000L})
        timer.action()
        return Harness(source, stream, http, timer)
    }
    private data class Harness(val source: NetworkPiSonarSource, val stream: Stream, val http: Http, val timer: Scheduler)
    @Test fun initiallyUnrequestedProfileCreatesNoSocketUntilExplicitPreparedDemand() {
        val timer=Scheduler();val stream=Stream();val http=Http()
        val source=NetworkPiSonarSource(identity,http,stream,{stamp},timer,initialProfileDemand=false)
        repeat(4){timer.action()}
        assertEquals(0,stream.listeners.size);assertEquals(0,http.calls)
        source.setProfileDemand(true);timer.action()
        assertEquals(1,stream.listeners.size)
        stream.state(state());source.tick();assertTrue(source.status.value.connected)
        assertEquals(0,http.calls);source.close()
    }
    @Test fun productionSessionFactoryDoesNotStartAnImageReadBeforeItsViewRequestsIt() {
        val timers=mutableMapOf<String,Scheduler>();val stream=Stream()
        val factory=NetworkSourceSessionFactory(now={stamp},transport={_,_->Http()},
            scheduler={tag->Scheduler().also{timers[tag]=it}},sonarStream={stream})
        val session=factory.create(SourceConfig(mode=SourceMode.PI,weatherProvider=WeatherProviderMode.DISABLED),identity)
        repeat(4){timers.getValue("sonar").action()}
        assertEquals(0,stream.listeners.size)
        session.sonar.setProfileDemand(true);timers.getValue("sonar").action()
        assertEquals(1,stream.listeners.size);session.close()
    }
    private class ProofDispatcher:OriginProofConsumerDispatcher {
        val tasks=ArrayDeque<Runnable>()
        override fun post(task:Runnable):Boolean { tasks.add(task);return true }
        override fun remove(task:Runnable) { tasks.removeAll{it===task} }
        fun consume(){while(tasks.isNotEmpty())tasks.removeFirst().run()}
    }
    private fun proofState(generation:Long=1)=state(simulated=false,generation=generation).put("displaySelection",
        JSONObject().put("version",1).put("source","OWN").put("generation",1))
    private fun enableProof(h:Harness) {
        h.stream.state(proofState());h.source.tick()
        h.http.read={path->
            val nonce=path.substringAfter("nonce=").substringBefore('&')
            JSONObject().put("schema","no5.origin-clock-proof/1").put("nonce",nonce)
                .put("serverSessionId","server-1").put("generation",1).put("displayGeneration",1)
                .put("pingId",42).put("timeUtcNs",stamp*1_000_000L)
                .put("originClockUpperEpochMs",stamp+50).put("uncertaintyMs",1).toString()
        }
    }
    private fun ownPing(id:Long,time:Long=stamp,generation:Long=1)=ping(id,simulated=false,generation=generation,time=time)
        .put("displaySelection",selected("own",1)).put("pingSource","own")
    @Test fun recoveryUsesNewestAcceptedOwnPingAfterOneCompleteOrderedBatchInsteadOfOldUiPing() {
        val h=harness();enableProof(h)
        h.stream.ping(ownPing(42));h.source.tick()
        val batches=mutableListOf<List<Long>>()
        val subscription=h.source.frameBatches.subscribe{v->if(v!=null)batches+=v.map{it.frame.pingId}}
        batches.clear()
        h.stream.ping(ownPing(43,stamp+1));h.stream.ping(ownPing(44,stamp+2))
        h.http.read={path->
            assertTrue(path.contains("pingId=44&observedAtEpochMs=${stamp+2}"))
            assertTrue(path.startsWith("/v1/sonar/recovery-input-proof?"))
            JSONObject().put("schema","no5.recovery-input-proof/1").put("nonce",path.substringAfter("nonce=").substringBefore('&'))
                .put("serverSessionId","server-1").put("generation",1).put("displayGeneration",1)
                .put("pingId",44).put("timeUtcNs",(stamp+2)*1_000_000L)
                .put("minimumPingId",44).put("minimumTimeUtcNs",(stamp+2)*1_000_000L).put("actualSource","own").put("validTxReference",true)
                .put("originClockUpperEpochMs",stamp+52).put("uncertaintyMs",1).toString()
        }
        var result:SonarOriginClockProof?=null
        assertTrue(h.source.requestRecoveryOriginClockProof(SonarOriginClockRequest("server-1",1,1,42,stamp)){result=it})
        assertEquals(listOf(listOf(43L,44L)),batches)
        assertEquals(0,h.http.gets.size);h.timer.action();h.source.tick()
        assertEquals(44L,result!!.request.pingId);assertEquals(50L,result!!.upperAgeMs(1_000_000_000L))
        assertEquals(1,h.http.gets.size);assertEquals(0,h.http.calls);subscription.close();h.source.close()
    }
    @Test fun queuedSourceEpochChangeCannotBeBorrowedAsFreshRecoveryInput() {
        val h=harness();enableProof(h);h.stream.ping(ownPing(42));h.source.tick()
        h.stream.state(proofState(generation=2));h.stream.ping(ownPing(43,generation=2))
        assertFalse(h.source.requestRecoveryOriginClockProof(SonarOriginClockRequest("server-1",1,1,42,stamp)){fail("wrong epoch")})
        assertEquals(2L,h.source.status.value.serverGeneration);assertEquals(0,h.http.gets.size);h.source.close()
    }
    @Test fun recoveryDoesNotUseSimulatedNativeMissingOldStaleOrUnrequestedFrames() {
        for(mode in listOf("simulated","native","missing","older","stale","unrequested")) {
            val h=harness();enableProof(h)
            when(mode){
                "simulated"->{h.stream.state(state().put("displaySelection",selected("own",1)));h.stream.ping(ping(42).put("displaySelection",selected("own",1)).put("pingSource","own"))}
                "native"->{h.stream.state(proofState().put("displaySelection",selected("all",1)));h.stream.ping(ownPing(42).put("displaySelection",selected("all",1)).put("pingSource","native"))}
                "missing"->Unit
                else->{h.stream.ping(ownPing(42));if(mode=="stale")h.stream.state(proofState().put("revision",1).put("status","STALE"))}
            }
            if(mode=="unrequested"){h.source.tick();h.source.setProfileDemand(false)}
            val minimum=SonarOriginClockRequest("server-1",1,1,if(mode=="older")43 else 42,stamp)
            assertFalse(mode,h.source.requestRecoveryOriginClockProof(minimum){fail(mode)})
            assertEquals(0,h.http.gets.size);h.source.close()
        }
    }
    @Test fun profileDemandRetiresOnlyOwnReadWithoutAcquisitionWritesAndRejectsLateCallbacks() {
        val h=harness();val old=h.stream.listeners.single()
        h.source.setProfileDemand(false);h.timer.action();h.source.tick()
        assertEquals(1,h.stream.disconnected);assertEquals(1,h.stream.listeners.size)
        old.text(state().toString());h.source.tick();assertFalse(h.source.status.value.connected)
        repeat(4){h.timer.action()};assertEquals(1,h.stream.listeners.size);assertEquals(0,h.http.calls)
        h.source.setProfileDemand(true);h.timer.action();assertEquals(2,h.stream.listeners.size)
        h.stream.state(state());h.source.tick();assertTrue(h.source.status.value.connected)
        assertEquals(0,h.http.calls);h.source.close()
    }

    @Test fun admittedOriginReadSignalsOnlyProofAndPeriodicPollCannotDuplicateIt() {
        val timer=ReadScheduler();val d=ProofDispatcher();val h=harness(dispatcher=d,timer=timer)
        enableProof(h);var calls=0
        assertTrue(h.source.requestOriginClockProof(SonarOriginClockRequest("server-1",1,1,42,stamp)){calls++;assertNotNull(it)})
        assertNotNull(timer.task);assertEquals(0,h.http.gets.size)
        assertFalse(h.source.requestOriginClockProof(SonarOriginClockRequest("server-1",1,1,43,stamp)){fail("second pending")})
        timer.consumeRead();d.consume();assertEquals(1,calls);assertEquals(1,h.http.gets.size)
        timer.action();h.source.tick();assertEquals(1,h.http.gets.size);assertEquals(0,h.http.calls);h.source.close()
    }
    @Test fun rejectedWorkerWakeIsStickyDiagnosticAndNeverFallsBackToAnotherRead() {
        val timer=ReadScheduler().apply{accept=false};val d=ProofDispatcher();val h=harness(dispatcher=d,timer=timer)
        enableProof(h);var calls=0
        assertTrue(h.source.requestOriginClockProof(SonarOriginClockRequest("server-1",1,1,42,stamp)){calls++;assertNull(it)})
        timer.action();assertEquals(0,h.http.gets.size);d.consume();timer.action();assertEquals(1,calls);assertEquals(0,h.http.gets.size)
        assertEquals("REJECTED",h.source.originClockDiagnostics().state)
        assertEquals("WORKER_WAKE_REJECTED",h.source.originClockDiagnostics().phase);h.source.close()
    }
    @Test fun admittedQueuedReadIsCancelledBySourceCloseBeforeHttp() {
        val timer=ReadScheduler();val d=ProofDispatcher();val h=harness(dispatcher=d,timer=timer);enableProof(h)
        assertTrue(h.source.requestOriginClockProof(SonarOriginClockRequest("server-1",1,1,42,stamp)){fail("closed")})
        h.source.close();timer.consumeRead();d.consume();assertEquals(0,h.http.gets.size);assertNull(timer.task)
    }
    @Test fun readyEventConsumesProofWithoutAnotherPeriodicTickOrHttpRead() {
        val d=ProofDispatcher();val h=harness(dispatcher=d);enableProof(h);var calls=0
        assertTrue(h.source.requestOriginClockProof(SonarOriginClockRequest("server-1",1,1,42,stamp)) {calls++;assertNotNull(it)})
        h.timer.action();assertEquals(0,calls);assertEquals(1,d.tasks.size);assertEquals(1,h.http.gets.size)
        d.consume();assertEquals(1,calls);assertEquals(1,h.http.gets.size);h.source.tick();assertEquals(1,calls);h.source.close()
    }
    @Test fun readyEventDrainsQueuedEpochChangeBeforeCheckingProof() {
        val d=ProofDispatcher();val h=harness(dispatcher=d);enableProof(h);var calls=0
        assertTrue(h.source.requestOriginClockProof(SonarOriginClockRequest("server-1",1,1,42,stamp)){calls++;assertNull(it)})
        h.timer.action();h.stream.state(proofState(generation=2));d.consume()
        assertEquals(1,calls);assertEquals("CONTEXT_AT_CONSUMER",h.source.originClockDiagnostics().phase);h.source.close()
    }
    @Test fun closeCancelsReadyEventAndCallback() {
        val d=ProofDispatcher();val h=harness(dispatcher=d);enableProof(h)
        assertTrue(h.source.requestOriginClockProof(SonarOriginClockRequest("server-1",1,1,42,stamp)){fail("closed")})
        h.timer.action();h.source.close();d.consume();assertTrue(d.tasks.isEmpty())
    }
    private fun state(simulated: Boolean = true, generation: Long = 1, running: Boolean = true, revision: Long = 0, time: Long = stamp): JSONObject = common(simulated, generation).apply {
        put("kind", "sonar.state"); put("revision", revision); put("publishedAtEpochMs", time)
        put("running", running); put("status", if (running) "LIVE" else "IDLE")
        put("capabilities", JSONObject().put("canStart", true).put("canStop", true).put("bottomDetection", true))
        put("cadenceHz", 10.0)
    }
    private fun ping(id: Long = 0, simulated: Boolean = true, generation: Long = 1, time: Long = stamp): JSONObject = common(simulated, generation).apply {
        put("kind", "sonar.ping"); put("pingId", id); put("observedAtEpochMs", time); put("publishedAtEpochMs", time)
        put("bottom", JSONObject().put("bin", 100.0).put("confidence", .95).put("strength", .8))
    }
    private fun common(simulated: Boolean, generation: Long): JSONObject = JSONObject()
        .put("version", 1).put("source", "TEST_GATEWAY").put("sourceKind", if (simulated) "SIMULATED" else "REAL")
        .put("simulated", simulated).put("serverSessionId", "server-1").put("generation", generation)
        .put("format", JSONObject().put("bins", 1024).put("sampleType", "UINT16").put("byteOrder", "LITTLE_ENDIAN"))
        .put("calibration", JSONObject().put("revision", 0).put("valid", true).put("samplePeriodUs", 20.0)
            .put("soundSpeedMps", 1500.0).put("depthReference", "TRANSDUCER")
            .put("transducerOffsetM", JSONObject.NULL).put("keelOffsetM", JSONObject.NULL))
    private fun ack(state: JSONObject, requestId: Long = 1, clientId: String = identity.sessionId): String = JSONObject()
        .put("version", 1).put("kind", "sonar.ack").put("serverSessionId", state.getString("serverSessionId"))
        .put("generation", state.getLong("generation")).put("clientId", clientId).put("requestId", requestId)
        .put("outcome", "VERIFIED").put("reason", JSONObject.NULL).put("state", state).toString()

    @Test fun realAndSimulatedFramesUseIdenticalDecoderAndConsumerThreadDelivery() {
        for (simulated in listOf(false, true)) {
            val h = harness(); h.stream.state(state(simulated)); h.stream.ping(ping(simulated = simulated))
            assertFalse(h.source.status.value.connected); assertNull(h.source.frames.value)
            h.source.tick()
            assertTrue(h.source.status.value.connected); assertTrue(h.source.status.value.running)
            assertEquals(simulated, h.source.status.value.simulated)
            assertEquals(65535, h.source.frames.value!!.frame.sample(0))
            assertEquals(0x1234, h.source.frames.value!!.frame.sample(1))
            assertTrue(h.source.frames.value!!.calibration.calibrated)
            h.source.close()
        }
    }
    @Test fun commandIsPendingUntilMatchingVerifiedHttpAckAndDoesNotInventFrames() {
        val h = harness(); h.stream.state(state(running = false)); h.source.tick()
        val outcome = h.source.start(SonarAcquisitionRequest(identity.sessionId, 1))
        assertTrue(outcome is SonarAcquisitionOutcome.Pending); assertEquals(0, h.http.calls)
        assertFalse(h.source.status.value.running)
        h.http.response = ack(state(revision = 1)); h.timer.action()
        assertFalse(h.source.status.value.running)
        h.source.tick(); assertTrue(h.source.outcomes.value is SonarAcquisitionOutcome.Acknowledged)
        assertTrue(h.source.status.value.running); assertNull(h.source.frames.value)
        assertEquals(0, h.http.body!!.getInt("expectedRevision")); assertEquals("start", h.http.body!!.getString("command"))
        h.source.close()
    }
    @Test fun noHelloNoCommandAndOldRequestIsNotSentAgain() {
        val h = harness()
        assertTrue(h.source.start(SonarAcquisitionRequest(identity.sessionId, 1)) is SonarAcquisitionOutcome.Rejected)
        assertEquals(0, h.http.calls)
        h.stream.state(state(running = false)); h.source.tick()
        h.source.start(SonarAcquisitionRequest(identity.sessionId, 2)); h.http.response = ack(state(revision = 1), 2)
        h.timer.action(); h.source.tick()
        val result = h.source.start(SonarAcquisitionRequest(identity.sessionId, 2)) as SonarAcquisitionOutcome.Rejected
        assertEquals(SonarRejection.STALE_REQUEST, result.reason); assertEquals(1, h.http.calls)
        h.source.close()
    }
    @Test fun wrongAckSessionFailsClosedAndNeverReplaysOnReconnect() {
        var time = stamp
        val h = harness { time }; h.stream.state(state(running = false)); h.source.tick()
        h.source.start(SonarAcquisitionRequest(identity.sessionId, 1))
        h.http.response = ack(state(revision = 1), clientId = "old-client")
        h.timer.action(); h.source.tick()
        assertFalse(h.source.status.value.connected); assertTrue(h.source.outcomes.value is SonarAcquisitionOutcome.Rejected)
        time += 1001; h.timer.action(); h.stream.state(state(time = time)); h.source.tick(); h.timer.action()
        assertEquals(1, h.http.calls)
        h.source.close()
    }
    @Test fun fullQueueBackpressuresWithoutDroppingAndPublishesWholeBatches() {
        val h = harness(); h.stream.state(state()); h.source.tick()
        val batches = mutableListOf<List<Long>>()
        h.source.frameBatches.subscribe { rows -> rows?.let { batches += it.map { row -> row.frame.pingId } } }
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val producer = Thread {
            try { repeat(65) { h.stream.ping(ping(it.toLong())) } }
            catch (error: Throwable) { failure.set(error) }
        }
        producer.start()
        try {
            awaitReceiverPressure(h.source)
            assertEquals(NetworkPiSonarSource.QUEUE_CAPACITY, h.source.receiveDiagnostics().pendingEvents)
            h.source.tick()
            producer.join(2000); assertFalse("reader released after drain", producer.isAlive)
            failure.get()?.let { throw AssertionError(it) }
            h.source.tick()
            assertEquals(listOf(64, 1), batches.map { it.size })
            assertEquals((0L..64L).toList(), batches.flatten())
            assertEquals(0L, h.source.status.value.queueDrops)
            assertEquals(64, h.source.receiveDiagnostics().largestBatch)
            assertNull("batch payload is transient", h.source.frameBatches.value)
        } finally { h.source.close(); producer.join(2000) }
    }
    @Test fun queuedBurstsUpTo256ReachControllerCompletelyAndInOrder() {
        for (size in listOf(1,8,64,65,256)) {
            val h=harness();val controller=com.no5.helm.SonarNativeController(h.source){stamp}
            h.stream.state(state());h.source.tick()
            val batches=mutableListOf<List<Long>>()
            h.source.frameBatches.subscribe{rows->rows?.let{batches+=it.map{r->r.frame.pingId}}}
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val producer=Thread{try{repeat(size){h.stream.ping(ping(it.toLong()))}}catch(t:Throwable){failure.set(t)}}
            producer.start()
            try {
                val end=System.nanoTime()+2_000_000_000L
                while(producer.isAlive&&System.nanoTime()<end){h.source.tick();Thread.sleep(1)}
                producer.join(100);assertFalse("burst producer released",producer.isAlive);h.source.tick()
                failure.get()?.let{throw AssertionError(it)}
                assertEquals((0L until size.toLong()).toList(),batches.flatten())
                assertTrue(batches.all{it.size<=NetworkPiSonarSource.QUEUE_CAPACITY})
                assertEquals(size,controller.model.viewRing.count)
                assertEquals((size-1).toLong(),controller.model.viewRing.pingIdAt(0))
                assertEquals(0L,h.source.status.value.queueDrops)
                assertEquals(0L,controller.model.droppedFrames)
            } finally {controller.dispose();h.source.close();producer.join(1000)}
        }
    }
    private fun awaitReceiverPressure(source: NetworkPiSonarSource) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!source.receiveDiagnostics().backpressured && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("reader reached bounded backpressure", source.receiveDiagnostics().backpressured)
    }
    @Test fun closeReleasesWaitingReaderWithoutAReconnectOrWrite() {
        val h = harness(); h.stream.state(state()); h.source.tick()
        val reader = Thread { repeat(65) { h.stream.ping(ping(it.toLong())) } }
        reader.start()
        try {
            awaitReceiverPressure(h.source)
            h.source.close(); reader.join(2000)
            assertFalse("closed queue must not strand socket reader", reader.isAlive)
            assertEquals(0, h.http.calls)
        } finally { h.source.close(); reader.join(2000) }
    }
    @Test fun queuedPingsAreBatchedWithoutCrossingStateGenerationBoundaries() {
        val h = harness(); h.stream.state(state()); h.source.tick()
        val batches = mutableListOf<List<SonarSourceFrame>>()
        h.source.frameBatches.subscribe { rows -> rows?.let { batches += it } }
        repeat(8) { h.stream.ping(ping(it.toLong())) }
        h.stream.state(state(generation = 2))
        repeat(8) { h.stream.ping(ping(it.toLong(), generation = 2)) }
        h.source.tick()
        assertEquals(listOf(8, 8), batches.map { it.size })
        assertEquals(listOf(1L, 2L), batches.map { it.first().serverGeneration })
        assertEquals(0L, h.source.status.value.queueDrops)
        h.source.close()
    }
    @Test fun outOfOrderAndWrongGenerationFramesAreDiscardedWithoutReplacingLatest() {
        for (wrong in listOf(ping(0), ping(2, generation = 0))) {
            val h = harness(); h.stream.state(state()); h.stream.ping(ping(1)); h.source.tick()
            h.stream.ping(wrong); h.source.tick()
            assertTrue(h.source.status.value.connected); assertEquals(1L, h.source.status.value.rejectedFrames)
            assertEquals(1L, h.source.frames.value!!.frame.pingId)
            h.source.close()
        }
    }
    @Test fun newGenerationAllowsSequenceResetAndRetiredConnectionCallbacksCannotPublish() {
        var time = stamp
        val h = harness { time }; h.stream.state(state()); h.stream.ping(ping(50)); h.source.tick()
        val oldGeneration = h.source.status.value.generation
        h.stream.state(state(generation = 2)); h.stream.ping(ping(0, generation = 2)); h.source.tick()
        assertTrue(h.source.status.value.generation > oldGeneration); assertEquals(0L, h.source.frames.value!!.frame.pingId)
        val old = h.stream.listeners.last(); old.failure("lost"); h.source.tick()
        time += 1001; h.timer.action(); h.stream.state(state(generation = 2, time = time)); h.source.tick()
        old.binary(binary(ping(1000, generation = 2, time = time))); h.source.tick()
        assertEquals(0L, h.source.frames.value!!.frame.pingId)
        h.source.close(); assertTrue(h.http.closed && h.stream.closed && h.timer.closed)
        h.stream.ping(ping(1, generation = 2, time = time)); h.source.tick()
    }
    @Test fun aHigherGatewayGenerationCanReplaceM0WithRealM1WithinTheSameServerSession() {
        for (changeName in listOf(false, true)) {
            val h = harness()
            val controller = com.no5.helm.SonarNativeController(h.source) { stamp }
            try {
                h.stream.state(state()); h.stream.ping(ping(50)); controller.tick()
                controller.model.setFreeze(true)
                val localGeneration = h.source.status.value.generation
                val name = if (changeName) "REAL_M1_GATEWAY" else "TEST_GATEWAY"
                h.stream.state(state(simulated = false, generation = 2).put("source", name))
                h.stream.ping(ping(1, simulated = false, generation = 2).put("source", name))
                controller.tick()
                assertTrue("Explicit new producer epoch must recover without restarting the app", h.source.status.value.connected)
                assertNull(h.source.status.value.error); assertFalse(h.source.status.value.simulated)
                assertEquals("server-1", h.source.status.value.serverSessionId)
                assertEquals(2L, h.source.status.value.serverGeneration)
                assertTrue(h.source.status.value.generation > localGeneration)
                assertEquals(1L, controller.model.latest!!.pingId)
                assertEquals(1, controller.model.ring.count); assertFalse(controller.model.display.freeze)
            } finally { controller.dispose(); h.source.close() }
        }
    }
    @Test fun aProducerChangeWithoutANewerGatewayEpochStillFailsClosed() {
        val h = harness(); h.stream.state(state()); h.stream.ping(ping(50)); h.source.tick()
        h.stream.state(state(simulated = false)); h.source.tick()
        assertFalse(h.source.status.value.connected)
        assertEquals("SONAR protocol validation failed", h.source.status.value.error)
        h.source.close()
    }
    @Test fun anOlderProducerEpochCannotDisconnectOrReplaceTheNewSource() {
        val h = harness()
        h.stream.state(state(simulated = false, generation = 2)); h.stream.ping(ping(4, simulated = false, generation = 2)); h.source.tick()
        h.stream.state(state(simulated = true, generation = 1)); h.source.tick()
        assertTrue(h.source.status.value.connected); assertNull(h.source.status.value.error)
        assertFalse(h.source.status.value.simulated); assertEquals(2L, h.source.status.value.serverGeneration)
        assertEquals(4L, h.source.frames.value!!.frame.pingId); assertEquals(1L, h.source.status.value.rejectedFrames)
        h.source.close()
    }
    @Test fun periodicStateCannotRefreshOldObservationsOrBypassSimulatedFreshness() {
        var time = stamp
        val h = harness { time }; h.stream.state(state()); h.stream.ping(ping()); h.source.tick()
        time += 6000; h.stream.state(state(time = time)); h.source.tick()
        assertEquals(stamp, h.source.frames.value!!.frame.timestampEpochMs)
        assertFalse("Fresh state proves transport only",h.source.status.value.stale); assertNull(h.source.status.value.error); h.source.close()
    }
    @Test fun freshStateWithoutNewPingsProducesZeroRateButNeverRefreshesDepth() {
        var time=stamp
        val h=harness{time}
        val controller=com.no5.helm.SonarNativeController(h.source){time}
        try {
            h.stream.state(state());h.stream.ping(ping());controller.tick()
            assertNotNull(controller.snapshot.depthM)
            time+=6000;h.stream.state(state(time=time));controller.tick()
            assertTrue(h.source.status.value.connected);assertFalse(h.source.status.value.stale)
            assertEquals(stamp,controller.snapshot.timestampEpochMs)
            assertNull(controller.snapshot.depthM);assertEquals(SonarQuality.DEGRADED,controller.snapshot.quality)
            assertEquals(SonarRateState.ZERO,controller.pingRate.state);assertEquals(0.0,controller.pingRate.hz!!,0.0)
            time+=6000;controller.tick()
            assertFalse(h.source.status.value.connected);assertTrue(h.source.status.value.stale)
            assertEquals(SonarRateState.STALE,controller.pingRate.state)
        } finally {controller.dispose()}
    }

    private fun originalReferenceReason(statusTime:Long=stamp,version:Int=1,code:String="ORIGINAL_REFERENCE_LOST")=JSONObject()
        .put("version",version).put("code",code).put("detail","native_reference_lost")
        .put("observedAtEpochMs",JSONObject.NULL).put("statusObservedAtEpochMs",statusTime)
        .put("latched",true).put("statusFresh",true)

    @Test fun observationReasonIsOptionalAndPublicationNeverBecomesItsEventTime() {
        val decoded=SonarV1Decoder.state(state(time=stamp+1000).put("observationReason",originalReferenceReason()).toString())
        assertEquals("ORIGINAL_REFERENCE_LOST",decoded.observationReason!!.code)
        assertNull(decoded.observationReason.observedAtEpochMs)
        assertEquals(stamp,decoded.observationReason.statusObservedAtEpochMs)
        for(reason in listOf(originalReferenceReason(version=2),originalReferenceReason(code="UNKNOWN_FUTURE"),originalReferenceReason().put("latched","true"),JSONObject())) {
            assertNull(SonarV1Decoder.state(state().put("observationReason",reason).toString()).observationReason)
        }
    }

    @Test fun hostReasonAgesDespiteStateHeartbeatAndDisconnectPreservesOnlyStaleLatchedEvidence() {
        var time=stamp;val h=harness{time}
        try {
            h.stream.state(state().put("observationReason",originalReferenceReason()));h.source.tick()
            assertTrue(h.source.status.value.observationReason!!.statusFresh)
            time+=6000;h.stream.state(state(time=time).put("observationReason",originalReferenceReason()));h.source.tick()
            val old=h.source.status.value.observationReason!!
            assertEquals(stamp,old.statusObservedAtEpochMs);assertNull(old.observedAtEpochMs);assertFalse(old.statusFresh)
            h.stream.listeners.last().failure("USB unavailable");h.source.tick()
            assertFalse(h.source.status.value.connected)
            assertEquals(old,h.source.status.value.observationReason)
        } finally {h.source.close()}
    }

    @Test fun explicitRecoveryClearsReasonWithoutChangingTheStreamGeneration() {
        var time=stamp;val h=harness{time}
        try {
            h.stream.state(state().put("observationReason",originalReferenceReason()));h.source.tick()
            val generation=h.source.status.value.generation
            time+=1000;h.stream.state(state(time=time).put("observationReason",JSONObject.NULL));h.source.tick()
            assertNull(h.source.status.value.observationReason);assertEquals(generation,h.source.status.value.generation)
            assertTrue(h.source.status.value.connected)
        } finally {h.source.close()}
    }

    @Test fun inFlightPingAfterStopAckIsDiscardedAndVerifiedNoOpIsAccepted() {
        val h = harness(); h.stream.state(state()); h.stream.ping(ping(1)); h.source.tick()
        h.source.stop(SonarAcquisitionRequest(identity.sessionId, 1))
        h.http.response = ack(state(running = false, revision = 1)); h.timer.action()
        h.stream.ping(ping(2)); h.source.tick()
        assertTrue(h.source.status.value.connected); assertFalse(h.source.status.value.running)
        assertEquals(1L, h.source.frames.value!!.frame.pingId)
        assertTrue(h.source.outcomes.value is SonarAcquisitionOutcome.Acknowledged)
        h.source.stop(SonarAcquisitionRequest(identity.sessionId, 2))
        h.http.response = ack(state(running = false, revision = 1), 2); h.timer.action(); h.source.tick()
        assertTrue(h.source.outcomes.value is SonarAcquisitionOutcome.Acknowledged)
        assertTrue(h.source.status.value.connected); h.source.close()
    }
    @Test fun commandTimeoutAndClosedSourceDropLateAckAndSamples() {
        var time = stamp
        val h = harness { time }; h.stream.state(state(running = false)); h.source.tick()
        h.source.start(SonarAcquisitionRequest(identity.sessionId, 1))
        time += 5001; h.source.tick()
        val result = h.source.outcomes.value as SonarAcquisitionOutcome.Rejected
        assertEquals(SonarRejection.TIMEOUT, result.reason)
        h.timer.action(); assertEquals(0, h.http.calls)
        h.source.close(); assertNull(h.source.frames.value); assertFalse(h.source.status.value.connected)
        assertFalse(h.source.capabilities.value.canStart)
        h.stream.state(state()); h.stream.ping(ping()); h.source.tick()
        assertNull(h.source.frames.value)
    }
    private fun selected(source: String, generation: Long) = JSONObject().put("version", 1)
        .put("source", source).put("generation", generation).put("publishedHz", 18.1)
        .put("lastPublishedPingId", 50L).put("observedAtEpochMs", stamp)
    private fun diagnostics(generation: Long = 1L, observed: Long = stamp) = JSONObject()
        .put("version", 1).put("kind", "sonar-diagnostics").put("serverSessionId", "server-1")
        .put("generation", generation).put("pingId", Long.MAX_VALUE).put("observedAtEpochMs", observed)
        .put("source", "own").put("fields", org.json.JSONArray().put(JSONObject().put("group", "transmit")
            .put("name", "raw_links").put("value", Long.MAX_VALUE.toString())))
        .put("available", true).put("stale", false)
        .put("statusObservedAtEpochMs", observed).put("statusStale", false)
        .put("statusTimeBasis", "GATEWAY_STATUS_REQUEST_STARTED_AT_UTC")

    @Test fun additiveWireMetadataTravelsWithTheActualPingAndGatewayEpoch() {
        val amplitude = JSONObject().put("version", 1).put("encoding", "UINT16_LE").put("unit", "ADC_CODE")
            .put("significantBits", 12).put("fullScaleCode", 4095).put("normalizationSource", "HELLO_12bit_checked_host_contract")
            .put("rawSamplesModified", false)
        val h = harness()
        h.stream.state(state().put("displaySelection", selected("all", 2)).put("amplitude", amplitude))
        h.stream.ping(ping(5).put("displaySelection", selected("all", 2)).put("amplitude", amplitude).put("pingSource", "own"))
        h.source.tick()
        val observed = h.source.frames.value!!
        assertEquals(SonarPingSource.ALL, observed.displaySelection!!.source)
        assertEquals(SonarPingSource.OWN, observed.pingSource)
        assertEquals(4095, observed.amplitude!!.fullScale); assertTrue(observed.amplitude!!.usable)
        assertEquals(1L, observed.serverGeneration)
        h.source.close()
    }
    @Test fun selectionChangeClearsHistoryAndRejectsLateSelectionWithoutLosingConnection() {
        val h = harness()
        h.stream.state(state().put("displaySelection", selected("own", 1)))
        h.stream.ping(ping(50).put("displaySelection", selected("own", 1))); h.source.tick()
        val generation = h.source.status.value.generation
        h.stream.state(state(revision = 1).put("displaySelection", selected("native", 2))); h.source.tick()
        assertNull(h.source.frames.value); assertTrue(h.source.status.value.generation > generation)
        h.stream.ping(ping(0).put("displaySelection", selected("native", 2)).put("pingSource", "native")); h.source.tick()
        assertEquals(0L, h.source.frames.value!!.frame.pingId)
        assertEquals(SonarPingSource.NATIVE, h.source.frames.value!!.pingSource)
        h.stream.ping(ping(100).put("displaySelection", selected("own", 1))); h.source.tick()
        h.stream.state(state(revision = 2).put("displaySelection", selected("own", 1))); h.source.tick()
        assertTrue(h.source.status.value.connected); assertEquals(0L, h.source.frames.value!!.frame.pingId)
        assertEquals(SonarPingSource.NATIVE, h.source.status.value.displaySelection!!.source)
        h.source.close()
    }
    @Test fun aKnownWrongActualSourceWithoutSelectionMetadataIsDiscardedWithoutDisconnect() {
        val h = harness()
        h.stream.state(state().put("displaySelection", selected("native", 2)))
        h.stream.ping(ping(1).put("displaySelection", selected("native", 2)).put("pingSource", "native")); h.source.tick()
        h.stream.ping(ping(2).put("pingSource", "own")); h.source.tick()
        assertTrue(h.source.status.value.connected); assertNull(h.source.status.value.error)
        assertEquals(1L, h.source.frames.value!!.frame.pingId); assertEquals(1L, h.source.status.value.rejectedFrames)
        // Unproven optional source is still compatible; the domain marks its depth/rate unconfirmed.
        h.stream.ping(ping(3)); h.source.tick()
        assertTrue(h.source.status.value.connected); assertEquals(3L, h.source.frames.value!!.frame.pingId)
        assertNull(h.source.frames.value!!.pingSource); h.source.close()
    }
    @Test fun aSameEpochReconnectResumesWithGapAndNewSessionAllowsSmallPingIds() {
        var time = stamp
        val h = harness { time }; h.stream.state(state()); h.stream.ping(ping(50)); h.source.tick()
        h.stream.listeners.last().failure("USB lost"); h.source.tick()
        assertFalse(h.source.status.value.connected); assertTrue(h.source.status.value.stale)
        time += 1001; h.timer.action(); h.stream.state(state(time = time)); h.stream.ping(ping(51, time = time)); h.source.tick()
        assertEquals(51L, h.source.frames.value!!.frame.pingId)
        val generation = h.source.status.value.generation
        h.stream.state(state(time = time).put("serverSessionId", "server-2"))
        h.stream.ping(ping(1, time = time).put("serverSessionId", "server-2")); h.source.tick()
        assertEquals(1L, h.source.frames.value!!.frame.pingId); assertTrue(h.source.status.value.generation > generation)
        h.stream.state(state(time = time + 1)); h.source.tick()
        assertEquals("server-2", h.source.status.value.serverSessionId); assertTrue(h.source.status.value.connected)
        h.source.close()
    }
    @Test fun diagnosticsOnlyPollWhileVisibleAtMostOneHzAndCannotPublishRetiredAnswer() {
        var time = stamp
        val h = harness { time }
        h.http.read = { path -> assertEquals("/v1/sonar/diagnostics", path); diagnostics(observed = time).toString() }
        h.stream.state(state().also { it.getJSONObject("capabilities").put("diagnostics", true) }); h.source.tick()
        h.timer.action(); assertTrue(h.http.gets.isEmpty())
        h.source.setDiagnosticsVisible(true); h.timer.action(); h.source.tick()
        assertEquals(Long.MAX_VALUE, h.source.diagnostics.value!!.pingId)
        assertEquals(Long.MAX_VALUE.toString(), h.source.diagnostics.value!!.fields.single().value)
        repeat(3) { time += 250; h.timer.action(); h.source.tick() }
        assertEquals(1, h.http.gets.size)
        time += 250; h.timer.action(); h.source.tick(); assertEquals(2, h.http.gets.size)
        h.source.setDiagnosticsVisible(false); time += 1000; h.timer.action(); assertEquals(2, h.http.gets.size)
        h.source.setDiagnosticsVisible(true)
        h.http.read = { h.source.setDiagnosticsVisible(false); diagnostics(observed = time).put("pingId", 4).toString() }
        h.timer.action(); h.source.tick(); assertEquals(Long.MAX_VALUE, h.source.diagnostics.value!!.pingId)
        h.source.close()
    }
    @Test fun freshHostStatusSurvivesMissingAnalysisButNeitherTimestampIsArtificiallyRenewed() {
        var time = stamp
        val h = harness { time }
        h.stream.state(state().put("status", "STALE").also { it.getJSONObject("capabilities").put("diagnostics", true) }); h.source.tick()
        h.http.read = { diagnostics(observed = time).put("pingId", JSONObject.NULL)
            .put("observedAtEpochMs", JSONObject.NULL).put("stale", true).toString() }
        h.source.setDiagnosticsVisible(true); h.timer.action(); h.source.tick()
        val received = h.source.diagnostics.value!!
        assertNull(received.pingId); assertNull(received.observedAtEpochMs); assertTrue(received.stale)
        assertEquals(stamp, received.statusObservedAtEpochMs!!); assertFalse(received.statusStale!!)
        assertTrue(h.source.status.value.stale) // Old raw ping does not invalidate independent status evidence.
        // A fresh websocket heartbeat keeps transport alive; it must not renew
        // the timestamp of the old HTTP status snapshot or an absent analysis.
        time += 5001
        h.stream.state(state(revision = 1, time = time).also { it.getJSONObject("capabilities").put("diagnostics", true) })
        h.source.tick()
        val expired = h.source.diagnostics.value!!
        assertTrue(expired.statusStale!!); assertEquals(stamp, expired.statusObservedAtEpochMs!!)
        assertNull(expired.observedAtEpochMs); assertTrue(h.source.status.value.connected)
        h.source.close()
    }
    @Test fun aLateDiagnosticSnapshotCannotResurrectARetiredGatewayGeneration() {
        val h = harness()
        h.stream.state(state().also { it.getJSONObject("capabilities").put("diagnostics", true) }); h.source.tick()
        h.source.setDiagnosticsVisible(true)
        h.http.read = {
            h.stream.state(state(generation = 2).also { it.getJSONObject("capabilities").put("diagnostics", true) })
            h.source.tick()
            diagnostics(generation = 1).toString()
        }
        h.timer.action(); h.source.tick()
        assertEquals(2L, h.source.status.value.serverGeneration); assertNull(h.source.diagnostics.value)
        assertTrue(h.source.status.value.connected); h.source.close()
    }
    @Test fun malformedDiagnosticsDoesNotTearDownTheLiveStream() {
        val h = harness()
        h.stream.state(state().also { it.getJSONObject("capabilities").put("diagnostics", true) }); h.source.tick()
        h.http.read = { "x".repeat(32_769) }; h.source.setDiagnosticsVisible(true); h.timer.action(); h.source.tick()
        assertTrue(h.source.status.value.connected); assertNull(h.source.diagnostics.value)
        h.source.close()
    }
    @Test fun separateAcquisitionAndGatewayClocksKeepActualTimesWithoutReconnects() {
        val h = harness(); h.stream.state(state()); h.source.tick()
        // The recorded Mac/Pi case: acquisition is 11 ms ahead of publication.
        h.stream.ping(ping(1, time = stamp + 11).put("publishedAtEpochMs", stamp))
        h.source.tick()
        assertTrue(h.source.status.value.connected); assertNull(h.source.status.value.error)
        assertEquals(stamp + 11, h.source.frames.value!!.frame.timestampEpochMs)
        assertEquals(stamp, h.source.frames.value!!.publishedAtEpochMs)
        h.stream.ping(ping(2, time = stamp + 21).put("publishedAtEpochMs", stamp + 10))
        h.source.tick()
        assertEquals(2L, h.source.frames.value!!.frame.pingId)
        assertEquals(0, h.stream.disconnected)
        // Regression in either original clock is still discarded.
        h.stream.ping(ping(3, time = stamp + 20).put("publishedAtEpochMs", stamp + 11))
        h.source.tick()
        assertEquals(2L, h.source.frames.value!!.frame.pingId)
        assertEquals(1L, h.source.status.value.rejectedFrames)
        h.source.close()
    }
    @Test fun acquisitionPublicationSkewUsesExistingBoundAndPreservesValidation() {
        val atBound = ping(time = stamp + 5000).put("publishedAtEpochMs", stamp)
        assertEquals(stamp + 5000, SonarV1Decoder.ping(binary(atBound)).frame.timestampEpochMs)
        for (value in listOf(
            ping(time = stamp + 5001).put("publishedAtEpochMs", stamp),
            ping().put("publishedAtEpochMs", 0L),
            ping(time = Long.MAX_VALUE).put("publishedAtEpochMs", 1L),
        )) assertThrows(PiProtocolException::class.java) { SonarV1Decoder.ping(binary(value)) }
        val h = harness(); h.stream.state(state()); h.source.tick()
        // Matching future clocks cannot bypass the existing local freshness guard.
        h.stream.ping(ping(1, time = stamp + 5001)); h.source.tick()
        assertFalse(h.source.status.value.connected); assertNull(h.source.frames.value)
        h.source.close()
    }
    @Test fun binaryBoundsEndiannessMetadataAndStrictJsonAreEnforced() {
        val valid = binary(ping())
        assertEquals(65535, SonarV1Decoder.ping(valid).frame.sample(0))
        assertThrows(PiProtocolException::class.java) { SonarV1Decoder.ping(valid + byteArrayOf(0)) }
        assertThrows(PiProtocolException::class.java) { SonarV1Decoder.ping(valid.copyOf().also { it[3] = 127 }) }
        assertThrows(PiProtocolException::class.java) { SonarV1Decoder.ping(binary(ping().put("sourceKind", "REAL"))) }
        assertThrows(PiProtocolException::class.java) { SonarV1Decoder.ping(binary(ping().also { it.getJSONObject("format").put("byteOrder", "BIG_ENDIAN") })) }
        assertThrows(PiProtocolException::class.java) { SonarV1Decoder.state(state().toString().replace("\"version\":1", "\"version\":1,\"version\":1")) }
        assertThrows(PiProtocolException::class.java) { SonarV1Decoder.ping(binary(ping().also { it.getJSONObject("calibration").put("samplePeriodUs", JSONObject.NULL) })) }
    }
    companion object {
        private fun binary(root: JSONObject): ByteArray {
            val header = root.toString().toByteArray(Charsets.UTF_8)
            return ByteBuffer.allocate(4 + header.size + 2048).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(header.size).put(header).putShort(0xffff.toShort()).putShort(0x1234.toShort()).array()
        }
    }
    @Test fun watchdogReconnectOnlyClosesOwnReadSubscriptionAndNeverReplaysWrites() {
        var mono=10_000_000_000L;val timer=Scheduler();val stream=Stream();val http=Http()
        val source=NetworkPiSonarSource(identity,http,stream,{stamp},timer,nowNanos={mono})
        try {
            assertEquals(-1L,source.readReceiveSilenceNanos());assertFalse(source.reconnectReadTransport())
            timer.action();stream.state(state(simulated=false));source.tick()
            assertEquals(0L,source.readReceiveSilenceNanos());assertFalse(source.reconnectReadTransport())
            mono+=1_000_000_000L;assertTrue(source.reconnectReadTransport())
            assertEquals("subscription I/O stays on existing poll worker",0,stream.disconnected);assertEquals(0,http.calls)
            source.tick();assertFalse(source.status.value.connected);assertTrue(source.status.value.stale)
            assertFalse(source.reconnectReadTransport());timer.action();assertEquals(1,stream.disconnected);assertEquals(2,stream.listeners.size)
            // The closed subscription's late data cannot become a fresh source frame.
            stream.listeners.first().text(state(simulated=false).toString());source.tick();assertFalse(source.status.value.connected)
            stream.state(state(simulated=false));source.tick();assertTrue(source.status.value.connected)
            assertEquals(0,http.calls)
        }finally{source.close()}
    }

}
