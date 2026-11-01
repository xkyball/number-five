package com.no5.helm.network

import com.no5.domain.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WS acquisition and HTTP command I/O remain off the UI thread. The app-root tick drains a
 * bounded handoff queue on its consumer dispatcher. Reconnect never replays a command.
 */
class NetworkPiSonarSource(
    override val identity: SourceIdentity,
    private val http: PiHttpTransport,
    private val stream: SonarStreamTransport,
    private val now: () -> Long = System::currentTimeMillis,
    scheduler: PiPollScheduler = BackgroundPiPollScheduler("sonar"),
    private val nowNanos: ()->Long = System::nanoTime,
    originProofDispatcher: OriginProofConsumerDispatcher? = null,
    originClockNanos: () -> Long = android.os.SystemClock::elapsedRealtimeNanos,
    initialProfileDemand: Boolean = true,
) : SonarSourcePort {
    init { require(identity.mode == SourceMode.PI) }
    private val lock = java.lang.Object()
    private var closed = false
    private val polling = AtomicBoolean(false)
    private val events = ArrayDeque<Event>(QUEUE_CAPACITY)
    private var epoch = 0L
    private var connecting = false
    private var profileDemand = initialProfileDemand
    private var connection: SourceSubscription? = null
    private var retiredReadConnection:SourceSubscription?=null
    private var readResetPending=false
    private var retryAt = 0L
    private var connectedAt = 0L
    private var streamReady = false
    private var lastReceivedAt = 0L
    private var lastReceivedNanos = 0L
    private var runningSince = 0L
    private var localGeneration = 0L
    private var current: SonarV1State? = null
    private val retired = LinkedHashSet<String>()
    private var lastPing = -1L
    private var lastObserved = -1L
    private var lastPublished = -1L
    private var lastCalibration: Calibration? = null
    private var lastRequest = -1L
    private var queueDrops = 0L
    private var waitingReceivers = 0
    private var backpressureEvents = 0L
    private var deliveredBatches = 0L
    private var largestBatch = 0
    private var consuming = false
    private var deliveryBatch: MutableList<SonarSourceFrame>? = null
    private val batchFeed = MutableSourceFeed<List<SonarSourceFrame>?>(null)
    private var rejectedFrames = 0L
    private var pending: Write? = null
    private var diagnosticsVisible = false
    private var diagnosticsVisibilityGeneration = 0L
    private var diagnosticsReadAt = Long.MIN_VALUE
    private var pendingDiagnostics: SonarDiagnostics? = null
    private var diagnosticsDirty = false
    private val diagnosticsFeed = MutableSourceFeed<SonarDiagnostics?>(null)
    override val diagnostics: SourceFeed<SonarDiagnostics?> get() = diagnosticsFeed
    override fun setDiagnosticsVisible(visible: Boolean) = synchronized(lock) {
        if (!closed && diagnosticsVisible != visible) {
            diagnosticsVisible = visible; diagnosticsVisibilityGeneration++
        }
    }
    private val statusFeed = MutableSourceFeed(SonarSourceStatus(identity))
    private val capabilityFeed = MutableSourceFeed(SonarSourceCapabilities(identity))
    private val frameFeed = MutableSourceFeed<SonarSourceFrame?>(null)
    private val outcomeFeed = MutableSourceFeed<SonarAcquisitionOutcome?>(null)
    override val status: SourceFeed<SonarSourceStatus> get() = statusFeed
    override val capabilities: SourceFeed<SonarSourceCapabilities> get() = capabilityFeed
    override val frames: SourceFeed<SonarSourceFrame?> get() = frameFeed
    override val frameBatches: SourceFeed<List<SonarSourceFrame>?> = object : SourceFeed<List<SonarSourceFrame>?> {
        override val value: List<SonarSourceFrame>? get() = null
        override fun subscribe(listener: (List<SonarSourceFrame>?) -> Unit): SourceSubscription = synchronized(lock) {
            if (closed) return@synchronized SourceSubscription { }
            val subscription = batchFeed.subscribe(listener)
            // A newly attached consumer gets the current ping once, just as with frames.
            try { frameFeed.value?.let { listener(listOf(it)) } }
            catch (failure: RuntimeException) { subscription.close(); throw failure }
            subscription
        }
    }
    override fun receiveDiagnostics(): SonarReceiveDiagnostics = synchronized(lock) {
        val oldest = events.firstOrNull { it is Event.Ping } as? Event.Ping
        SonarReceiveDiagnostics(events.size, QUEUE_CAPACITY, waitingReceivers > 0,
            backpressureEvents, deliveredBatches, largestBatch,
            oldest?.let { (nowNanos() - it.receivedAtNanos).coerceAtLeast(0) })
    }
    override val outcomes: SourceFeed<SonarAcquisitionOutcome?> get() = outcomeFeed
    private val filtering = NetworkSonarFilter(identity, http, now)
    override val filterState get() = filtering.state
    override val filterOutcomes get() = filtering.outcomes
    override fun filter(request: SonarFilterRequest) = filtering.submit(request)
    override fun filterResult(commandId: Long) = filtering.result(commandId)
    private val selection = NetworkSonarSourceSelection(identity, http, now)
    override val sourceState get() = selection.state
    override val sourceOutcomes get() = selection.outcomes
    override fun selectSource(request: SonarSourceSelectionRequest) = selection.submit(request)
    override fun sourceResult(commandId: Long) = selection.result(commandId)
    private data class Write(val request: SonarAcquisitionRequest, val state: SonarV1State, val start: Boolean, val epoch: Long, val deadline: Long, var sent: Boolean = false)
    private sealed interface Event {
        val epoch: Long
        data class State(override val epoch: Long, val state: SonarV1State) : Event
        data class Ping(override val epoch: Long, val ping: SonarV1Ping, val receivedAtNanos: Long) : Event
        data class Ack(override val epoch: Long, val requestId: Long, val ack: SonarV1Ack) : Event
        data class Failure(override val epoch: Long, val message: String) : Event
    }
    private val proofConsumerWake: OriginProofConsumerWake? = originProofDispatcher?.let { OriginProofConsumerWake(it) { tick() } }
    // One coalesced Main wake, separate from proof admission and its diagnostics.
    private val receiveWake: OriginProofConsumerWake? = originProofDispatcher?.let { OriginProofConsumerWake(it) { tick() } }
    private val originClock: OriginClockProofClient = OriginClockProofClient(http, nowNanos=originClockNanos, captureContext={ request -> synchronized(lock) {
        val state=current
        if(!closed && streamReady && state!=null && state.origin.serverSessionId==request.serverSessionId &&
            state.origin.generation==request.generation && state.displaySelection?.generation==request.displayGeneration)
            epoch to localGeneration else null
    } }, onReady={ proofConsumerWake?.ready() }) { false } // All request validation and local-generation capture share the same source lock.
    private val originProofRead = Runnable { originClock.pump() }
    override fun requestOriginClockProof(request: SonarOriginClockRequest, callback: (SonarOriginClockProof?) -> Unit): Boolean {
        val accepted=originClock.request(request) { proof -> proofConsumerWake?.cancel(); callback(proof) }
        return wakeAcceptedProof(accepted)
    }
    override fun requestRecoveryOriginClockProof(minimum:SonarOriginClockRequest, callback:(SonarOriginClockProof?)->Unit):Boolean {
        val accepted=originClock.requestRecoveryFrom({ synchronized(lock) {
            // Same consumer drain, no HTTP/source poll and no second RAW history.
            // The client's enqueue time already includes all of this work.
            tick()
            val state=current
            val packet=frameFeed.value
            if(closed||!streamReady||!profileDemand||state==null||packet==null||
                !status.value.connected||!status.value.running||status.value.stale||status.value.error!=null||
                packet.simulated||packet.pingSource!=SonarPingSource.OWN||packet.generation!=localGeneration||
                state.origin.simulated||state.origin.serverSessionId!=minimum.serverSessionId||
                state.origin.generation!=minimum.generation||state.displaySelection?.generation!=minimum.displayGeneration||
                packet.serverSessionId!=minimum.serverSessionId||packet.serverGeneration!=minimum.generation||
                packet.displaySelection?.generation!=minimum.displayGeneration||
                packet.displaySelection?.source!=state.displaySelection?.source||
                packet.frame.pingId<minimum.pingId||packet.frame.timestampEpochMs<minimum.originalEpochMs) null
            else SonarOriginClockRequest(minimum.serverSessionId,minimum.generation,minimum.displayGeneration,
                packet.frame.pingId,packet.frame.timestampEpochMs)
        } }) { proof -> proofConsumerWake?.cancel();callback(proof) }
        return wakeAcceptedProof(accepted)
    }
    private fun wakeAcceptedProof(accepted:Boolean):Boolean {
        if(accepted) {
            // Only the accepted read is signalled, on the same serial IO worker.
            // Injected legacy/manual schedulers retain their explicit poll contract.
            val reads=job as? ReadTaskPollSubscription
            if(reads!=null&&!reads.requestRead(originProofRead)) originClock.rejectQueuedWorkerWake()
        }
        return accepted
    }
    override fun originClockDiagnostics(): SonarOriginClockDiagnostics =
        originClock.diagnostics.copy(consumerWakeFailures=proofConsumerWake?.failures ?: 0)
    private val job = try { scheduler.schedule(250, ::poll) } catch (failure: RuntimeException) {
        http.close(); stream.close(); throw failure
    }

    override fun setProfileDemand(enabled: Boolean) = synchronized(lock) {
        if (closed || profileDemand == enabled) return@synchronized
        profileDemand = enabled
        ++epoch
        check(retiredReadConnection == null || connection == null)
        if (connection != null) retiredReadConnection = connection
        connection = null; connecting = false; streamReady = false
        current = null  // fresh local observation generation on the next admitted subscription
        events.clear(); lock.notifyAll(); retryAt = 0L
        // Retire this observation epoch explicitly; no stale continuity claim
        // across an intentionally absent display subscription.
        frameFeed.emit(null)
        emitStatus(status.value.copy(connected=false, running=false, stale=true,
            error=if(enabled) "PROFILE_DEMAND_RESUMING" else "PROFILE_DEMAND_PAUSED"))
    }
    override fun readReceiveSilenceNanos():Long = synchronized(lock) {
        if(closed||!streamReady||lastReceivedNanos<=0)-1L else (nowNanos()-lastReceivedNanos).takeIf{it>=0}?:-1L
    }
    override fun reconnectReadTransport():Boolean = synchronized(lock) {
        if(closed||!streamReady||pending!=null||retiredReadConnection!=null||lastReceivedNanos<=0||nowNanos()-lastReceivedNanos<1_000_000_000L)return@synchronized false
        ++epoch;retiredReadConnection=connection;connection=null;connecting=false;streamReady=false;events.clear();lock.notifyAll();readResetPending=true
        retryAt=0L
        // No write replay. The next existing poll reconnects only this subscription.
        true
    }
    private fun enqueue(event: Event) = synchronized(lock) {
        if (closed || event.epoch != epoch) return@synchronized
        if(event is Event.State||event is Event.Ping)lastReceivedNanos=nowNanos()
        if (events.size == QUEUE_CAPACITY) {
            // The transport callback is on its reader thread. Stop consuming the
            // socket while Main drains instead of deleting an already received ping.
            waitingReceivers++; backpressureEvents++
            try {
                receiveWake?.ready()
                while (!closed && event.epoch == epoch && events.size == QUEUE_CAPACITY) lock.wait()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("SONAR receiver interrupted while backpressured")
            } finally { waitingReceivers-- }
        }
        if (closed || event.epoch != epoch) return@synchronized
        events.addLast(event)
        receiveWake?.ready()
    }
    private fun poll() {
        if (!polling.compareAndSet(false, true)) return
        try {
            val retiring=synchronized(lock){retiredReadConnection.also{retiredReadConnection=null}}
            try {retiring?.close()}catch(_:RuntimeException){
                synchronized(lock){enqueue(Event.Failure(epoch,"OWN_READ_RECONNECT_CLOSE_FAILED"))};return
            }
            val openEpoch = synchronized(lock) {
                if (closed) return
                if (profileDemand && !connecting && connection == null && now() >= retryAt) {
                    connecting = true; connectedAt = now(); ++epoch
                } else null
            }
            if (openEpoch != null) open(openEpoch)
            val write = synchronized(lock) {
                if (closed) return
                pending?.takeIf { !it.sent && it.epoch == epoch }?.also { it.sent = true }
            }
            if (write != null) send(write)
            filtering.pump()
            selection.pump()
            originClock.pump()
            pollDiagnostics()
        } finally { polling.set(false) }
    }
    /** At most one snapshot per second, only for the currently visible diagnostics consumer. */
    private fun pollDiagnostics() {
        val captured = synchronized(lock) {
            val state = current
            if (closed || !diagnosticsVisible || !streamReady || state?.diagnostics != true ||
                (diagnosticsReadAt != Long.MIN_VALUE && now() - diagnosticsReadAt < 1000)) return
            diagnosticsReadAt = now()
            DiagnosticsCapture(state.origin, epoch, localGeneration, diagnosticsVisibilityGeneration)
        }
        try {
            val value = SonarDiagnosticsDecoder.decode(http.get("/v1/sonar/diagnostics"), identity)
            require(value.serverSessionId == captured.origin.serverSessionId && value.generation == captured.origin.generation)
            value.observedAtEpochMs?.let { validTime(it) }
            value.statusObservedAtEpochMs?.let { validTime(it) }
            synchronized(lock) {
                if (closed || !diagnosticsVisible || epoch != captured.epoch || localGeneration != captured.generation || diagnosticsVisibilityGeneration != captured.visibility) return
                val age = value.observedAtEpochMs?.let { now() - it }
                val statusAge = value.statusObservedAtEpochMs?.let { now() - it }
                pendingDiagnostics = value.copy(stale = value.stale || age == null || age > 5000 || status.value.stale,
                    statusStale = value.statusStale?.let { it || statusAge == null || statusAge !in -5000..5000 ||
                        !status.value.connected || status.value.error != null })
                diagnosticsDirty = true
            }
        } catch (_: Exception) {
            synchronized(lock) {
                if (!closed && epoch == captured.epoch && localGeneration == captured.generation && diagnosticsVisibilityGeneration == captured.visibility) {
                    pendingDiagnostics = pendingDiagnostics?.copy(stale = true, available = false, statusStale = true)
                    diagnosticsDirty = true
                }
            }
        }
    }
    private data class DiagnosticsCapture(val origin: SonarV1Origin, val epoch: Long, val generation: Long, val visibility: Long)
    private fun open(id: Long) {
        try {
            val subscription = stream.connect(object : SonarStreamTransport.Listener {
                override fun text(value: String) {
                    try { enqueue(Event.State(id, SonarV1Decoder.state(value))) }
                    catch (_: Exception) { enqueue(Event.Failure(id, "Invalid SONAR state")) }
                }
                override fun binary(value: ByteArray) {
                    try {
                        val received=nowNanos()
                        val decoded=com.no5.helm.SonarTrace.phase("sonar.rx.decode"){SonarV1Decoder.ping(value)}
                        com.no5.helm.SonarTrace.ping("sonar.rx.ping",decoded.frame.pingId)
                        enqueue(Event.Ping(id, decoded, received))
                    }
                    catch (_: Exception) { enqueue(Event.Failure(id, "Invalid SONAR binary frame")) }
                }
                override fun failure(message: String) { enqueue(Event.Failure(id, message)) }
            })
            synchronized(lock) {
                if (closed || id != epoch) subscription.close() else { connection = subscription; connecting = false }
            }
        } catch (_: Exception) { enqueue(Event.Failure(id, "SONAR connection failed")) }
    }
    private fun send(write: Write) {
        if (now() >= write.deadline) return
        try {
            val ack = SonarV1Decoder.ack(http.post("/v1/sonar/command",
                SonarV1Decoder.command(identity.sessionId, write.request.requestId, write.state, write.start)))
            enqueue(Event.Ack(write.epoch, write.request.requestId, ack))
        } catch (_: Exception) { enqueue(Event.Failure(write.epoch, "SONAR command failed; result unknown, no replay")) }
    }
    override fun start(request: SonarAcquisitionRequest) = command(request, true)
    override fun stop(request: SonarAcquisitionRequest) = command(request, false)
    private fun command(request: SonarAcquisitionRequest, start: Boolean): SonarAcquisitionOutcome = synchronized(lock) {
        fun reject(reason: SonarRejection, text: String) = SonarAcquisitionOutcome.Rejected(identity.sessionId, request.requestId, reason, text)
        when {
            closed -> reject(SonarRejection.CLOSED, "SONAR source is closed")
            request.sessionId != identity.sessionId -> reject(SonarRejection.WRONG_SESSION, "SONAR session changed")
            request.requestId <= lastRequest -> reject(SonarRejection.STALE_REQUEST, "SONAR request is stale")
            pending != null -> reject(SonarRejection.PENDING, "A SONAR command is awaiting confirmation")
            !streamReady || !status.value.connected || current == null ||
                now().toDouble() - current!!.publishedAtEpochMs > 5_000 ||
                !(if (start) capabilities.value.canStart else capabilities.value.canStop) -> reject(SonarRejection.UNAVAILABLE, "SONAR acquisition capability unavailable")
            else -> {
                lastRequest = request.requestId
                pending = Write(request, requireNotNull(current), start, epoch, now() + 5_000)
                SonarAcquisitionOutcome.Pending(identity.sessionId, request.requestId).also { outcomeFeed.emit(it) }
            }
        }
    }
    override fun tick() = synchronized(lock) {
        if (closed || consuming) return@synchronized
        receiveWake?.cancel()
        consuming = true
        deliveryBatch = ArrayList()
        try {
        if(readResetPending){
            readResetPending=false;filtering.observe(null);selection.observe(null)
            pendingDiagnostics=pendingDiagnostics?.copy(stale=true,available=false,statusStale=true);diagnosticsDirty=true
            capabilityFeed.emit(SonarSourceCapabilities(identity));frameFeed.emit(null)
            emitStatus(status.value.copy(connected=false,running=false,stale=true,error="OWN_READ_RECONNECT_PENDING"))
        }
        filtering.tick()
        selection.tick()
        var remaining = events.size
        while (remaining > 0 && events.isNotEmpty()) {
            remaining--
            val event = events.removeFirst()
            lock.notifyAll()
            if (event.epoch != epoch) continue
            try {
                // Publish the old context before applying any state/ACK/failure boundary.
                if (event !is Event.Ping) flushBatch()
                when (event) {
                    is Event.State -> acceptState(event.state, hello = true)
                    is Event.Ping -> acceptPing(event.ping,event.receivedAtNanos)
                    is Event.Ack -> acceptAck(event)
                    is Event.Failure -> fail(event.message)
                }
            } catch (_: Exception) { rejectedFrames++; fail("SONAR protocol validation failed") }
        }
        flushBatch()
        // Fence proofs AFTER all already-received state/epoch events were consumed.
        originClock.tick()
        val stamp = now()
        pending?.takeIf { stamp >= it.deadline }?.let { finish(it, SonarRejection.TIMEOUT, "SONAR command timed out; result unknown, no replay") }
        if (!streamReady && (connection != null || connecting) && stamp - connectedAt > 5_000) fail("SONAR stream handshake timed out")
        if (streamReady && stamp - lastReceivedAt > 5_000) fail("SONAR stream timed out")
        // A live state heartbeat proves the transport, not a fresh ping. The model
        // marks old depth/profile evidence stale while its 5-s ping count reaches zero.
        emitStatus(status.value)
        pendingDiagnostics?.let { value ->
            val analysisExpired = status.value.stale || !status.value.connected ||
                value.observedAtEpochMs?.let { stamp - it !in -5000..5000 } != false
            val statusExpired = !status.value.connected || status.value.error != null ||
                value.statusObservedAtEpochMs?.let { stamp - it !in -5000..5000 } != false
            if (!value.stale && analysisExpired || value.statusStale == false && statusExpired) {
                pendingDiagnostics = value.copy(stale = value.stale || analysisExpired,
                    statusStale = value.statusStale?.let { it || statusExpired })
                diagnosticsDirty = true
            }
        }
        if (diagnosticsDirty) { diagnosticsDirty = false; diagnosticsFeed.emit(pendingDiagnostics) }
        } finally {
            deliveryBatch = null; consuming = false; lock.notifyAll()
        }
    }
    private fun flushBatch() {
        val pendingFrames = deliveryBatch ?: return
        if (pendingFrames.isEmpty()) return
        val batch = pendingFrames.toList(); pendingFrames.clear()
        deliveredBatches++; largestBatch = maxOf(largestBatch, batch.size)
        try { com.no5.helm.SonarTrace.phase("sonar.batch.deliver"){batchFeed.emit(batch)} } finally { batchFeed.emit(null) }
    }
    private fun validTime(value: Long) { require(value > 0 && value.toDouble() - now().toDouble() <= SonarV1Decoder.MAX_CLOCK_SKEW_MS) }
    private fun acceptState(value: SonarV1State, hello: Boolean) {
        validTime(value.publishedAtEpochMs)
        val before = current
        val newSession = before?.origin?.serverSessionId != value.origin.serverSessionId
        val newGeneration = before?.origin?.generation != value.origin.generation
        val newSelection = before != null && (before.displaySelection?.generation != value.displaySelection?.generation ||
            before.displaySelection?.source != value.displaySelection?.source)
        if (newSession) {
            if (value.origin.serverSessionId in retired) { rejectedFrames++; return }
            before?.origin?.serverSessionId?.let { retired.add(it) }
            if (retired.size > 16) retired.remove(retired.first())
        } else {
            if (value.origin.generation < before!!.origin.generation) {
                rejectedFrames++
                emitStatus(status.value.copy(rejectedFrames = rejectedFrames))
                return
            }
            // A Gateway source switch keeps its server session and increments
            // generation. Identity is immutable within that epoch, not forever
            // across a confirmed M0/M1 replacement or its reconnect.
            if (!newGeneration) require(value.origin.source == before.origin.source && value.origin.simulated == before.origin.simulated)
            if (!newGeneration && before.displaySelection != null && value.displaySelection != null &&
                value.displaySelection.generation < before.displaySelection.generation) { rejectedFrames++; return }
            if (!newGeneration && (value.revision < before.revision || value.publishedAtEpochMs < before.publishedAtEpochMs)) return
        }
        if (newSession || newGeneration || newSelection) {
            localGeneration++; lastPing = -1; lastObserved = -1; lastPublished = -1; lastCalibration = null
            frameFeed.emit(null)
            pendingDiagnostics = null; diagnosticsDirty = true
            pending?.let { finish(it, SonarRejection.WRONG_SESSION, "SONAR generation changed; command not replayed") }
        }
        if (value.running && (before?.running != true || newSession || newGeneration || newSelection)) runningSince = now()
        current = value
        filtering.observe(value)
        selection.observe(value)
        lastReceivedAt = now()
        if (hello) streamReady = true
        capabilityFeed.emit(SonarSourceCapabilities(identity, value.canStart && streamReady,
            value.canStop && streamReady, value.bottomDetection, value.filterControl, value.confirmedTracks, value.sourceSelection, value.diagnostics))
        val connected = streamReady && value.status != "UNAVAILABLE"
        emitStatus(SonarSourceStatus(identity, localGeneration, connected, connected && value.running,
            null,
            value.origin.source, value.origin.simulated, value.origin.serverSessionId,
            value.origin.generation, value.publishedAtEpochMs, queueDrops, rejectedFrames, stale = value.status == "STALE",
            displaySelection = value.displaySelection, amplitude = value.amplitude, observationReason = value.observationReason))
    }
    private fun acceptPing(value: SonarV1Ping,receivedAtNanos:Long) {
        val state = requireNotNull(current)
        require(streamReady)
        // HTTP ACK and WS delivery are independent. An in-flight ping after STOP or an
        // old generation is discarded, without tearing down a healthy new acquisition.
        val selectedSource = state.displaySelection?.source
        val wrongKnownSource = selectedSource != null && selectedSource != SonarPingSource.ALL &&
            value.pingSource != null && value.pingSource != selectedSource
        if (wrongKnownSource || !state.running || !status.value.connected || value.origin != state.origin ||
            value.frame.pingId <= lastPing || value.frame.timestampEpochMs < lastObserved || value.publishedAtEpochMs < lastPublished ||
            (state.displaySelection != null && value.displaySelection != null &&
                (state.displaySelection.generation != value.displaySelection.generation || state.displaySelection.source != value.displaySelection.source))) {
            rejectedFrames++
            emitStatus(status.value.copy(rejectedFrames = rejectedFrames))
            return
        }
        validTime(value.frame.timestampEpochMs); validTime(value.publishedAtEpochMs)
        require(value.frame.pingId > lastPing && value.frame.timestampEpochMs >= lastObserved && value.publishedAtEpochMs >= lastPublished)
        require(value.calibration.revision >= state.calibration.revision)
        require(value.calibration.revision != state.calibration.revision || value.calibration == state.calibration)
        lastCalibration?.let {
            require(value.calibration.revision >= it.revision)
            require(value.calibration.revision != it.revision || value.calibration == it)
        }
        lastReceivedAt = now()
        lastPing = value.frame.pingId; lastObserved = value.frame.timestampEpochMs; lastPublished = value.publishedAtEpochMs; lastCalibration = value.calibration
        emitStatus(status.value.copy(error = null, stale = state.status == "STALE",
            queueDrops = queueDrops, rejectedFrames = rejectedFrames))
        val packet = SonarSourceFrame(identity, localGeneration, value.frame, value.calibration, value.bottom,
            value.origin.serverSessionId, value.publishedAtEpochMs, value.origin.simulated, value.geometry, value.presentation, value.hostPackageVersion,
            value.displaySelection, value.amplitude, value.pingSource, value.origin.generation,receivedAtNanos)
        frameFeed.emit(packet)
        deliveryBatch?.add(packet)
    }
    private fun acceptAck(event: Event.Ack) {
        val write = pending ?: return
        if (event.requestId != write.request.requestId || now() >= write.deadline) return
        val ack = event.ack
        require(ack.clientId == identity.sessionId && ack.requestId == write.request.requestId)
        require(ack.state.origin == write.state.origin)
        if (ack.verified) {
            require(ack.state.revision >= write.state.revision && ack.state.running == write.start)
            acceptState(ack.state, hello = false)
            pending = null
            outcomeFeed.emit(SonarAcquisitionOutcome.Acknowledged(identity.sessionId, ack.requestId))
        } else {
            acceptState(ack.state, hello = false)
            finish(write, SonarRejection.REJECTED, ack.reason ?: "SONAR command rejected")
        }
    }
    private fun finish(write: Write, reason: SonarRejection, message: String) {
        if (pending !== write) return
        pending = null
        outcomeFeed.emit(SonarAcquisitionOutcome.Rejected(identity.sessionId, write.request.requestId, reason, message))
    }
    private fun emitStatus(value: SonarSourceStatus) {
        val reason=value.observationReason
        val reasonAge=reason?.let { now()-it.statusObservedAtEpochMs }
        val observed=if(reason!=null && reason.statusFresh && (!value.connected || reasonAge!! !in -5000..5000))
            value.copy(observationReason=reason.copy(statusFresh=false)) else value
        if (statusFeed.value != observed) statusFeed.emit(observed)
    }
    private fun fail(message: String) {
        // Retire all callbacks and packets from this connection, including a late HTTP ACK.
        ++epoch; connection?.close(); connection = null; connecting = false; streamReady = false
        filtering.observe(null)
        selection.observe(null)
        pendingDiagnostics = pendingDiagnostics?.copy(stale = true, available = false, statusStale = true); diagnosticsDirty = true
        events.clear(); lock.notifyAll(); retryAt = now() + 1_000
        pending?.let { finish(it, SonarRejection.UNAVAILABLE, message) }
        capabilityFeed.emit(SonarSourceCapabilities(identity))
        emitStatus(status.value.copy(connected = false, running = false, stale = true, error = message,
            queueDrops = queueDrops, rejectedFrames = rejectedFrames))
    }
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; ++epoch; events.clear(); pending = null; lock.notifyAll()
            connection?.close(); connection = null
            retiredReadConnection?.close();retiredReadConnection=null
            // Observers cannot prevent resource retirement or retain a live-looking final value.
            try { emitStatus(status.value.copy(connected = false, running = false)) } catch (_: RuntimeException) { }
            try { capabilityFeed.emit(SonarSourceCapabilities(identity)) } catch (_: RuntimeException) { }
            try { frameFeed.emit(null) } catch (_: RuntimeException) { }
            try { diagnosticsFeed.emit(null) } catch (_: RuntimeException) { }
            statusFeed.close(); capabilityFeed.close(); frameFeed.close(); batchFeed.close(); outcomeFeed.close()
            originClock.close()
            proofConsumerWake?.close()
            receiveWake?.close()
            filtering.close(); selection.close()
            diagnosticsFeed.close()
        }
        try { job.close() } finally { try { http.close() } finally { stream.close() } }
    }
    companion object { const val QUEUE_CAPACITY = 64 }
}
