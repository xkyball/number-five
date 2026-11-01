package org.lumina.header.consumer.v1;

import static org.lumina.header.consumer.v1.HeaderConsumerContract.*;

/** Exactly one pending immutable frame and one posted Main drain; no per-heartbeat post growth. */
public final class HeaderLatestMailbox {
    public interface MainQueue { boolean post(Runnable task); }
    public interface Sink { void frame(HeaderFrameCodec.DecodedFrame frame); void expired(); }
    private final Clock clock;private final MainQueue main;private final Sink sink;
    private HeaderFrameCodec.DecodedFrame pending;private boolean posted,closed;
    public HeaderLatestMailbox(Clock clock,MainQueue main,Sink sink){this.clock=req(clock);this.main=req(main);this.sink=req(sink);}
    public synchronized boolean offer(HeaderFrameCodec.DecodedFrame frame){
        if(closed)return false;
        // State stream is contiguous: replacing unread revisions/sequences would conceal a gap.
        if(pending!=null){closed=true;pending=null;return false;}
        pending=req(frame);if(posted)return true;posted=true;
        if(!main.post(this::drain)){posted=false;pending=null;closed=true;return false;}return true;
    }
    private void drain(){
        HeaderFrameCodec.DecodedFrame f;
        synchronized(this){posted=false;f=pending;pending=null;if(closed||f==null)return;}
        long now=clock.elapsedRealtimeMs();long expiry=Math.min(deadline(f.readStartedElapsedMs,FRAME_DEADLINE_MS),f.frame.expiresAtElapsedMs);
        if(now<f.receivedElapsedMs||now>=expiry){close();sink.expired();return;}sink.frame(f);
    }
    public synchronized void close(){closed=true;pending=null;}
    public synchronized boolean isClosed(){return closed;}
}
