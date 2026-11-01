package com.no5.helm.network

import com.no5.domain.*
import android.os.Trace
import com.no5.helm.security.GatewaySecurityProfile
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A separate owned feature worker. It cannot hold the fader WSS or knob admission lock. */
internal class NetworkAudioSound(
    private val identity:SourceIdentity, private val transport:PiHttpTransport,
    scheduler:PiPollScheduler, private val spectrumTransport:AudioSpectrumTransport?=null,
    // Local intervals/admission/receipt validity use a monotonic clock, never wall-clock adjustment.
    private val now:()->Long={System.nanoTime()/1_000_000},
    private val commandTransport:AudioSoundCommandTransport?=null,
    private val readExecutor:java.util.concurrent.Executor=java.util.concurrent.ThreadPoolExecutor(
        1,1,0,TimeUnit.MILLISECONDS,java.util.concurrent.ArrayBlockingQueue(1),
        java.util.concurrent.ThreadFactory{r->Thread(r,"no5-sound-read").apply{isDaemon=true}}),
):AudioSoundPort {
    private val lock=Any();private var closed=false;private var visible=false;private var eqVisible=false
    private val feed=MutableSourceFeed(AudioSoundState(reason="NOT_STARTED"));private val spectra=MutableSourceFeed<AudioSpectrumFrame?>(null);private val outcomes=MutableSourceFeed<AudioSoundResult?>(null)
    override val state:SourceFeed<AudioSoundState> get()=feed
    override val spectrum:SourceFeed<AudioSpectrumFrame?> get()=spectra
    override val results:SourceFeed<AudioSoundResult?> get()=outcomes
    private data class Job(val id:Long,val command:AudioSoundCommand,val base:AudioSoundState,var gateway:String?=null,val acceptedAt:Long,var beganPost:Boolean=false,val previewDependency:Long?=null,var sentAt:Long=0)
    // Two bounded scope goals; a Save cannot commit a rejected or unread goal.
    private var presetGoal:Long?=null;private var calibrationGoal:Long?=null
    private var confirmedPreset:Pair<Long,AudioSoundCommand.Preview>?=null;private var confirmedCalibration:Pair<Long,AudioSoundCommand.Preview>?=null
    private fun acceptedPreview(job:Job){(job.command as? AudioSoundCommand.Preview)?.let{if(it.scope==SoundScope.PRESET)presetGoal=job.id else calibrationGoal=job.id}}
    private val ring=arrayOfNulls<Job>(64);private var head=0;private var count=0;private var sequence=0L;private var active:Job?=null
    private val history=arrayOfNulls<AudioSoundResult>(256);private var historyWrite=0
    private val diagnostics=arrayOfNulls<String>(64);private var diagnosticWrite=0
    private val pumping=AtomicBoolean(false);private var ownedPreviewId:String?=null;private var refreshNeeded=true;private var lastRead=0L;private var lastKeepalive=0L;private var verifiedLeaseDeadline:Long?=null
    private val commandSession=java.util.UUID.randomUUID().toString();private var server:String?=null;private var revision=0L
    private var stream:SourceSubscription?=null;private var streamAttempt=0L;private var lastSpectrumAt=0L;private var spectrumSequence=-1L;private var streamEpoch=0L
    private var commandStream:SourceSubscription?=null;private var commandEpoch=0L;private var commandAttempt=0L
    private var commandReady=false;private var commandFailed=false;private var commandConnectedAt=0L;private var lastCommandSent=Long.MIN_VALUE/2
    private var commandAuthorityRetired=false
    private var credentialRotationPending=false
    private val commandMessages=java.util.ArrayDeque<String>(16)
    private val inFlight=linkedMapOf<Long,Job>()
    // HTTP can take seconds. It must never occupy the receipt/lease/admission owner.
    // At most one periodic read and one reconciliation exist; completions return
    // to that owner, never publish directly from an IO callback.
    private val readCompletions=java.util.ArrayDeque<()->Unit>(2)
    private var periodicReadPending=false
    private var reconcilingJob:Long?=null
    private var authorityVersion=0L
    private var pendingReadCount=0
    private val task=try{scheduler.schedule(if(commandTransport==null)100 else 20,::pump)}catch(failure:RuntimeException){commandTransport?.close();spectrumTransport?.close();transport.close();throw failure}
    /** Only the factory calls this AFTER verified same-origin/issuer/role/CA credential renewal.
     * Drain old sends, retain unsent local goals, then authenticate a new socket. No replay.
     */
    internal fun credentialsRenewed(){synchronized(lock){if(!closed&&commandStream!=null){credentialRotationPending=true;wake()}}}
    private var experimentalAllowed=false
    override fun setExperimentalAllowed(allowed:Boolean){synchronized(lock){experimentalAllowed=allowed&&visible&&state.value.available&&state.value.capabilities.experimentalEq;if(experimentalAllowed)commandAuthorityRetired=false}}
    override fun refresh(){synchronized(lock){refreshNeeded=true}}
    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean){synchronized(lock){if(this.visible!=visible)soundDiagnostic(if(visible)"EDITOR_SHOWN"else"EDITOR_HIDDEN");this.visible=visible;if(!visible){experimentalAllowed=false;retireCommands("SOURCE_HIDDEN")};this.eqVisible=visible&&eqVisible;refreshNeeded=true;if(!this.eqVisible){streamEpoch++;stream?.close();stream=null;spectra.emit(null)}}}
    override fun submit(command:AudioSoundCommand):Long=synchronized(lock){
        if(closed || !visible || !state.value.available || !state.value.writable)return@synchronized 0L
        if(command is AudioSoundCommand.Preview && command.eqEditing?.experimental==true && !experimentalAllowed)return@synchronized 0L
        if(command is AudioSoundCommand.Keepalive && command.experimental==true && (!experimentalAllowed || state.value.eqEditing?.experimental!=true))return@synchronized 0L
        if((command is AudioSoundCommand.SaveAs || command is AudioSoundCommand.LoadPreset) && !state.value.capabilities.presetLibrary)return@synchronized 0L
        if(command is AudioSoundCommand.DeletePreset && !state.value.capabilities.presetManagement)return@synchronized 0L
        if(command.previewId.length !in 1..100 || sequence==Long.MAX_VALUE)return@synchronized 0L
        if(command is AudioSoundCommand.Preview)ownedPreviewId=command.previewId
        val id=++sequence;val b=command.basis;val base=if(b==null)state.value else state.value.copy(configGeneration=b.configGeneration,presetRevision=b.presetRevision,calibrationRevision=b.calibrationRevision)
        val dependency=(command as? AudioSoundCommand.Save)?.let{if(it.scope==SoundScope.PRESET)presetGoal else calibrationGoal}
        val job=Job(id,command,base,acceptedAt=now(),previewDependency=dependency)
        if(command is AudioSoundCommand.Preview){
            for(offset in count-1 downTo 0){val at=(head+offset)%64;val old=ring[at]?:continue
                if(old.command !is AudioSoundCommand.Preview)break
                if(old.command.previewId==command.previewId && old.command.scope==command.scope){ring[at]=job;acceptedPreview(job);result(AudioSoundResult(old.id,"SUPERSEDED"));result(AudioSoundResult(id,"ACCEPTED"));wake();return@synchronized id}
            }
        }
        if(count==64)return@synchronized 0L
        ring[(head+count)%64]=job;count++;acceptedPreview(job);result(AudioSoundResult(id,"ACCEPTED"));wake();id
    }
    private fun wake(){(task as? WakeablePollSubscription)?.requestNow()}
    private fun result(value:AudioSoundResult){history[historyWrite]=value;historyWrite=(historyWrite+1)%256;diagnostics[diagnosticWrite]="${value.id}:${value.status}";diagnosticWrite=(diagnosticWrite+1)%64;outcomes.emit(value)}
    private fun pump(){
        if(!pumping.compareAndSet(false,true))return
        try{
            drainCommands()
            drainReads()
            val time=now()
            synchronized(lock){if(verifiedLeaseDeadline?.let{time>=it}==true){
                verifiedLeaseDeadline=null;refreshNeeded=true
                authorityVersion++;feed.emit(state.value.copy(available=false,writable=false,reason="PREVIEW_EXPIRED"))
            }}
            // A periodic read must not consume the renewal budget of an owned preview.
            val renewal=synchronized(lock){
                state.value.preview?.takeIf{visible && it.id==ownedPreviewId && time-lastKeepalive>=1000 && active==null && count==0 && inFlight.isEmpty()}
            }
            if(renewal!=null && (state.value.eqEditing?.experimental!=true || experimentalAllowed))
                submit(AudioSoundCommand.Keepalive(renewal.id,if(state.value.eqEditing?.experimental==true)true else null))
            val doRead=synchronized(lock){if(closed)return
                visible && !periodicReadPending && (refreshNeeded || time-lastRead>=1000) && active==null && count==0 && inFlight.isEmpty()}
            if(doRead)startPeriodicRead()
            drainReads()
            var streamingJob:Job?=null
            synchronized(lock){
                if(closed)return
                if(!eqVisible || !state.value.capabilities.spectrum){streamEpoch++;stream?.close();stream=null;spectra.emit(null)}
                else if(stream==null && time-streamAttempt>=1000){streamAttempt=time;startSpectrum()}
                if(spectra.value!=null && time-lastSpectrumAt>1000)spectra.emit(null)
                val duplex=commandTransport!=null && state.value.capabilities.commandStream
                if(credentialRotationPending&&inFlight.isEmpty()&&active==null){
                    commandEpoch++;commandStream?.close();commandStream=null;commandReady=false;commandMessages.clear()
                    credentialRotationPending=false;commandAttempt=0
                    soundDiagnostic("CREDENTIAL_CHANNEL_ROTATED")
                }
                if(visible&&duplex&&commandStream==null&&time-commandAttempt>=1000)startCommands()
                if(!duplex)retireCommands("SOURCE_CHANGED")
                // Discrete work stays a FIFO barrier until all admitted preview goals complete.
                if(active==null && count>0 && (!duplex || inFlight.isEmpty() || streamable(ring[head]!!.command))){
                    val next=ring[head]!!
                    if(duplex&&streamable(next.command)){
                        // One active native preparation. submit() continuously replaces the
                        // single unsent scope goal; pointer cadence never creates DSP backlog.
                        if(!credentialRotationPending&&commandReady&&inFlight.isEmpty()){
                            ring[head]=null;head=(head+1)%64;count--;streamingJob=next
                        }
                    }else if(inFlight.isEmpty()){active=next;ring[head]=null;head=(head+1)%64;count--}
                }
            }
            streamingJob?.let(::sendCommand)
            val job=synchronized(lock){active}
            if(job!=null)execute(job)

        }finally{pumping.set(false)}
    }
    private fun streamable(command:AudioSoundCommand)=command is AudioSoundCommand.Preview || command is AudioSoundCommand.Keepalive
    private fun streamKey(command:AudioSoundCommand):List<Any?> = when(command){
        is AudioSoundCommand.Preview->listOf(command.previewId,command.scope,command.eqEditing?.experimental,
            command.eqEditing?.previousPoint?.id,command.preset?.eqEnabled,command.preset?.reserveDb)
        else->listOf(command.previewId,"renewal")
    }
    private fun startCommands(){
        val wire=commandTransport?:return;val epoch=++commandEpoch
        commandAttempt=now();commandConnectedAt=now();commandReady=false;commandFailed=false
        // commandSession is the same opaque UUID included in requestId on the
        // wire, so Gateway-side request records can be matched without logging
        // source values, request bodies, endpoint data, or credentials.
        soundDiagnostic("WSS_STREAM_OPEN_ATTEMPT_SESSION_${commandSession}_EPOCH_$epoch")
        commandStream=wire.connect({text->synchronized(lock){
            if(closed||!visible||epoch!=commandEpoch)return@connect
            if(commandMessages.size==16){soundDiagnostic("WSS_RECEIPT_QUEUE_FULL");commandFailed=true}else commandMessages.addLast(text)
            wake()
        }},{synchronized(lock){if(!closed&&epoch==commandEpoch){soundDiagnostic("WSS_STREAM_FAILED_SESSION_${commandSession}_EPOCH_$epoch");commandFailed=true;wake()}}})
    }
    private fun retireCommands(reason:String){
        if(commandStream==null&&inFlight.isEmpty())return
        soundDiagnostic("STREAM_RETIRED_$reason")
        commandEpoch++;commandStream?.close();commandStream=null;commandReady=false;commandMessages.clear();commandFailed=false;credentialRotationPending=false
        inFlight.values.toList().forEach{finish(it,"UNKNOWN",reason)}
        // No queued goal from a failed/retired transport may restart a preview on reconnect.
        while(count>0){ring[head]?.let{result(AudioSoundResult(it.id,"REJECTED",reason))};ring[head]=null;head=(head+1)%64;count--}
        ownedPreviewId=null;experimentalAllowed=false;commandAuthorityRetired=true;refreshNeeded=true
        authorityVersion++;feed.emit(state.value.copy(available=false,writable=false,reason=reason))
    }
    private fun drainCommands(){
        synchronized(lock){
            if(commandFailed || commandStream!=null&&!commandReady&&now()-commandConnectedAt>=1500){soundDiagnostic(if(commandFailed)"RETIRE_TRANSPORT_FAILURE"else"RETIRE_READY_TIMEOUT");retireCommands("COMPLETION_UNKNOWN");return}
        }
        while(true){
            val text=synchronized(lock){commandMessages.pollFirst()}?:break
            var stage="FRAME"
            try{
                val message=JSONObject(text)
                if(message.optString("kind")=="sound-command-ready"){
                    stage="READY"
                    synchronized(lock){require(!commandReady&&message.getInt("version")==1&&message.getString("serverSessionId")==server&&message.getInt("window") in 1..4);commandReady=true}
                    soundDiagnostic("STREAM_READY")
                }else{
                    stage="REQUEST_MATCH"
                    val requestId=message.getString("requestId")
                    val job=synchronized(lock){inFlight.values.firstOrNull{"$commandSession:${it.id}"==requestId}}?:error("unknown sound receipt")
                    stage="ADMISSION_AGE"
                    require(now()-job.acceptedAt<4500)
                    stage="RECEIPT_FENCE"
                    consumeReceipt(job,message,job.sentAt,true)
                }
            }catch(_:Exception){soundDiagnostic("STREAM_REJECTED_$stage");synchronized(lock){retireCommands("COMPLETION_UNKNOWN")};return}
        }
        synchronized(lock){if(inFlight.values.any{now()-it.acceptedAt>=4500}){soundDiagnostic("RETIRE_ADMISSION_EXPIRED");retireCommands("COMPLETION_UNKNOWN")}}
    }
    private fun sendCommand(job:Job){
        val input=synchronized(lock){
            if(now()-job.acceptedAt>=4500){result(AudioSoundResult(job.id,"EXPIRED","ADMISSION_EXPIRED"));return}
            val edit=(job.command as? AudioSoundCommand.Preview)?.eqEditing
            if(!visible || edit?.experimental==true&&!experimentalAllowed){result(AudioSoundResult(job.id,"SUPERSEDED","EXPERIMENT_ENDED"));return}
            val endpoint=server?:return
            val observation=state.value
            if(job.base.configGeneration!=observation.configGeneration || !observation.writable){result(AudioSoundResult(job.id,"REJECTED","CONFIGURATION_CHANGED"));return}
            Triple(endpoint,revision,observation)
        }
        // JSON/codec work must never hold the UI admission/spectrum lock.
        val body=try{
            val preview=job.command as? AudioSoundCommand.Preview
            val pointId=preview?.eqEditing?.previousPoint?.id
            val command=if(pointId!=null){
                val actual=requireNotNull(input.third.preset);val requested=requireNotNull(preview.preset)
                require(actual.id==requested.id&&actual.routingTemplate==requested.routingTemplate&&actual.reserveDb==requested.reserveDb&&actual.eqEnabled==requested.eqEnabled)
                require(actual.eqPoints.map{it.id}==requested.eqPoints.map{it.id})
                if(preview.locallyValidated){
                    // A source actor must not silently mutate a kernel-validated curve.
                    // Ask its owner to revalidate the newest goal against changed peers.
                    if(actual.eqPoints.filter{it.id!=pointId}!=requested.eqPoints.filter{it.id!=pointId}){
                        synchronized(lock){result(AudioSoundResult(job.id,"SUPERSEDED","LOCAL_VALIDATION_STALE"))};return
                    }
                    preview
                }else preview.copy(preset=requested.copy(eqPoints=actual.eqPoints.map{p->if(p.id==pointId)requested.eqPoints.first{it.id==pointId}else p}),
                    eqEditing=preview.eqEditing!!.copy(previousPoint=actual.eqPoints.first{it.id==pointId}))
            }else job.command
            AudioSoundCodec.command(commandSession,"$commandSession:${job.id}",input.first,input.second,job.base,command)
        }catch(_:Exception){synchronized(lock){result(AudioSoundResult(job.id,"REJECTED","NOT_SENT"))};return}
        synchronized(lock){
            val edit=(job.command as? AudioSoundCommand.Preview)?.eqEditing
            if(closed||!visible||!commandReady||server!=input.first||state.value.configGeneration!=input.third.configGeneration||
                state.value.sourceGeneration!=input.third.sourceGeneration||!state.value.writable||edit?.experimental==true&&!experimentalAllowed){
                result(AudioSoundResult(job.id,"REJECTED","SOURCE_CHANGED"));return
            }
            job.beganPost=true;job.sentAt=now();inFlight[job.id]=job;lastCommandSent=now()
            soundDiagnostic("EQ_COMMAND_SENT_SESSION_${commandSession}_EPOCH_${commandEpoch}_JOB_${job.id}")
            if(!soundTrace("no5.eq.wss.send"){commandTransport?.send(body)==true}){soundDiagnostic("WSS_SEND_NOT_ACCEPTED");retireCommands("COMPLETION_UNKNOWN")}
        }
    }
    private data class ReadReply(val next:AudioSoundState?=null,val server:String?=null,val revision:Long=0,val failure:String?=null)
    private fun fetchRead():ReadReply {
        var stage="TRANSPORT"
        return try {
            val audioJson=transport.get("/v1/audio");stage="AUDIO_DECODE"
            val audio=AudioV1Decoder.state(audioJson)
            stage="TRANSPORT";val soundJson=transport.get("/v1/audio/sound");stage="SOUND_DECODE"
            ReadReply(AudioSoundCodec.state(soundJson),audio.serverSessionId,audio.state.revision)
        }catch(failure:Exception){
            ReadReply(failure=if(failure is PiHttpStatusException && failure.statusCode in 100..599)"HTTP_${failure.statusCode}"else stage)
        }
    }
    private fun applyRead(reply:ReadReply,accept:((AudioSoundState,String)->Boolean)?=null){
        synchronized(lock){if(closed)return
            var failure=reply.failure
            val next=reply.next;val endpoint=reply.server
            if(next!=null&&endpoint!=null){
                if(accept?.invoke(next,endpoint)==false)failure="RECONCILE_FENCE"
                else {
                    if(server!=null && server!=endpoint){experimentalAllowed=false;drop("SOURCE_CHANGED");spectra.emit(null);spectrumSequence=-1}
                    if(feed.value.sourceGeneration!=next.sourceGeneration){experimentalAllowed=false;spectra.emit(null);spectrumSequence=-1}
                    server=endpoint;revision=reply.revision;authorityVersion++
                    feed.emit(if(commandAuthorityRetired&&next.eqEditing?.experimental==true&&!experimentalAllowed)next.copy(available=false,writable=false,reason="EXPERIMENT_ENDED")else if(next.available)next else next.copy(reason="BACKEND_UNAVAILABLE"))
                    verifiedLeaseDeadline=null;lastRead=now();refreshNeeded=false;return
                }
            }
            val reason=failure?:"SOUND_DECODE"
            soundDiagnostic("READ_FAILED_$reason");experimentalAllowed=false;authorityVersion++
            feed.emit(state.value.copy(available=false,writable=false,reason=reason));spectra.emit(null);lastRead=now();refreshNeeded=false
        }
    }
    private fun read(accept:((AudioSoundState,String)->Boolean)?=null)=applyRead(fetchRead(),accept)
    private fun startRead(completed:(ReadReply)->Unit){
        // Include finished-but-not-yet-drained work in the bound. Retired
        // sessions cannot build up a second hidden queue of HTTP completions.
        synchronized(lock){if(pendingReadCount>=2){completed(ReadReply(failure="READ_WORKER_BUSY"));return};pendingReadCount++}
        fun post(reply:ReadReply){synchronized(lock){if(closed)return;check(readCompletions.size<2);readCompletions.addLast{completed(reply)}};wake()}
        try{readExecutor.execute{post(fetchRead())}}
        catch(_:java.util.concurrent.RejectedExecutionException){post(ReadReply(failure="READ_WORKER_UNAVAILABLE"))}
    }
    private fun drainReads(){while(true){val next=synchronized(lock){readCompletions.pollFirst()?.also{pendingReadCount--}}?:return;next()}}
    private fun startPeriodicRead(){
        val version=synchronized(lock){periodicReadPending=true;authorityVersion}
        startRead {reply->synchronized(lock){
            periodicReadPending=false
            // A receipt/source/lease decision that happened after this GET began
            // wins, including over a late HTTP failure. Never roll it back.
            if(!closed&&visible&&version==authorityVersion&&inFlight.isEmpty())applyRead(reply)
        }}
    }
    private fun execute(job:Job){
        if(now()-job.acceptedAt>=4500){finish(job,if(job.beganPost)"UNKNOWN"else"EXPIRED",if(job.beganPost)"COMPLETION_UNKNOWN"else"ADMISSION_EXPIRED");return}
        try{
            val edit=(job.command as? AudioSoundCommand.Preview)?.eqEditing
            if(!job.beganPost && edit?.experimental==true && (!visible || !experimentalAllowed)){finish(job,"SUPERSEDED","EXPERIMENT_ENDED");return}
            val renewal=job.command as? AudioSoundCommand.Keepalive
            if(!job.beganPost && renewal?.experimental==true && (!visible || !experimentalAllowed || state.value.eqEditing?.experimental!=true)){finish(job,"SUPERSEDED","EXPERIMENT_ENDED");return}
            val save=job.command as? AudioSoundCommand.Save
            if(save!=null && job.previewDependency!=null){
                val confirmed=if(save.scope==SoundScope.PRESET)confirmedPreset else confirmedCalibration
                val matches=confirmed!=null && confirmed.first==job.previewDependency && confirmed.second.previewId==save.previewId &&
                    (if(save.scope==SoundScope.PRESET)SoundNativeReadback.preset(state.value.preset,confirmed.second.preset) else state.value.calibration==confirmed.second.calibration)
                if(!matches){finish(job,"REJECTED","PREVIEW_NOT_CONFIRMED");synchronized(lock){refreshNeeded=true};return}
            }
            val endpoint=server?:run{finish(job,"REJECTED","NO_GATEWAY_SESSION");return}
            val requestId="$commandSession:${job.id}"
            val ioStarted=now()
            val json=soundTrace("no5.eq.http"){if(job.gateway==null){
                // Generation checks immediately before the only POST. No command replay on uncertain response.
                if(job.base.configGeneration!=state.value.configGeneration || !state.value.writable){finish(job,"REJECTED","CONFIGURATION_CHANGED");return}
                val body=AudioSoundCodec.command(commandSession,requestId,endpoint,revision,job.base,job.command)
                job.beganPost=true
                transport.post("/v1/audio/commands",body)
            }else transport.get("/v1/audio/commands/${job.gateway}")}
            consumeReceipt(job,JSONObject(json),ioStarted,false)
        }catch(_:Exception){finish(job,if(job.beganPost)"UNKNOWN"else"REJECTED",if(job.beganPost)"COMPLETION_UNKNOWN"else"NOT_SENT");synchronized(lock){refreshNeeded=true}}
    }
    private fun consumeReceipt(job:Job,o:JSONObject,ioStarted:Long,duplex:Boolean){
        val requestId="$commandSession:${job.id}";val endpoint=server
        val edit=(job.command as? AudioSoundCommand.Preview)?.eqEditing
        require(o.getInt("version")==1 && o.getString("kind")=="audio-command-result" && o.getString("requestId")==requestId)
        if(o.has("serverSessionId"))require(o.getString("serverSessionId")==endpoint)
        if(o.has("sessionId"))require(o.getString("sessionId")==commandSession)
        val id=o.getString("commandId").also{require(it.matches(Regex("[A-Za-z0-9_-]{1,64}")))}
        require(job.gateway==null || job.gateway==id);job.gateway=id
        val status=o.getString("status");require(status in setOf("ACCEPTED","RUNNING","APPLIED","REJECTED","FAILED","UNKNOWN","SUPERSEDED","EXPIRED"))
        if(status=="APPLIED" && job.command !is AudioSoundCommand.Keepalive)
            require(o.optString("verification")==if(job.command is AudioSoundCommand.DeletePreset)"SOUND_STATE_COMMIT"else"AUDIO_BLOCK_READBACK")
        if(status !in setOf("ACCEPTED","RUNNING")){
            if(duplex&&status=="APPLIED"&&edit?.experimental==true&&!experimentalAllowed){finish(job,"SUPERSEDED","EXPERIMENT_ENDED");return}
            if(duplex){
                if(status=="APPLIED"){
                    val before=state.value
                    when(soundTrace("no5.eq.verified"){verifiedReadback(o,job,ioStarted)}){
                        ReadbackProof.VALID->Unit
                        ReadbackProof.STALE->{
                            if(reconcilingJob==job.id)return
                            reconcilingJob=job.id
                            val reconcileStarted=now()
                            startRead {reply->
                                if(reconcilingJob==job.id)reconcilingJob=null
                                if(closed||!visible||inFlight[job.id]!==job)return@startRead
                                try {
                                    var reconciled=false
                                    val proofMeta=o.getJSONObject("soundReadback")
                                    var leaseDeadline:Long?=null
                                    applyRead(reply){live,liveServer->
                                        val remaining=proofMeta.optLong("remainingLeaseMs",-1)
                                        val elapsed=(now()-ioStarted).coerceAtLeast(0)
                                        val leaseValid=if(live.preview==null)proofMeta.isNull("remainingLeaseMs")else
                                            remaining in 1..5000&&remaining-elapsed>100
                                        val valid=!closed&&visible&&inFlight[job.id]===job&&now()-job.acceptedAt<4500&&
                                            (now()-reconcileStarted).coerceAtLeast(0)<=1000&&server==endpoint&&liveServer==endpoint&&live.available&&live.writable&&
                                            live.configGeneration==job.base.configGeneration&&live.sourceGeneration==job.base.sourceGeneration&&
                                            live.configurationRevision>=before.configurationRevision&&live.presetRevision>=before.presetRevision&&
                                            live.calibrationRevision>=before.calibrationRevision&&
                                            (job.command !is AudioSoundCommand.Preview&&job.command !is AudioSoundCommand.Keepalive||live.preview?.id==job.command.previewId)&&
                                            (edit==null||live.eqEditing?.experimental==edit.experimental)&&
                                            (live.eqEditing?.experimental!=true||experimentalAllowed)&&leaseValid
                                        if(valid&&live.preview!=null)leaseDeadline=ioStarted+remaining
                                        reconciled=valid;valid
                                    }
                                    require(reconciled)
                                    synchronized(lock){verifiedLeaseDeadline=leaseDeadline}
                                    completeReceipt(job,o,ioStarted)
                                }catch(_:Exception){soundDiagnostic("RECONCILIATION_REJECTED elapsed_ms=${(now()-reconcileStarted).coerceAtLeast(0)}");synchronized(lock){retireCommands("COMPLETION_UNKNOWN")}}
                            }
                            return
                        }
                        ReadbackProof.INVALID->error("invalid sound readback")
                    }
                }
            }else
            if(status!="APPLIED" || soundTrace("no5.eq.verified"){verifiedReadback(o,job,ioStarted)}!=ReadbackProof.VALID)soundTrace("no5.eq.reconcile"){read()}
            completeReceipt(job,o,ioStarted)
        }
    }
    private fun completeReceipt(job:Job,o:JSONObject,ioStarted:Long){
        val status=o.getString("status");val edit=(job.command as? AudioSoundCommand.Preview)?.eqEditing
        if(status=="APPLIED" && edit!=null){
            require(state.value.available && state.value.writable && state.value.configGeneration==job.base.configGeneration)
            require(state.value.preview?.id==job.command.previewId && state.value.eqEditing?.experimental==edit.experimental)
        }
        if(status=="APPLIED" && state.value.preview?.id==job.command.previewId)synchronized(lock){lastKeepalive=ioStarted}
        finish(job,status,o.optString("reason",o.optString("message",status)).take(160))
    }
    private enum class ReadbackProof { VALID, STALE, INVALID }
    /** Only a fresh, fully fenced proof can publish inline. WSS reconciles age failures by live GET. */
    private fun verifiedReadback(receipt:JSONObject,job:Job,ioStarted:Long):ReadbackProof {
      var stage="ENVELOPE"
      return try {
        require(receipt.getString("sessionId")==commandSession && receipt.getString("serverSessionId")==server)
        require(receipt.getString("verification")==if(job.command is AudioSoundCommand.DeletePreset)"SOUND_STATE_COMMIT"else"AUDIO_BLOCK_READBACK")
        val meta=receipt.getJSONObject("soundReadback")
        require(meta.getString("schema")=="no5.sound-command-readback/1")
        stage="FRESHNESS"
        val elapsed=(now()-ioStarted).coerceAtLeast(0)
        val age=meta.getLong("ageMs")
        if(age<0 || age+elapsed>1000)soundDiagnostic("READBACK_TIMING elapsed_ms=$elapsed age_ms=$age work_ms=${meta.optLong("workMs",-1)}")
        require(age>=0 && age+elapsed<=1000)
        stage="MODEL"
        val next=AudioSoundCodec.state(meta.getJSONObject("state").toString())
        stage="SOURCE_REVISION"
        require(next.available && next.writable && next.configGeneration==job.base.configGeneration &&
            next.sourceGeneration==job.base.sourceGeneration && next.configurationRevision>=state.value.configurationRevision &&
            next.presetRevision>=state.value.presetRevision && next.calibrationRevision>=state.value.calibrationRevision)
        // SOUND owns its state separately: decode only the verified AUDIO envelope/CAS fields.
        val audio=receipt.getJSONObject("state")
        stage="CONTROL_REVISION"
        require(audio.getInt("version")==1 && audio.getString("kind")=="audio" && audio.getString("serverSessionId")==server)
        val rawRevision=audio.get("revision");require(rawRevision is Number)
        val controlRevision=rawRevision.toLong();require(rawRevision.toDouble().isFinite() && controlRevision in 0..9007199254740991L && rawRevision.toDouble()==controlRevision.toDouble())
        if(meta.has("workMs")){val ms=meta.getLong("workMs");if(ms in 0..5000)try{Trace.setCounter("no5.eq.gateway_work_ms",ms)}catch(_:RuntimeException){}}
        stage="PREVIEW_LEASE"
        val remaining=if(next.preview==null){require(meta.isNull("remainingLeaseMs"));null}else{
            val value=meta.getLong("remainingLeaseMs");require(value in 1..5000 && value-elapsed>100)
            if(job.command is AudioSoundCommand.Preview || job.command is AudioSoundCommand.Keepalive)
                require(next.preview?.id==job.command.previewId)
            value-elapsed
        }
        synchronized(lock){
            stage="LIVE_EDITOR"
            require(!closed && visible && job.base.configGeneration==state.value.configGeneration)
            if(next.eqEditing?.experimental==true)require(experimentalAllowed)
            revision=controlRevision;verifiedLeaseDeadline=remaining?.let{now()+it}
            authorityVersion++;soundTrace("no5.eq.publish"){feed.emit(next)};lastRead=now();refreshNeeded=false
        }
        ReadbackProof.VALID
      }catch(_:Exception){soundDiagnostic("READBACK_REJECTED_$stage");if(stage=="FRESHNESS")ReadbackProof.STALE else ReadbackProof.INVALID}
    }

    private fun finish(job:Job,status:String,reason:String=""){synchronized(lock){if(active?.id!=job.id&&inFlight[job.id]!==job)return
        if(status !in setOf("APPLIED","SUPERSEDED"))soundDiagnostic("COMMAND_$status")
        if(job.command is AudioSoundCommand.Keepalive&&status=="APPLIED")soundDiagnostic("KEEPALIVE_CONFIRMED")
        (job.command as? AudioSoundCommand.Preview)?.let{preview->
            val actual=if(preview.eqEditing!=null&&preview.scope==SoundScope.PRESET)preview.copy(preset=state.value.preset)else preview
            val confirmed=if(status=="APPLIED" && (preview.eqEditing==null || state.value.eqEditing?.experimental==preview.eqEditing?.experimental))job.id to actual else null
            if(preview.scope==SoundScope.PRESET){if(job.id>=(confirmedPreset?.first?:0))confirmedPreset=confirmed}
            else if(job.id>=(confirmedCalibration?.first?:0))confirmedCalibration=confirmed
        }
        if(active?.id==job.id)active=null;inFlight.remove(job.id);result(AudioSoundResult(job.id,status,reason));if(status!="APPLIED")refreshNeeded=true;if(count>0)wake()}}
    private fun drop(reason:String){retireCommands(reason);presetGoal=null;calibrationGoal=null;confirmedPreset=null;confirmedCalibration=null;active?.let{result(AudioSoundResult(it.id,"UNKNOWN",reason))};active=null;while(count>0){ring[head]?.let{result(AudioSoundResult(it.id,"REJECTED",reason))};ring[head]=null;head=(head+1)%64;count--}}
    private fun startSpectrum(){
        val t=spectrumTransport?:return
        val epoch=++streamEpoch
        stream=t.connect({ value->try{val frame=soundTrace("no5.eq.spectrum.receive"){AudioSoundCodec.spectrum(value)};synchronized(lock){if(closed||!eqVisible||epoch!=streamEpoch)return@connect
            if(frame==null){spectra.emit(null);return@connect}
            if(frame.sourceGeneration!=state.value.sourceGeneration || frame.sequence<=spectrumSequence)return@connect
            spectrumSequence=frame.sequence;val stamp=now();if(lastSpectrumAt!=0L && stamp-lastSpectrumAt<100)return@connect;lastSpectrumAt=stamp;spectra.emit(frame)
        }}catch(_:Exception){synchronized(lock){if(epoch==streamEpoch){spectra.emit(null);stream?.close();stream=null}}} },{synchronized(lock){if(epoch==streamEpoch){spectra.emit(null);stream=null}}})
    }
    override fun close(){synchronized(lock){if(closed)return;soundDiagnostic("SOURCE_CLOSED");closed=true;visible=false;drop("SOURCE_CLOSED");streamEpoch++;stream?.close();stream=null};task.close();(readExecutor as? java.util.concurrent.ExecutorService)?.shutdownNow();commandTransport?.close();spectrumTransport?.close();transport.close();feed.close();spectra.close();outcomes.close()}
}
internal interface AudioSpectrumTransport:AutoCloseable {fun connect(onText:(String)->Unit,failure:()->Unit):SourceSubscription}
internal class OkHttpAudioSpectrumTransport(endpoint:String,security:GatewaySecurityProfile?,resolver:GatewayEndpointResolver):AudioSpectrumTransport {
    private val url=endpoint.trimEnd('/')+"/v1/audio/spectrum"
    private val client=OkHttpClient.Builder().apply{security?.configureOkHttp{factory,manager->sslSocketFactory(factory,manager)};if(security!=null)dns(GatewayOkHttpDns(endpoint,resolver,1500))}.connectTimeout(1500,TimeUnit.MILLISECONDS).readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(5,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val securityProfile=security;private var socket:WebSocket?=null
    override fun connect(onText:(String)->Unit,failure:()->Unit):SourceSubscription {
        val cancelled=AtomicBoolean(false);fun fail(){if(cancelled.compareAndSet(false,true)){socket?.cancel();socket=null;failure()}}
        val request=Request.Builder().url(url).header("Sec-WebSocket-Protocol","no5.audio.spectrum.v1").header("Cache-Control","no-cache, no-store")
        securityProfile?.configureHttps(url,configureTls={},setHeader=request::header)
        socket=client.newWebSocket(request.build(),object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){if(response.header("Sec-WebSocket-Protocol")!="no5.audio.spectrum.v1")fail()}
            override fun onMessage(webSocket:WebSocket,text:String){if(!cancelled.get()){if(text.toByteArray().size>32768)fail()else onText(text)}}
            override fun onMessage(webSocket:WebSocket,bytes:ByteString)=fail()
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String)=fail()
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String)=fail()
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?)=fail()
        })
        return SourceSubscription{cancelled.set(true);socket?.cancel();socket=null}
    }
    override fun close(){socket?.cancel();socket=null;client.dispatcher.executorService.shutdownNow();client.connectionPool.evictAll()}
}

private inline fun <T> soundTrace(name:String,block:()->T):T {
    // Diagnostics must never change admission or completion, including unavailable platform tracing.
    val traced=try{Trace.beginSection(name);true}catch(_:RuntimeException){false}
    return try{block()}finally{if(traced)try{Trace.endSection()}catch(_:RuntimeException){}}
}

/** Fixed status codes only: never URLs, keys, exception text, presets or operator values. */
internal fun soundDiagnostic(code:String){try{android.util.Log.d("No5EqFlow",code)}catch(_:RuntimeException){}}
