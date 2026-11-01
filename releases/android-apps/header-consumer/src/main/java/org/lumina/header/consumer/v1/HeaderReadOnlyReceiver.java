package org.lumina.header.consumer.v1;

import static org.lumina.header.consumer.v1.HeaderConsumerContract.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** Bounded state machine for the Root process. Trusted focus is an independent local input. */
public final class HeaderReadOnlyReceiver {
    private final Clock clock;private final TrustedRootFocusPort focus;private final HeaderFrameCodec.Hello hello;
    private long lastElapsed=-1,lastUptime=-1,epochHighWater=-1,lastSequence=0,lastRevision=0;
    private ScopeEvidence mapping;private Snapshot original,aged;private Snapshot lastPublication;
    private byte[] lastStateBytes;private Theme theme=Theme.DAY;private Reason reason=Reason.NO_PUBLICATION;
    private long validUntil;private AbortReason abort=AbortReason.NONE;private boolean closed,scopeInvalidated;
    public HeaderReadOnlyReceiver(Clock clock,TrustedRootFocusPort focus,HeaderPeerAdmission peer,HeaderFrameCodec.Hello hello){
        if(peer==null||peer.role!=HeaderPeerAdmission.Role.HELM_PRODUCER)throw new SecurityException("HELM admission before parser");
        this.clock=req(clock);this.focus=req(focus);this.hello=req(hello);
    }
    public synchronized boolean accept(HeaderFrameCodec.DecodedFrame input){
        if(closed)return false;if(!clockValid())return fail(AbortReason.CLOCK_ROLLBACK);
        Frame f=input.frame;long now=lastElapsed;
        if(input.readStartedElapsedMs>input.receivedElapsedMs||input.receivedElapsedMs>now||
            now>=deadline(input.readStartedElapsedMs,FRAME_DEADLINE_MS))return fail(AbortReason.FRAME_DEADLINE);
        if(!f.scope.rootControlSession.equals(hello.rootControlSession)||!f.scope.consumerSession.equals(hello.consumerSession)||
            !f.scope.controllerSession.equals(hello.controllerSession))return fail(AbortReason.SESSION_MISMATCH);
        if(lastSequence==Long.MAX_VALUE||f.transportSequence!=lastSequence+1||f.stateRevision<lastRevision)return fail(AbortReason.OUT_OF_ORDER);
        if(f.issuedAtElapsedMs>now||f.expiresAtElapsedMs<=f.issuedAtElapsedMs||
            f.expiresAtElapsedMs-f.issuedAtElapsedMs>MAX_TRANSPORT_LEASE_MS||now>=f.expiresAtElapsedMs)return fail(AbortReason.TRANSPORT_EXPIRED);
        if(lastSequence>0&&now>=validUntil)return fail(AbortReason.HEARTBEAT_TIMEOUT);
        RootFocus root;try{root=focus.current();}catch(RuntimeException e){return fail(AbortReason.FOCUS_UNAVAILABLE);}
        if(root==null||!freshUptime(root.observedUptimeMs,lastUptime))return fail(AbortReason.FOCUS_UNAVAILABLE);
        if(!f.scope.matchesRoot(root)||f.scope.focusObservedUptime>root.observedUptimeMs||
            !freshUptime(f.scope.focusObservedUptime,lastUptime))return fail(AbortReason.FOCUS_MISMATCH);
        boolean changed=mapping==null||!mapping.sameMapping(f.scope);
        if(scopeInvalidated&&!changed)return fail(AbortReason.EPOCH_MAPPING);
        if(mapping!=null&&changed&&(f.scope.rootEpoch<=mapping.rootEpoch||f.scope.hostForegroundEpoch<=mapping.hostForegroundEpoch))return fail(AbortReason.EPOCH_MAPPING);
        if(!changed&&f.stateRevision==lastRevision){
            if(!sameState(f))return fail(AbortReason.STATE_REWRITE);
        } else if(f.stateRevision<=lastRevision)return fail(AbortReason.OUT_OF_ORDER);
        if(f.snapshot!=null){
            Snapshot s=f.snapshot;Publication p=s.publication;
            if(!p.packageName.equals(root.packageName)||p.foregroundEpoch!=f.scope.hostForegroundEpoch||!s.leaseValid(now))return fail(AbortReason.INVALID_SNAPSHOT);
            if(!changed&&lastPublication!=null){
                Publication old=lastPublication.publication;
                if(!p.producerSession.equals(old.producerSession)||p.pageGeneration<old.pageGeneration||p.sequence<old.sequence)return fail(AbortReason.INVALID_SNAPSHOT);
                if(p.publishedAtElapsedMs<old.publishedAtElapsedMs)return fail(AbortReason.INVALID_SNAPSHOT);
                if(p.pageGeneration==old.pageGeneration&&(s.pageId!=lastPublication.pageId||!Objects.equals(p.sourceSessionId,old.sourceSessionId)))return fail(AbortReason.INVALID_SNAPSHOT);
                if(p.sequence==old.sequence&&(original==null||!sameSnapshot(s,lastPublication)))return fail(AbortReason.STATE_REWRITE);
            }
        }
        try {lastStateBytes=stateBytes(f);}catch(IOException e){return fail(AbortReason.MALFORMED_FRAME);}
        if(changed)lastPublication=null;
        mapping=f.scope;lastSequence=f.transportSequence;lastRevision=f.stateRevision;theme=f.baseTheme;reason=f.reason;
        boolean sameOriginal=original!=null&&f.snapshot!=null&&sameSnapshot(original,f.snapshot);
        original=f.snapshot;aged=original==null?null:(sameOriginal&&aged!=null?aged.at(epochHighWater):original.at(epochHighWater));
        if(original!=null)lastPublication=original;scopeInvalidated=false;
        validUntil=Math.min(f.expiresAtElapsedMs,deadline(f.issuedAtElapsedMs,HEARTBEAT_MAX_GAP_MS));
        if(now>=validUntil)return fail(AbortReason.HEARTBEAT_TIMEOUT);
        abort=AbortReason.NONE;return true;
    }
    public synchronized ConsumerState current(){
        if(!closed&&!clockValid())fail(AbortReason.CLOCK_ROLLBACK);
        if(closed)return empty(false,abort,null);
        if(lastSequence==0)return empty(true,AbortReason.NOT_ADMITTED,null);
        if(lastElapsed>=validUntil){fail(AbortReason.HEARTBEAT_TIMEOUT);return empty(false,abort,null);}
        RootFocus root;try{root=focus.current();}catch(RuntimeException e){fail(AbortReason.FOCUS_UNAVAILABLE);return empty(false,abort,null);}
        if(root==null||!freshUptime(root.observedUptimeMs,lastUptime)){invalidateScope();return empty(true,AbortReason.FOCUS_UNAVAILABLE,null);}
        if(!mapping.matchesRoot(root)||root.observedUptimeMs<mapping.focusObservedUptime){invalidateScope();return empty(true,AbortReason.FOCUS_MISMATCH,null);}
        if(scopeInvalidated)return empty(true,AbortReason.EPOCH_MAPPING,null);
        if(original!=null&&!original.leaseValid(lastElapsed)){original=null;aged=null;reason=Reason.EXPIRED;}
        if(aged!=null)aged=aged.at(epochHighWater); // Wall-clock reversal cannot upgrade quality/value.
        return new ConsumerState(mapping.withObservedRoot(root),aged,theme,reason,AbortReason.NONE,validUntil,true);
    }
    public synchronized void disconnect(){if(!closed)fail(AbortReason.DISCONNECTED);}
    public synchronized void malformed(){if(!closed)fail(AbortReason.MALFORMED_FRAME);}
    public synchronized boolean isClosed(){return closed;}
    private ConsumerState empty(boolean alive,AbortReason why,ScopeEvidence scope){return new ConsumerState(scope,null,theme,reason,why,validUntil,alive);}
    private boolean fail(AbortReason why){closed=true;abort=why;original=null;aged=null;mapping=null;return false;}
    private void invalidateScope(){scopeInvalidated=true;original=null;aged=null;reason=Reason.NO_FOREGROUND_SCOPE;}
    private boolean clockValid(){
        long elapsed=clock.elapsedRealtimeMs(),uptime=clock.uptimeMs(),epoch=clock.epochMs();
        if(elapsed<0||uptime<0||epoch<0||elapsed<lastElapsed||uptime<lastUptime)return false;
        lastElapsed=elapsed;lastUptime=uptime;epochHighWater=Math.max(epochHighWater,epoch);return true;
    }
    private boolean sameState(Frame f){try{return Arrays.equals(lastStateBytes,stateBytes(f));}catch(IOException e){return false;}}
    private static boolean sameSnapshot(Snapshot a,Snapshot b){try{return Arrays.equals(HeaderFrameCodec.snapshotBytes(a),HeaderFrameCodec.snapshotBytes(b));}catch(IOException e){return false;}}
    private static byte[] stateBytes(Frame f)throws IOException {
        // Transport heartbeat/focus observation may change; original publication/data/base reason may not.
        byte[] snapshot=HeaderFrameCodec.snapshotBytes(f.snapshot);byte[] state=Arrays.copyOf(snapshot,snapshot.length+2);
        state[snapshot.length]=(byte)f.baseTheme.ordinal();state[snapshot.length+1]=(byte)f.reason.ordinal();return state;
    }
}
