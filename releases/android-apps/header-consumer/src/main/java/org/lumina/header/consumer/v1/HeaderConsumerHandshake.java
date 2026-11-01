package org.lumina.header.consumer.v1;

import static org.lumina.header.consumer.v1.HeaderConsumerContract.*;

/** One-shot handshake: HELM intro -> Root fresh hello -> read-only states. No reconnect or commands. */
public final class HeaderConsumerHandshake {
    private HeaderConsumerHandshake(){}
    public interface ClosePort {void closeChannel();}
    public static final class Root {
        private enum Phase { INTRO, HELLO_SENT, STATES, CLOSED }
        private final Clock clock;private final TrustedRootFocusPort focus;private final HeaderPeerAdmission peer;
        private final String rootControlSession;private final ClosePort close;private Phase phase=Phase.INTRO;
        private HeaderFrameCodec.Hello hello;private HeaderReadOnlyReceiver receiver;
        private AbortReason failure=AbortReason.NOT_ADMITTED;
        public Root(Clock clock,TrustedRootFocusPort focus,HeaderPeerAdmission peer,String rootControlSession,ClosePort close){
            if(peer==null||peer.role!=HeaderPeerAdmission.Role.HELM_PRODUCER)throw new SecurityException("HELM kernel/package admission first");
            this.clock=req(clock);this.focus=req(focus);this.peer=peer;this.rootControlSession=id(rootControlSession,80);this.close=req(close);
        }
        public synchronized HeaderFrameCodec.Hello receiveIntro(HeaderFrameCodec.ControllerIntro intro){
            if(phase!=Phase.INTRO||intro==null){failure=AbortReason.MALFORMED_FRAME;retire();return null;}
            hello=HeaderFrameCodec.Hello.fresh(rootControlSession,intro.controllerSession);phase=Phase.HELLO_SENT;return hello;
        }
        /** Call only after this exact hello was completely written before its absolute deadline. */
        public synchronized boolean helloSent(HeaderFrameCodec.Hello sent){
            if(phase!=Phase.HELLO_SENT||!sameHello(hello,sent)){failure=AbortReason.SESSION_MISMATCH;retire();return false;}
            receiver=new HeaderReadOnlyReceiver(clock,focus,peer,hello);phase=Phase.STATES;return true;
        }
        public synchronized boolean state(HeaderFrameCodec.DecodedFrame frame){
            if(phase!=Phase.STATES||frame==null){failure=AbortReason.MALFORMED_FRAME;retire();return false;}
            if(!receiver.accept(frame)){retire();return false;}return true;
        }
        public synchronized ConsumerState current(){
            if(phase!=Phase.STATES)return receiver==null?new ConsumerState(null,null,Theme.DAY,Reason.NO_PUBLICATION,failure,0,false):receiver.current();
            ConsumerState state=receiver.current();if(!state.transportAlive)retire();return state;
        }
        public synchronized void retire(){if(phase!=Phase.CLOSED){phase=Phase.CLOSED;if(receiver!=null)receiver.disconnect();close.closeChannel();}}
        public synchronized boolean isClosed(){return phase==Phase.CLOSED;}
    }
    public static final class Helm {
        private enum Phase { INTRO, HELLO, STATES, CLOSED }
        private final Clock clock;private final HeaderPeerAdmission peer;private final String hostSession;
        private final HeaderReadOnlyPublisher.AuthenticatedRootControlPort control;private final ClosePort close;
        private Phase phase=Phase.INTRO;private HeaderReadOnlyPublisher publisher;
        public Helm(Clock clock,HeaderPeerAdmission peer,String existingHostSession,HeaderReadOnlyPublisher.AuthenticatedRootControlPort control,ClosePort close){
            if(peer==null||peer.role!=HeaderPeerAdmission.Role.ROOT_CONSUMER)throw new SecurityException("UID0 before packet parsing");
            this.clock=req(clock);this.peer=peer;hostSession=id(existingHostSession,80);this.control=req(control);this.close=req(close);
        }
        public synchronized HeaderFrameCodec.ControllerIntro introForSend(){
            if(phase!=Phase.INTRO){retire();return null;}phase=Phase.HELLO;return new HeaderFrameCodec.ControllerIntro(hostSession);
        }
        public synchronized HeaderReadOnlyPublisher receiveHello(HeaderFrameCodec.Hello hello){
            if(phase!=Phase.HELLO||hello==null){retire();return null;}
            try{publisher=new HeaderReadOnlyPublisher(clock,peer,hello,hostSession,control);phase=Phase.STATES;return publisher;}
            catch(RuntimeException e){retire();return null;}
        }
        public synchronized void retire(){if(phase!=Phase.CLOSED){phase=Phase.CLOSED;if(publisher!=null)publisher.close();close.closeChannel();}}
        public synchronized boolean isClosed(){return phase==Phase.CLOSED;}
    }
    private static boolean sameHello(HeaderFrameCodec.Hello a,HeaderFrameCodec.Hello b){return a!=null&&b!=null&&a.rootControlSession.equals(b.rootControlSession)&&a.consumerSession.equals(b.consumerSession)&&a.controllerSession.equals(b.controllerSession);}
}
