package org.lumina.header.consumer.v1;

import static org.lumina.header.consumer.v1.HeaderConsumerContract.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.SecureRandom;

/** v1 binary format: BE length, magic/version/type, explicit fields; no Java serialization. */
public final class HeaderFrameCodec {
    private HeaderFrameCodec() {}
    private static final int MAGIC=0x4c484331, HELLO=1, STATE=2, INTRO=3;
    /** Sent only by the installed, authenticated HELM host, on the newly admitted connection. */
    public static final class ControllerIntro {
        public final String controllerSession;
        public ControllerIntro(String existingHostSession){controllerSession=id(existingHostSession,80);}
    }
    public static final class Hello {
        public final String rootControlSession,consumerSession,controllerSession;
        public Hello(String root,String consumer,String controller){rootControlSession=id(root,80);consumerSession=nonce(consumer);controllerSession=id(controller,80);}
        public static Hello fresh(String root,String controller){byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);StringBuilder b=new StringBuilder(32);
            for(byte v:bytes){b.append(Character.forDigit((v>>>4)&15,16));b.append(Character.forDigit(v&15,16));}return new Hello(root,b.toString(),controller);}
    }
    public static final class DecodedFrame {
        public final Frame frame;public final long readStartedElapsedMs,receivedElapsedMs;
        public DecodedFrame(Frame frame,long start,long received){this.frame=req(frame);readStartedElapsedMs=nonnegative(start);receivedElapsedMs=nonnegative(received);}
    }
    public static byte[] encodeHello(Hello h)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=begin(b,HELLO);
        str(d,h.rootControlSession);str(d,h.consumerSession);str(d,h.controllerSession);return finish(b);
    }
    public static byte[] encodeControllerIntro(ControllerIntro intro)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=begin(b,INTRO);str(d,intro.controllerSession);return finish(b);
    }
    public static ControllerIntro readControllerIntro(InputStream in,Clock clock,long start)throws IOException {
        DataInputStream d=body(in,clock,start,INTRO);
        try{ControllerIntro intro=new ControllerIntro(str(d,80));end(d);checkDeadline(clock,start);return intro;}
        catch(IllegalArgumentException|NullPointerException ex){throw new IOException("invalid controller intro",ex);}
    }
    public static Hello readHello(InputStream in,Clock clock,long start)throws IOException {
        DataInputStream d=body(in,clock,start,HELLO);
        try {Hello h=new Hello(str(d,80),str(d,32),str(d,80));end(d);checkDeadline(clock,start);return h;}
        catch(IllegalArgumentException|NullPointerException ex){throw new IOException("invalid hello",ex);}
    }
    public static byte[] encode(Frame f)throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=begin(b,STATE);ScopeEvidence s=f.scope;
        str(d,s.rootControlSession);str(d,s.consumerSession);str(d,s.controllerSession);d.writeLong(s.rootEpoch);
        str(d,s.rootWindowIdentity);d.writeLong(s.hostForegroundEpoch);str(d,s.packageName);d.writeInt(s.user);d.writeInt(s.display);d.writeLong(s.focusObservedUptime);
        d.writeLong(f.transportSequence);d.writeLong(f.stateRevision);d.writeLong(f.issuedAtElapsedMs);d.writeLong(f.expiresAtElapsedMs);
        en(d,f.baseTheme);en(d,f.reason);
        byte[] snapshot=snapshotBytes(f.snapshot);d.writeInt(snapshot.length);d.write(snapshot);return finish(b);
    }
    public static DecodedFrame read(InputStream in,Clock clock,long start)throws IOException {
        DataInputStream d=body(in,clock,start,STATE);
        try {
            ScopeEvidence s=new ScopeEvidence(str(d,80),str(d,32),str(d,80),d.readLong(),str(d,256),d.readLong(),str(d,256),d.readInt(),d.readInt(),d.readLong());
            long seq=d.readLong(),rev=d.readLong(),issued=d.readLong(),expiry=d.readLong();Theme theme=en(d,Theme.values());Reason reason=en(d,Reason.values());
            int n=d.readInt();if(n<0||n>MAX_SNAPSHOT_BYTES||n>d.available())throw new IOException("snapshot bound");
            byte[] bytes=new byte[n];d.readFully(bytes);Snapshot snapshot=parseSnapshot(bytes);end(d);
            Frame f=new Frame(s,seq,rev,issued,expiry,theme,reason,snapshot);checkDeadline(clock,start);
            return new DecodedFrame(f,start,clock.elapsedRealtimeMs());
        } catch(IllegalArgumentException|NullPointerException ex){throw new IOException("invalid state",ex);}
    }
    public static byte[] snapshotBytes(Snapshot s)throws IOException {
        if(s==null)return new byte[0];ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);
        d.writeInt(s.schemaVersion);Publication p=s.publication;str(d,p.packageName);str(d,p.producerSession);d.writeLong(p.foregroundEpoch);d.writeLong(p.pageGeneration);
        str(d,p.sourceSessionId);d.writeLong(p.sequence);d.writeLong(p.publishedAtElapsedMs);d.writeLong(p.expiresAtElapsedMs);
        en(d,s.pageId);en(d,s.theme);en(d,s.profile);str(d,s.context.title);str(d,s.context.subtitle);str(d,s.context.pageStatus);
        Source a=s.source;str(d,a.label);str(d,a.detail);str(d,a.sourceId);str(d,a.backendSessionId);en(d,a.transport);en(d,a.quality);en(d,a.provenance);policy(d,a.freshness);
        opt(d,s.sourceObservedAtEpochMs);measure(d,s.extras.portRpm);measure(d,s.extras.starboardRpm);measure(d,s.extras.sog);
        d.flush();byte[] bytes=b.toByteArray();if(bytes.length>MAX_SNAPSHOT_BYTES)throw new IOException("snapshot bound");return bytes;
    }
    private static Snapshot parseSnapshot(byte[] bytes)throws IOException {
        if(bytes.length==0)return null;DataInputStream d=new DataInputStream(new ByteArrayInputStream(bytes));int version=d.readInt();
        Publication p=new Publication(str(d,256),str(d,256),d.readLong(),d.readLong(),str(d,256),d.readLong(),d.readLong(),d.readLong());
        Page page=en(d,Page.values());Theme theme=en(d,Theme.values());Profile profile=en(d,Profile.values());Context context=new Context(str(d,128),str(d,256),str(d,512));
        Source source=new Source(str(d,128),str(d,512),str(d,256),str(d,256),en(d,Transport.values()),en(d,Quality.values()),en(d,Provenance.values()),policy(d));
        Long observed=opt(d);Extras extras=new Extras(measure(d),measure(d),measure(d));end(d);
        return new Snapshot(p,page,theme,profile,context,source,observed,extras,version);
    }
    private static DataOutputStream begin(ByteArrayOutputStream b,int type)throws IOException {
        DataOutputStream d=new DataOutputStream(b);d.writeInt(MAGIC);d.writeInt(VERSION);d.writeByte(type);return d;
    }
    private static byte[] finish(ByteArrayOutputStream b)throws IOException {
        byte[] body=b.toByteArray();if(body.length+4>MAX_FRAME_BYTES)throw new IOException("frame bound");
        ByteArrayOutputStream all=new ByteArrayOutputStream(body.length+4);DataOutputStream d=new DataOutputStream(all);d.writeInt(body.length);d.write(body);return all.toByteArray();
    }
    private static DataInputStream body(InputStream in,Clock clock,long start,int type)throws IOException {
        byte[] prefix=new byte[4];readFully(in,prefix,clock,start);int n=ByteBuffer.wrap(prefix).getInt();
        if(n<9||n>MAX_FRAME_BYTES-4)throw new IOException("frame bound");byte[] bytes=new byte[n];readFully(in,bytes,clock,start);
        DataInputStream d=new DataInputStream(new ByteArrayInputStream(bytes));if(d.readInt()!=MAGIC||d.readInt()!=VERSION||d.readUnsignedByte()!=type)throw new IOException("magic/version/type");return d;
    }
    private static void readFully(InputStream in,byte[] out,Clock clock,long start)throws IOException {
        int offset=0;while(offset<out.length){checkDeadline(clock,start);int n=in.read(out,offset,out.length-offset);checkDeadline(clock,start);
            if(n<0)throw new EOFException("partial frame");if(n==0)throw new IOException("nonprogress stream");offset+=n;}
    }
    private static void checkDeadline(Clock clock,long start)throws IOException {
        long now=clock.elapsedRealtimeMs();if(start<0||now<start||now>=deadline(start,FRAME_DEADLINE_MS))throw new IOException("absolute frame deadline/clock");
        // A blocking Android read additionally requires a single bounded socket-close watchdog.
    }
    private static void end(DataInputStream d)throws IOException {if(d.available()!=0)throw new IOException("unknown/trailing fields");}
    private static void str(DataOutputStream d,String s)throws IOException {
        if(s==null){d.writeInt(-1);return;}
        try {ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s));
            byte[] bytes=new byte[b.remaining()];b.get(bytes);d.writeInt(bytes.length);d.write(bytes);}
        catch(CharacterCodingException ex){throw new IOException("UTF8",ex);}
    }
    private static String str(DataInputStream d,int maxChars)throws IOException {
        int n=d.readInt();if(n==-1)return null;if(n<0||n>maxChars*4||n>d.available())throw new IOException("string bound");
        byte[] bytes=new byte[n];d.readFully(bytes);
        try {String s=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(s.length()>maxChars)throw new IOException("string chars");return s;}
        catch(CharacterCodingException ex){throw new IOException("UTF8",ex);}
    }
    private static void en(DataOutputStream d,Enum<?> e)throws IOException {d.writeByte(e.ordinal());}
    private static <T>T en(DataInputStream d,T[] es)throws IOException {int n=d.readUnsignedByte();if(n>=es.length)throw new IOException("enum");return es[n];}
    private static void opt(DataOutputStream d,Long t)throws IOException {d.writeByte(t==null?0:1);if(t!=null)d.writeLong(t);}
    private static Long opt(DataInputStream d)throws IOException {int b=d.readUnsignedByte();if(b==0)return null;if(b!=1)throw new IOException("optional");return d.readLong();}
    private static void policy(DataOutputStream d,Freshness p)throws IOException {d.writeLong(p.staleAfterMs);d.writeLong(p.lostAfterMs);}
    private static Freshness policy(DataInputStream d)throws IOException {return new Freshness(d.readLong(),d.readLong());}
    private static void measure(DataOutputStream d,Measurement m)throws IOException {
        d.writeByte(m==null?0:1);if(m==null)return;d.writeByte(m.value==null?0:1);if(m.value!=null)d.writeDouble(m.value);
        en(d,m.unit);str(d,m.sourceId);opt(d,m.sourceObservedAtEpochMs);en(d,m.quality);en(d,m.provenance);policy(d,m.freshness);
    }
    private static Measurement measure(DataInputStream d)throws IOException {
        int b=d.readUnsignedByte();if(b==0)return null;if(b!=1)throw new IOException("measurement");int v=d.readUnsignedByte();if(v!=0&&v!=1)throw new IOException("value optional");
        Double value=v==0?null:d.readDouble();return new Measurement(value,en(d,Unit.values()),str(d,256),opt(d),en(d,Quality.values()),en(d,Provenance.values()),policy(d));
    }
}
