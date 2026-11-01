package com.no5.control.wire;

import java.io.*;
import java.util.Arrays;
import static com.no5.control.wire.BridgeWire.*;

/** Actual codec/guard boundaries; no backend, Android or synthetic device success claims. */
public final class BridgeWireSelfTest {
    static final String SESSION = "0123456789abcdef0123456789abcdef";
    static int assertions;
    static void check(boolean value) { assertions++; if (!value) throw new AssertionError("Assertion " + assertions); }
    interface Failure { void run() throws Exception; }
    static void fails(Failure f) throws Exception { boolean failed = false; try { f.run(); } catch (IOException | IllegalArgumentException e) { failed = true; } check(failed); }
    static byte[] encoded(Packet p) throws Exception { ByteArrayOutputStream out=new ByteArrayOutputStream(); write(out,p); return out.toByteArray(); }
    static Focus focus(long epoch,long now) { return new Focus("com.no5.helm","window-one",epoch,now,0,0); }
    static Key key(long epoch,long down,long now) { return new Key(epoch,5,368,368,7,-1,257,0,1,128,down,now,now+1); }
    static Guard started() { Guard g=new Guard(); check(g.accept(Packet.hello(SESSION),100)); check(g.accept(Packet.focus(SESSION,2,focus(1,100)),100)); return g; }
    public static void main(String[] args) throws Exception {
        for (Packet p: Arrays.asList(Packet.hello(SESSION),Packet.focus(SESSION,2,focus(1,100)),Packet.key(SESSION,3,key(1,101,105)),Packet.cancel(SESSION,4))) {
            byte[] bytes=encoded(p);
            InputStream partial=new FilterInputStream(new ByteArrayInputStream(bytes)) { @Override public int read(byte[] b,int o,int n)throws IOException{return super.read(b,o,Math.min(1,n));} };
            Packet round=read(partial); check(round.kind==p.kind); check(round.controllerSession.equals(SESSION)); check(round.sequence==p.sequence); check(read(partial)==null);
            if(p.key!=null) { check(round.key.eventIdentity().equals(p.key.eventIdentity())); check(round.key.longPress()); check(!round.key.cancelled()); check(round.key.copiedUptime==106); }
            if(p.focus!=null) { check(round.focus.packageName.equals("com.no5.helm")); check(round.focus.epoch==1); }
            for(int n=1;n<bytes.length;n++) { final byte[] cut=Arrays.copyOf(bytes,n); fails(()->read(new ByteArrayInputStream(cut))); }
        }
        fails(()->read(new ByteArrayInputStream(new byte[]{0,0,16,1})));
        fails(()->read(new ByteArrayInputStream(new byte[]{-1,-1,-1,-1})));
        byte[] badVersion=encoded(Packet.hello(SESSION));badVersion[7]=3; fails(()->read(new ByteArrayInputStream(badVersion)));
        byte[] original=encoded(Packet.hello(SESSION));
        byte[] extra=Arrays.copyOf(original,original.length+1); extra[3]++; fails(()->read(new ByteArrayInputStream(extra)));
        byte[] badUtf=original.clone();badUtf[16]=(byte)0xFF; fails(()->read(new ByteArrayInputStream(badUtf)));
        fails(()->new Focus("com.no5.helm","window",1,100,1,0));
        fails(()->new Focus("com.no5.helm","window",1,100,0,1));
        fails(()->encoded(Packet.focus(SESSION,2,new Focus("com.no5.helm","\uD800",1,100,0,0))));
        fails(()->key(1,110,100));
        Guard g=started(); check(g.accept(Packet.key(SESSION,3,key(1,101,105)),106));
        check(!g.accept(Packet.key(SESSION,3,key(1,101,105)),106));check(g.closed());check(g.focus()==null);
        g=started(); check(!g.accept(Packet.key(SESSION,4,key(1,101,105)),106));
        g=started(); check(!g.accept(Packet.key("ffffffffffffffffffffffffffffffff",3,key(1,101,105)),106));
        g=started(); check(!g.accept(Packet.hello(SESSION),106));
        g=started(); check(!g.accept(Packet.key(SESSION,3,key(1,90,105)),106)); // prior hold at reconnect
        g=new Guard();check(g.accept(Packet.hello(SESSION),1000));check(g.accept(Packet.focus(SESSION,2,focus(1,900)),1001));
        check(!g.accept(Packet.key(SESSION,3,key(1,950,1002)),1003)); // independently old hello admission, not just old focus
        g=started(); check(!g.accept(Packet.key(SESSION,3,key(1,101,105)),400));
        g=started();check(g.accept(Packet.focus(SESSION,3,focus(1,400)),400));
        check(!g.accept(Packet.key(SESSION,4,new Key(1,5,368,368,7,-1,257,0,1,128,101,110,401)),401)); // fresh copy of stale event
        g=started(); check(!g.accept(Packet.key(SESSION,3,key(2,101,105)),106));
        g=started();check(g.accept(Packet.focus(SESSION,3,focus(1,200)),200));check(g.accept(Packet.key(SESSION,4,key(1,101,205)),206)); // refresh retains gesture
        g=started();check(g.accept(Packet.focus(SESSION,3,focus(1,200)),200));check(!g.accept(Packet.focus(SESSION,4,focus(2,150)),210));
        g=started();check(g.accept(Packet.focus(SESSION,3,focus(1,200)),200));check(g.accept(Packet.cancel(SESSION,4),205));check(!g.accept(Packet.focus(SESSION,5,focus(2,150)),210));
        g=started();check(!g.accept(Packet.focus(SESSION,3,new Focus("com.android.systemui","different",1,200,0,0)),200));
        g=started();check(g.accept(Packet.cancel(SESSION,3),110));check(!g.accept(Packet.key(SESSION,4,key(1,111,115)),116));
        g=started();check(g.accept(Packet.cancel(SESSION,3),110));check(g.accept(Packet.focus(SESSION,4,focus(2,120)),120));check(g.accept(Packet.key(SESSION,5,key(2,121,125)),126));
        g=started();check(g.accept(Packet.cancel(SESSION,3),200));check(g.accept(Packet.focus(SESSION,4,focus(2,180)),210));check(!g.accept(Packet.key(SESSION,5,key(2,190,211)),212));
        g=started();check(g.accept(Packet.focus(SESSION,3,focus(2,120)),120));check(!g.accept(Packet.focus(SESSION,4,focus(1,130)),130));
        check(read(new ByteArrayInputStream(new byte[0]))==null);
        System.out.println("LUMINA_BRIDGE_WIRE_PASS:"+assertions);
    }
}
