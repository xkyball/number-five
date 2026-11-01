package com.no5.helm.network

import com.no5.domain.*
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class AudioSoundDuplexTest {
    private class Clock {var value=10000L}
    private class Scheduler:PiPollScheduler {lateinit var pump:()->Unit
        override fun schedule(intervalMs:Long,poll:()->Unit):SourceSubscription{pump=poll;return object:WakeablePollSubscription{override fun requestNow()=true;override fun close(){}}}}
    private class Wire:AudioSoundCommandTransport {
        lateinit var text:(String)->Unit;lateinit var failure:()->Unit;val sent=mutableListOf<JSONObject>();var retired=0;var onSend:(()->Unit)?=null
        override fun connect(text:(String)->Unit,failure:()->Unit):SourceSubscription{this.text=text;this.failure=failure;return SourceSubscription{retired++}}
        override fun send(value:String):Boolean{sent+=JSONObject(value);onSend?.invoke();return true}
        override fun close(){}
    }
    private class ReadLane:java.util.concurrent.Executor {
        val work=java.util.ArrayDeque<Runnable>()
        override fun execute(command:Runnable){check(work.size<2);work.addLast(command)}
        fun drain(){while(work.isNotEmpty())work.removeFirst().run()}
    }
    private class Rig(twoPoints:Boolean=false,val reads:java.util.concurrent.Executor=java.util.concurrent.Executor{it.run()}) {
        val time=Clock();val scheduler=Scheduler();val wire=Wire();var posts=0;var gets=0
        var live:JSONObject?=null;var readFailure=false;var getDelayMs=0L;var onGet:(()->Unit)?=null
        val source=soundFixture().copy(preset=soundFixture().preset!!.copy(eqEnabled=true,
            eqPoints=soundFixture().preset!!.eqPoints+if(twoPoints)listOf(SoundEqPoint("second",4000.0))else emptyList()),headroomDb=12.0,
            capabilities=soundFixture().capabilities.copy(commandStream=true))
        fun model(gain:Double)=source.preset!!.copy(eqPoints=source.preset!!.eqPoints.mapIndexed{i,p->if(i==0)p.copy(gainDb=gain)else p})
        fun state(model:SoundPreset=source.preset!!,revision:Long=11)=soundWire(source.copy(preset=model,configurationRevision=revision))
            .apply{getJSONObject("capabilities").put("commandStream",true).put("eqProjection",true)
                put("eqEditing",JSONObject().put("schema","no5.eq-editing/1").put("previewProjection",true).put("maxExperimentalBoostDb",3)
                    .put("experimental",false).put("constrained",false).put("normalPreset",JSONObject.NULL))}
        val port=NetworkAudioSound(SourceIdentity("test",SourceMode.PI,"SOUND"),object:PiHttpTransport{
            override fun get(path:String):String{gets++;onGet?.invoke();time.value+=getDelayMs;if(readFailure)error("offline");return if(path=="/v1/audio")wireFixture("audio")else (live?:state()).toString()}
            override fun post(path:String,body:String):String{posts++;error("A streaming preview must never fall back to HTTP")}
            override fun close(){}
        },scheduler,now={time.value},commandTransport=wire,readExecutor=reads)
        init{port.setEditorVisible(true,false);scheduler.pump();(reads as? ReadLane)?.drain();scheduler.pump();wire.text(JSONObject().put("version",1).put("kind","sound-command-ready")
            .put("serverSessionId",JSONObject(wireFixture("audio")).getString("serverSessionId")).put("window",4).toString());scheduler.pump()}
        fun preview(gain:Double):Long=port.submit(AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=model(gain)))
        fun send(gain:Double):Long{time.value+=40;val id=preview(gain);scheduler.pump();return id}
        fun receipt(index:Int,status:String="APPLIED",revision:Long=12,malformed:Boolean=false):String {
            val body=wire.sent[index];val model=AudioSoundCodec.preset(body.getJSONObject("command").getJSONObject("model"))
            val sound=state(model,revision).put("preview",JSONObject().put("id","preview").put("expiresAtEpochMs",time.value+5000).put("presetDirty",true).put("calibrationDirty",false))
            val result=JSONObject().put("version",1).put("kind","audio-command-result").put("requestId",body.getString("requestId"))
                .put("sessionId",body.getString("sessionId")).put("serverSessionId",body.getString("serverSessionId"))
                .put("commandId","command-$index").put("status",status).put("verification","AUDIO_BLOCK_READBACK")
                .put("state",JSONObject(wireFixture("audio")))
                .put("soundReadback",JSONObject().put("schema","no5.sound-command-readback/1").put("ageMs",0).put("remainingLeaseMs",5000).put("state",sound))
            if(malformed)result.getJSONObject("soundReadback").put("remainingLeaseMs",0)
            return result.toString()
        }
    }
    @Test fun readyDeliveredBeforeDeadlineMustNotBeRetiredBehindPeriodicHttpRead(){
        val reads=ReadLane();val r=Rig(reads=reads)
        // Reconnect without a preview. A periodic HTTP read is allowed while
        // the new socket handshake is pending on the same feature worker.
        r.port.credentialsRenewed();r.scheduler.pump()
        val retiredBefore=r.wire.retired
        r.time.value+=1001
        var deliveredAt=0L
        r.onGet={
            if(deliveredAt==0L){
                deliveredAt=r.time.value
                r.wire.text(JSONObject().put("version",1).put("kind","sound-command-ready")
                    .put("serverSessionId",JSONObject(wireFixture("audio")).getString("serverSessionId")).put("window",1).toString())
                r.scheduler.pump() // The owner runs while the separate HTTP lane is blocked.
            }
        }
        r.getDelayMs=600
        r.scheduler.pump();reads.drain()
        assertEquals(11001L,deliveredAt,"READY arrived inside the unchanged 1500 ms handshake deadline")
        r.onGet=null;r.getDelayMs=0
        r.scheduler.pump()
        assertEquals(retiredBefore,r.wire.retired,"A timely queued READY must survive unrelated HTTP work")
        r.preview(2.0);r.scheduler.pump()
        assertEquals(1,r.wire.sent.size)
        r.port.close()
    }

    @Test fun aNewDragMustNotWaitForAnUnrelatedPeriodicRead(){
        val reads=ReadLane();val r=Rig(reads=reads);r.time.value+=1001
        var acceptedAt=0L;var sentAt=0L
        r.wire.onSend={sentAt=r.time.value}
        r.onGet={if(acceptedAt==0L){acceptedAt=r.time.value;assertTrue(r.preview(2.0)>0);r.scheduler.pump()}}
        r.getDelayMs=600;r.scheduler.pump();reads.drain();r.scheduler.pump()
        assertEquals(1,r.wire.sent.size)
        assertTrue(sentAt-acceptedAt<1000,"Unrelated two-stage read delayed the newest EQ goal by ${sentAt-acceptedAt} ms")
        r.port.close()
    }

    @Test fun latePeriodicSnapshotOrFailureCannotUndoNewerVerifiedSound(){
        for(fail in listOf(false,true)){
            val reads=ReadLane();val r=Rig(reads=reads);r.time.value+=1001;r.scheduler.pump()
            assertEquals(1,reads.work.size)
            r.preview(2.0);r.scheduler.pump();r.wire.text(r.receipt(0));r.scheduler.pump()
            assertEquals(2.0,r.port.state.value.preset!!.eqPoints.first().gainDb)
            r.readFailure=fail;reads.drain();r.scheduler.pump()
            assertTrue(r.port.state.value.available)
            assertEquals(2.0,r.port.state.value.preset!!.eqPoints.first().gainDb)
            assertTrue(r.port.state.value.preset!!.eqEnabled);assertEquals(0,r.posts);r.port.close()
        }
    }
    @Test fun hiddenOrClosedEditorCannotReceiveAReadAlreadyInProgress(){
        for(close in listOf(false,true)){
            val reads=ReadLane();val r=Rig(reads=reads);r.time.value+=1001;r.scheduler.pump()
            assertEquals(1,reads.work.size)
            val seen=mutableListOf<AudioSoundState>();r.port.state.subscribe{seen+=it}
            if(close)r.port.close()else r.port.setEditorVisible(false,false)
            val count=seen.size;reads.drain();r.scheduler.pump()
            assertEquals(count,seen.size)
            r.port.close()
        }
    }
    @Test fun slowReconciliationCannotBlockExpiryOrPublishAfterIt(){
        val reads=ReadLane();val r=Rig(reads=reads);val results=mutableListOf<AudioSoundResult>();r.port.results.subscribe{it?.let(results::add)}
        val id=r.send(2.0);val receipt=r.receipt(0)
        r.time.value+=1100;r.wire.text(receipt);r.scheduler.pump()
        assertEquals(1,reads.work.size);assertFalse(results.any{it.id==id&&it.status=="APPLIED"})
        r.time.value+=3400;r.scheduler.pump()
        assertTrue(results.any{it.id==id&&it.status=="UNKNOWN"})
        reads.drain();r.scheduler.pump()
        assertFalse(results.any{it.id==id&&it.status=="APPLIED"});assertEquals(0,r.posts);r.port.close()
    }
    @Test fun periodicReadRequestsRemainBoundedWhileTheReadWorkerIsBlocked(){
        val reads=ReadLane();val r=Rig(reads=reads);r.time.value+=1001
        repeat(1000){r.port.refresh();r.scheduler.pump()}
        assertEquals(1,reads.work.size)
        reads.drain();r.scheduler.pump();assertEquals(0,reads.work.size);r.port.close()
    }

    @Test fun aValidatedCurveIsNeverRebasedWithoutAnotherKernelCheck(){
        val r=Rig(true);val results=mutableListOf<AudioSoundResult>();r.port.results.subscribe{it?.let(results::add)}
        val original=r.source.preset!!;val first=original.eqPoints[0];val second=original.eqPoints[1]
        r.port.submit(AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=original.copy(eqPoints=original.eqPoints.map{if(it.id==first.id)it.copy(gainDb=1.0)else it}),eqEditing=SoundEqEditIntent(false,first)));r.scheduler.pump()
        val checked=original.copy(eqPoints=original.eqPoints.map{if(it.id==second.id)it.copy(gainDb=2.0)else it})
        val pending=r.port.submit(AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=checked,eqEditing=SoundEqEditIntent(false,second),locallyValidated=true))
        r.wire.text(r.receipt(0));r.scheduler.pump()
        assertEquals(1,r.wire.sent.size);assertTrue(results.any{it.id==pending&&it.status=="SUPERSEDED"&&it.reason=="LOCAL_VALIDATION_STALE"});assertEquals(0,r.posts);r.port.close()
    }
    @Test fun acceptanceIsImmediateButOnlyOnePreparationRunsAndReadbackIsAuthoritative(){
        val r=Rig();val initial=r.port.state.value.preset
        r.send(1.0);r.send(2.0);assertEquals(1,r.wire.sent.size);assertEquals(0,r.posts)
        r.wire.text(r.receipt(0,"ACCEPTED"));r.scheduler.pump();assertEquals(initial,r.port.state.value.preset)
        r.wire.text(r.receipt(0));r.scheduler.pump();assertEquals(2,r.wire.sent.size);assertEquals(1.0,r.port.state.value.preset!!.eqPoints.single().gainDb)
        r.wire.text(r.receipt(1,revision=13));r.scheduler.pump();assertEquals(2.0,r.port.state.value.preset!!.eqPoints.single().gainDb)
        assertTrue(r.port.state.value.preset!!.eqEnabled);r.port.close()
    }
    @Test fun oneInFlightAndOneReplaceableGoalFlushFinalValueBeforeDiscreteBarrier(){
        val r=Rig();r.send(1.0)
        repeat(1000){r.preview(2.0+it/1000.0)};r.scheduler.pump()
        assertEquals(1,r.wire.sent.size)
        // No extra 40-ms throttle after completion, including the final release target.
        r.wire.text(r.receipt(0));r.scheduler.pump();assertEquals(2,r.wire.sent.size)
        val finalGain=r.wire.sent.last().getJSONObject("command").getJSONObject("model").getJSONArray("eqPoints").getJSONObject(0).getDouble("gainDb")
        assertEquals(2.999,finalGain)
        r.port.submit(AudioSoundCommand.Save("preview",SoundScope.PRESET));r.scheduler.pump();assertEquals(0,r.posts)
        r.port.close()
    }
    @Test fun bothOldAndSingleWindowServersAreCompatibleButInvalidWindowsAreRejected(){
        val r=Rig();r.port.credentialsRenewed();r.scheduler.pump()
        r.wire.text(JSONObject().put("version",1).put("kind","sound-command-ready")
            .put("serverSessionId",JSONObject(wireFixture("audio")).getString("serverSessionId")).put("window",1).toString())
        r.scheduler.pump();r.send(1.0);assertEquals(1,r.wire.sent.size)
        r.port.close()
    }
    @Test fun disconnectDropsUnsentWorkNeverReplaysAndRejectsOldCallbacks(){
        val r=Rig();val results=mutableListOf<AudioSoundResult>();r.port.results.subscribe{it?.let(results::add)}
        val sent=r.send(2.0);val unsent=r.preview(3.0);val old=r.wire.text
        r.wire.failure();r.scheduler.pump()
        assertTrue(results.any{it.id==sent&&it.status=="UNKNOWN"});assertTrue(results.any{it.id==unsent&&it.status=="REJECTED"})
        val value=r.port.state.value.preset;old(r.receipt(0));repeat(3){r.scheduler.pump()}
        assertEquals(value,r.port.state.value.preset);assertEquals(1,r.wire.sent.size);assertEquals(0,r.posts);r.port.close()
    }
    @Test fun invalidOrExpiredReceiptCannotPublishOrTurnEqOn(){
        for(expired in listOf(false,true)){
            val r=Rig();r.send(2.0);val before=r.port.state.value.preset
            if(expired)r.time.value+=4500
            r.wire.text(r.receipt(0,malformed=!expired));r.scheduler.pump()
            assertEquals(before,r.port.state.value.preset);assertTrue(r.wire.retired>0);assertEquals(0,r.posts);r.port.close()
        }
    }
    @Test fun agedInlineProofUsesFreshLiveStateWithoutReplayingOrRetiringHealthyStream(){
        val r=Rig();val outcomes=mutableListOf<AudioSoundResult>();r.port.results.subscribe{it?.let(outcomes::add)};val id=r.send(2.0)
        val receipt=JSONObject(r.receipt(0));val live=receipt.getJSONObject("soundReadback").getJSONObject("state")
        r.live=JSONObject(live.toString()).apply{put("preset",AudioSoundCodec.command("unused","unused","unused",0,r.source,
            AudioSoundCommand.Preview("preview",SoundScope.PRESET,preset=r.model(3.0))).let{JSONObject(it).getJSONObject("command").getJSONObject("model")})}
        r.time.value+=1100
        r.wire.text(receipt.toString());r.scheduler.pump()
        assertEquals(0,r.wire.retired);assertTrue(r.port.state.value.available)
        assertEquals(3.0,r.port.state.value.preset!!.eqPoints.single().gainDb,"Fresh live read wins over aged inline model")
        assertEquals(0,r.posts);assertEquals(1,r.wire.sent.count{it.getJSONObject("command").getString("type")=="soundPreview"})
        assertTrue(outcomes.any{it.id==id&&it.status=="APPLIED"});r.port.close()
    }
    @Test fun staleProofCannotSurviveOfflineReadSourceChangeOrWrongPreview(){
        for(failure in listOf("offline","source","preview","revision","slow","lease")){
            val r=Rig();r.send(2.0);val before=r.port.state.value.preset
            val atUnknown=mutableListOf<SoundPreset?>();r.port.results.subscribe{if(it?.status=="UNKNOWN")atUnknown+=r.port.state.value.preset}
            val receipt=JSONObject(r.receipt(0));val live=JSONObject(receipt.getJSONObject("soundReadback").getJSONObject("state").toString())
            when(failure){
                "offline"->r.readFailure=true
                "slow"->r.getDelayMs=600
                "lease"->receipt.getJSONObject("soundReadback").put("remainingLeaseMs",500)
                "source"->live.put("sourceGeneration","other-source")
                "preview"->live.getJSONObject("preview").put("id","other-preview")
                "revision"->live.put("configurationRevision",0)
            }
            r.live=live;r.time.value+=1100;r.wire.text(receipt.toString());r.scheduler.pump()
            assertTrue(r.wire.retired>0,failure);assertEquals(0,r.posts)
            // After retirement a normal periodic read may observe a genuinely changed
            // source/preview. The failed command itself must never publish that model.
            assertEquals(listOf(before),atUnknown,"Rejected reconciliation must not publish before retiring: $failure")
            assertNotEquals("APPLIED",r.port.results.value?.status,failure);r.port.close()
        }
    }
    @Test fun backgroundClosesAuthorityAndLateReceiptCannotRevivePreview(){
        val r=Rig();r.send(2.0);val old=r.wire.text;r.port.setEditorVisible(false,false)
        old(r.receipt(0));r.time.value+=6000;r.scheduler.pump()
        assertNull(r.port.state.value.preview);assertEquals(1,r.wire.sent.size);assertEquals(0,r.posts);r.port.close()
    }
    @Test fun changingGestureOwnerWaitsThenPreservesOtherConfirmedPoints(){
        val r=Rig(true);val original=r.source.preset!!
        fun edit(index:Int,gain:Double){val p=original.eqPoints[index];r.port.submit(AudioSoundCommand.Preview("preview",SoundScope.PRESET,
            preset=original.copy(eqPoints=original.eqPoints.map{if(it.id==p.id)it.copy(gainDb=gain)else it}),eqEditing=SoundEqEditIntent(false,p)))}
        r.time.value+=40;edit(0,1.0);r.scheduler.pump()
        r.time.value+=40;edit(1,2.0);r.scheduler.pump();assertEquals(1,r.wire.sent.size)
        r.wire.text(r.receipt(0));r.scheduler.pump();assertEquals(2,r.wire.sent.size)
        val second=AudioSoundCodec.preset(r.wire.sent[1].getJSONObject("command").getJSONObject("model"))
        assertEquals(listOf(1.0,2.0),second.eqPoints.map{it.gainDb})
        r.wire.text(r.receipt(1,revision=13));r.scheduler.pump();assertEquals(listOf(1.0,2.0),r.port.state.value.preset!!.eqPoints.map{it.gainDb});r.port.close()
    }
    @Test fun verifiedCredentialRenewalDrainsThenRotatesWithoutReplayingOrEndingOwnedPreview(){
        val r=Rig();r.send(1.0);val old=r.wire.text
        r.port.credentialsRenewed();r.time.value+=40;r.preview(2.0);r.scheduler.pump()
        assertEquals(1,r.wire.sent.size);assertEquals(0,r.wire.retired)
        old(r.receipt(0));r.scheduler.pump();assertEquals(1,r.wire.retired)
        assertTrue(r.port.state.value.available);assertEquals("preview",r.port.state.value.preview!!.id)
        // Only the replacement socket's ready handshake can release the unsent latest goal.
        old(r.receipt(0));r.wire.text(JSONObject().put("version",1).put("kind","sound-command-ready")
            .put("serverSessionId",JSONObject(wireFixture("audio")).getString("serverSessionId")).put("window",4).toString())
        r.scheduler.pump();assertEquals(2,r.wire.sent.size)
        r.wire.text(r.receipt(1,revision=13));r.scheduler.pump();assertEquals(2.0,r.port.state.value.preset!!.eqPoints[0].gainDb)
        assertTrue(r.port.state.value.preset!!.eqEnabled);assertEquals(0,r.posts);r.port.close()
    }
}
