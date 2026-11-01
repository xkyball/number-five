package org.lumina.header.consumer.v1;

import android.net.LocalSocket;
import java.io.*;
import static org.lumina.header.consumer.v1.HeaderConsumerContract.*;

/** API29 one-shot endpoint. Owner supplies exactly one connector/reader and a finite supervisor. */
public final class AndroidHeaderConnection {
    private AndroidHeaderConnection(){}
    public static final class RootEndpoint implements Closeable {
        private final AndroidHeaderSocketPort socket;private final HeaderConsumerHandshake.Root handshake;private boolean started;
        public RootEndpoint(LocalSocket connected,Clock clock,int helmUid,HeaderPeerAdmission.PinnedHelmVerifier verifier,String rootControlSession,TrustedRootFocusPort focus)throws IOException {
            socket=AndroidHeaderSocketPort.admittedHelm(connected,clock,helmUid,verifier);
            try{handshake=new HeaderConsumerHandshake.Root(clock,focus,socket.admission,rootControlSession,()->closeQuietly(socket));}
            catch(RuntimeException e){closeQuietly(socket);throw e;}
        }
        public synchronized boolean start()throws IOException {
            if(started){close();return false;}started=true;
            try{HeaderFrameCodec.Hello hello=handshake.receiveIntro(socket.readControllerIntro());if(hello==null)return false;
                socket.writeRootHello(hello);return handshake.helloSent(hello);}
            catch(IOException|RuntimeException e){close();throw e;}
        }
        /** One serial worker; deliver to bounded Main mailbox with the original read-start timestamp. */
        public HeaderFrameCodec.DecodedFrame readState()throws IOException {
            if(!started||handshake.isClosed())throw new IOException("handshake unavailable");
            try{return socket.readState();}catch(IOException|RuntimeException e){close();throw e;}
        }
        public boolean acceptOnMain(HeaderFrameCodec.DecodedFrame decoded){return handshake.state(decoded);}
        public ConsumerState currentOnMain(){return handshake.current();}
        public void close(){handshake.retire();}
    }
    public static final class HelmEndpoint implements Closeable {
        private final AndroidHeaderSocketPort socket;private final HeaderConsumerHandshake.Helm handshake;
        private HeaderReadOnlyPublisher publisher;private boolean started;
        public HelmEndpoint(LocalSocket connected,Clock clock,String existingHostControllerSession,HeaderReadOnlyPublisher.AuthenticatedRootControlPort control)throws IOException {
            socket=AndroidHeaderSocketPort.admittedRoot(connected,clock);
            try{handshake=new HeaderConsumerHandshake.Helm(clock,socket.admission,existingHostControllerSession,control,()->closeQuietly(socket));}
            catch(RuntimeException e){closeQuietly(socket);throw e;}
        }
        public synchronized HeaderReadOnlyPublisher start()throws IOException {
            if(started){close();return null;}started=true;
            try{HeaderFrameCodec.ControllerIntro intro=handshake.introForSend();socket.writeControllerIntro(intro);
                publisher=handshake.receiveHello(socket.readRootHello());return publisher;}
            catch(IOException|RuntimeException e){close();throw e;}
        }
        /** Existing host captured mapping/state -> heartbeat only. Never constructs a Sourceowner. */
        public boolean sendHeartbeat()throws IOException {
            if(!started||handshake.isClosed()||publisher==null)return false;
            Frame state=publisher.heartbeat();if(state==null){close();return false;}
            try{socket.writeState(state);return true;}catch(IOException|RuntimeException e){close();throw e;}
        }
        public void close(){handshake.retire();}
    }
    private static void closeQuietly(Closeable socket){try{socket.close();}catch(IOException ignored){/* process slot stays retired if native close unconfirmed */}}
}
