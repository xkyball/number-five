package org.lumina.header.consumer.v1;

import android.net.Credentials;
import android.net.LocalSocket;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.lumina.header.consumer.v1.HeaderConsumerContract.*;

/** API29 compile candidate, not an Android socket/SELinux runtime proof. No connect/reconnect loop. */
public final class AndroidHeaderSocketPort implements Closeable {
    private static final AtomicBoolean PROCESS_CONNECTION=new AtomicBoolean();
    private static final ScheduledThreadPoolExecutor DEADLINES=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"header-consumer-deadline");t.setDaemon(true);return t;});
    static {DEADLINES.setRemoveOnCancelPolicy(true);}
    public static final Clock ANDROID_CLOCK=new Clock(){
        public long elapsedRealtimeMs(){return SystemClock.elapsedRealtime();}
        public long uptimeMs(){return SystemClock.uptimeMillis();}
        public long epochMs(){return System.currentTimeMillis();}
    };
    public final HeaderPeerAdmission admission;
    private final LocalSocket socket;private final Clock clock;private boolean reading,closed,released,closeConfirmed;
    private AndroidHeaderSocketPort(LocalSocket socket,Clock clock,HeaderPeerAdmission admission){this.socket=socket;this.clock=clock;this.admission=admission;}
    public static AndroidHeaderSocketPort admittedRoot(LocalSocket connected,Clock clock)throws IOException {
        return admit(connected,clock,true,0,null);
    }
    public static AndroidHeaderSocketPort admittedHelm(LocalSocket connected,Clock clock,int helmUid,HeaderPeerAdmission.PinnedHelmVerifier verifier)throws IOException {
        return admit(connected,clock,false,helmUid,verifier);
    }
    private static AndroidHeaderSocketPort admit(LocalSocket socket,Clock clock,boolean root,int uid,HeaderPeerAdmission.PinnedHelmVerifier verifier)throws IOException {
        boolean claimed=false,success=false;
        try {
            if(socket==null||clock==null||!PROCESS_CONNECTION.compareAndSet(false,true))throw new IOException("one process connection");claimed=true;
            Credentials c=socket.getPeerCredentials(); // Actual kernel credentials, before any input parsing.
            HeaderPeerAdmission.KernelPeerProbe probe=new HeaderPeerAdmission.KernelPeerProbe(){public int uid(){return c.getUid();}public int pid(){return c.getPid();}public int gid(){return c.getGid();}};
            HeaderPeerAdmission a=root?HeaderPeerAdmission.root(probe):HeaderPeerAdmission.helm(probe,uid,verifier);
            AndroidHeaderSocketPort p=new AndroidHeaderSocketPort(socket,clock,a);success=true;return p;
        } finally {if(!success){if(socket!=null)socket.close();if(claimed)PROCESS_CONNECTION.set(false);}}
    }
    public HeaderFrameCodec.DecodedFrame readState()throws IOException {
        if(admission.role!=HeaderPeerAdmission.Role.HELM_PRODUCER)throw new IOException("direction");
        return read(0);
    }
    public HeaderFrameCodec.Hello readRootHello()throws IOException {
        if(admission.role!=HeaderPeerAdmission.Role.ROOT_CONSUMER)throw new IOException("direction");
        return read(1);
    }
    public HeaderFrameCodec.ControllerIntro readControllerIntro()throws IOException {
        if(admission.role!=HeaderPeerAdmission.Role.HELM_PRODUCER)throw new IOException("direction");
        return read(2);
    }
    @SuppressWarnings("unchecked")
    private <T>T read(int kind)throws IOException {
        synchronized(this){if(closed||reading)throw new IOException("closed/concurrent read");reading=true;}
        long start=clock.elapsedRealtimeMs();ScheduledFuture<?> watchdog=DEADLINES.schedule(()->{try{close();}catch(IOException ignored){}},FRAME_DEADLINE_MS,TimeUnit.MILLISECONDS);
        try {
            Object result=kind==1?HeaderFrameCodec.readHello(socket.getInputStream(),clock,start):kind==2?HeaderFrameCodec.readControllerIntro(socket.getInputStream(),clock,start):HeaderFrameCodec.read(socket.getInputStream(),clock,start);
            FileDescriptor[] fds=socket.getAncillaryFileDescriptors();if(fds!=null&&fds.length!=0){
                for(FileDescriptor fd:fds)try{new FileInputStream(fd).close();}catch(IOException ignored){}
                throw new IOException("ancillary descriptors forbidden");}
            synchronized(this){if(closed)throw new IOException("closed by deadline");}return (T)result;
        } catch(IOException|RuntimeException e){try{close();}catch(IOException ignored){}throw e;}
        finally {watchdog.cancel(false);closeDescriptorsQuietly();synchronized(this){reading=false;releaseIfClosed();}}
    }
    public void writeRootHello(HeaderFrameCodec.Hello hello)throws IOException {
        if(admission.role!=HeaderPeerAdmission.Role.HELM_PRODUCER)throw new IOException("direction");
        write(HeaderFrameCodec.encodeHello(hello),Long.MAX_VALUE);
    }
    public void writeControllerIntro(HeaderFrameCodec.ControllerIntro intro)throws IOException {
        if(admission.role!=HeaderPeerAdmission.Role.ROOT_CONSUMER)throw new IOException("direction");
        write(HeaderFrameCodec.encodeControllerIntro(intro),Long.MAX_VALUE);
    }
    public void writeState(Frame state)throws IOException {
        if(admission.role!=HeaderPeerAdmission.Role.ROOT_CONSUMER)throw new IOException("direction");
        if(socket.getInputStream().available()>0){close();throw new IOException("unexpected Root bytes after one-shot hello");}
        write(HeaderFrameCodec.encode(state),state.expiresAtElapsedMs);
    }
    private void write(byte[] bytes,long originalExpiry)throws IOException {
        synchronized(this){if(closed||reading)throw new IOException("closed/concurrent I/O");reading=true;}
        long start=clock.elapsedRealtimeMs();ScheduledFuture<?> watchdog=DEADLINES.schedule(()->{try{close();}catch(IOException ignored){}},FRAME_DEADLINE_MS,TimeUnit.MILLISECONDS);
        try {
            if(start<0||start>=originalExpiry)throw new IOException("expired write");
            socket.getOutputStream().write(bytes);socket.getOutputStream().flush();long now=clock.elapsedRealtimeMs();
            if(now<start||now>=Math.min(deadline(start,FRAME_DEADLINE_MS),originalExpiry))throw new IOException("absolute write deadline");
            synchronized(this){if(closed)throw new IOException("closed write");}
        } catch(IOException|RuntimeException e){try{close();}catch(IOException ignored){}throw e;}
        finally {watchdog.cancel(false);synchronized(this){reading=false;releaseIfClosed();}}
    }
    private void closeDescriptorsQuietly(){try{FileDescriptor[] fds=socket.getAncillaryFileDescriptors();if(fds!=null)for(FileDescriptor fd:fds)try{new FileInputStream(fd).close();}catch(IOException ignored){}}catch(IOException ignored){}}
    public synchronized void close()throws IOException {
        if(!closed){closed=true;try{socket.close();closeConfirmed=true;}finally{releaseIfClosed();}}
    }
    private void releaseIfClosed(){if(closed&&closeConfirmed&&!reading&&!released){released=true;PROCESS_CONNECTION.set(false);}}
    // If native close/read hangs, the process slot remains claimed: no replacement reader growth.
    // A separate finite process supervisor is still required; ScheduledExecutor is not a hard RT guarantee.
}
