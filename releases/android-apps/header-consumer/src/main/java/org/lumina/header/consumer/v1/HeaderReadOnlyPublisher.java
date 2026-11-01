package org.lumina.header.consumer.v1;

import static org.lumina.header.consumer.v1.HeaderConsumerContract.*;
import java.io.IOException;
import java.util.Arrays;

/** Host-side adapter for already accepted immutable HeaderRegistry state. Does not own a source. */
public final class HeaderReadOnlyPublisher {
    public interface AuthenticatedRootControlPort { boolean currentRootControlSession(String rootControlSession); }
    private final Clock clock;private final HeaderFrameCodec.Hello hello;private final AuthenticatedRootControlPort control;
    private ScopeEvidence mapping;private Snapshot snapshot;private Reason reason=Reason.NO_PUBLICATION;private Theme theme=Theme.DAY;
    private long transportSequence,stateRevision=1,lastElapsed=-1,lastUptime=-1;private boolean closed;
    public HeaderReadOnlyPublisher(Clock clock,HeaderPeerAdmission peer,HeaderFrameCodec.Hello hello,
                                  String existingControllerSession,AuthenticatedRootControlPort control){
        if(peer==null||peer.role!=HeaderPeerAdmission.Role.ROOT_CONSUMER)throw new SecurityException("UID0 before root hello parser");
        this.clock=req(clock);this.hello=req(hello);this.control=req(control);
        if(!hello.controllerSession.equals(existingControllerSession)||!control.currentRootControlSession(hello.rootControlSession))throw new SecurityException("current host/control binding");
    }
    /** Only call at the actual authenticated-focus adoption, before original publication capture. */
    public synchronized boolean adoptFocus(RootFocus capturedRoot,long localForegroundEpoch){
        if(!live()||capturedRoot==null||!capturedRoot.rootControlSession.equals(hello.rootControlSession)||
            !freshUptime(capturedRoot.observedUptimeMs,lastUptime))return retire();
        ScopeEvidence next=new ScopeEvidence(hello.rootControlSession,hello.consumerSession,hello.controllerSession,
            capturedRoot.rootEpoch,capturedRoot.rootWindowIdentity,localForegroundEpoch,capturedRoot.packageName,
            capturedRoot.user,capturedRoot.display,capturedRoot.observedUptimeMs);
        if(mapping!=null&&!mapping.sameMapping(next)){
            if(next.rootEpoch<=mapping.rootEpoch||next.hostForegroundEpoch<=mapping.hostForegroundEpoch)return retire();
            snapshot=null;reason=Reason.NO_PUBLICATION;if(!advanceRevision())return false;
        }
        if(mapping!=null&&mapping.sameMapping(next)&&next.focusObservedUptime<mapping.focusObservedUptime)return retire();
        mapping=next;return true;
    }
    /** Capture before the actual synchronous registry read / at the data callback's origin. */
    public synchronized ScopeEvidence captureScopeForAcceptedState(){return live()?mapping:null;}
    /** Trusted local registry hook only. Never replace an old async callback's captured scope. */
    public synchronized boolean acceptedState(ScopeEvidence capturedAtOrigin,Snapshot accepted,Reason clearReason,Theme baseTheme){
        if(!live()||mapping==null||!mapping.sameMapping(capturedAtOrigin))return retire();
        if(accepted!=null&&(!accepted.publication.packageName.equals(mapping.packageName)||
            accepted.publication.foregroundEpoch!=mapping.hostForegroundEpoch||!accepted.leaseValid(lastElapsed)||clearReason!=Reason.NONE))return retire();
        if(accepted==null&&clearReason==Reason.NONE)return retire();
        try {boolean same=Arrays.equals(HeaderFrameCodec.snapshotBytes(snapshot),HeaderFrameCodec.snapshotBytes(accepted))&&reason==clearReason&&theme==baseTheme;
            if(!same&&!advanceRevision())return false;}
        catch(IOException e){return retire();}
        snapshot=accepted;reason=req(clearReason);theme=req(baseTheme);return true;
    }
    /** Called at <=100ms cadence by a single bounded owner; never republishes a measurement. */
    public synchronized Frame heartbeat(){
        if(!live()||mapping==null||transportSequence==Long.MAX_VALUE)return null;
        if(snapshot!=null&&!snapshot.leaseValid(lastElapsed)){snapshot=null;reason=Reason.EXPIRED;if(!advanceRevision())return null;}
        return new Frame(mapping,++transportSequence,stateRevision,lastElapsed,deadline(lastElapsed,MAX_TRANSPORT_LEASE_MS),theme,reason,snapshot);
    }
    public synchronized void close(){retire();}
    public synchronized boolean isClosed(){return closed;}
    private boolean advanceRevision(){if(stateRevision==Long.MAX_VALUE)return retire();stateRevision++;return true;}
    private boolean live(){
        if(closed)return false;try{if(!control.currentRootControlSession(hello.rootControlSession))return retire();}catch(RuntimeException e){return retire();}
        long elapsed=clock.elapsedRealtimeMs(),uptime=clock.uptimeMs();if(elapsed<0||uptime<0||elapsed<lastElapsed||uptime<lastUptime)return retire();
        lastElapsed=elapsed;lastUptime=uptime;return true;
    }
    private boolean retire(){closed=true;snapshot=null;return false;}
}
