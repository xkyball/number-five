package com.no5.control.wire;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Bounded local transport, not an authentication or OS focus observer. */
public final class BridgeWire {
    public static final int VERSION = 2, MAX_FRAME = 4096;
    public static final int HELLO = 1, FOCUS = 2, KEY = 3, CANCEL = 4;
    private BridgeWire() {}

    public static final class Packet {
        public final int kind;
        public final String controllerSession;
        public final long sequence;
        public final Focus focus;
        public final Key key;
        private Packet(int kind, String session, long sequence, Focus focus, Key key) {
            if (kind < HELLO || kind > CANCEL || session == null || !session.matches("[a-f0-9]{32}") || sequence < 1)
                throw new IllegalArgumentException("Invalid envelope");
            if ((kind == FOCUS) != (focus != null) || (kind == KEY) != (key != null))
                throw new IllegalArgumentException("Invalid payload");
            this.kind = kind; controllerSession = session; this.sequence = sequence; this.focus = focus; this.key = key;
        }
        public static Packet hello(String session) { return new Packet(HELLO, session, 1, null, null); }
        public static Packet focus(String session, long sequence, Focus focus) { return new Packet(FOCUS, session, sequence, focus, null); }
        public static Packet key(String session, long sequence, Key key) { return new Packet(KEY, session, sequence, null, key); }
        public static Packet cancel(String session, long sequence) { return new Packet(CANCEL, session, sequence, null, null); }
    }

    public static final class Focus {
        public final String packageName, windowIdentity;
        public final long epoch, observedUptime;
        public final int user, display;
        public Focus(String packageName, String windowIdentity, long epoch, long observedUptime, int user, int display) {
            if (packageName == null || !packageName.matches("[A-Za-z0-9_.]{1,128}") || windowIdentity == null ||
                windowIdentity.isEmpty() || windowIdentity.length() > 128 || epoch < 1 || observedUptime < 0 || user != 0 || display != 0)
                throw new IllegalArgumentException("Invalid focus");
            this.packageName = packageName; this.windowIdentity = windowIdentity; this.epoch = epoch;
            this.observedUptime = observedUptime; this.user = user; this.display = display;
        }
    }

    /** Complete immutable KeyEvent metadata copied before posting out of a QF callback. */
    public static final class Key {
        public final long capturedScopeEpoch, downTime, eventTime, copiedUptime;
        public final int qfType, qfRawCode, androidKeyCode, scanCode, deviceId, source, action, repeat, flags;
        public Key(long capturedScopeEpoch, int qfType, int qfRawCode, int androidKeyCode, int scanCode,
                   int deviceId, int source, int action, int repeat, int flags, long downTime, long eventTime, long copiedUptime) {
            if (capturedScopeEpoch < 1 || repeat < 0 || action < 0 || action > 1 || downTime < 0 ||
                eventTime < downTime || copiedUptime < eventTime)
                throw new IllegalArgumentException("Invalid key chronology");
            this.capturedScopeEpoch = capturedScopeEpoch; this.qfType = qfType; this.qfRawCode = qfRawCode;
            this.androidKeyCode = androidKeyCode; this.scanCode = scanCode; this.deviceId = deviceId;
            this.source = source; this.action = action; this.repeat = repeat; this.flags = flags;
            this.downTime = downTime; this.eventTime = eventTime; this.copiedUptime = copiedUptime;
        }
        public boolean longPress() { return (flags & 128) != 0; }
        public boolean cancelled() { return (flags & 32) != 0; }
        public String eventIdentity() {
            return qfType+":"+qfRawCode+":"+androidKeyCode+":"+scanCode+":"+deviceId+":"+source+":"+action+":"+repeat+":"+flags+":"+downTime+":"+eventTime;
        }
    }

    public static void write(OutputStream stream, Packet packet) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(VERSION); out.writeInt(packet.kind); string(out, packet.controllerSession); out.writeLong(packet.sequence);
        if (packet.focus != null) {
            Focus f = packet.focus; string(out, f.packageName); string(out, f.windowIdentity);
            out.writeLong(f.epoch); out.writeLong(f.observedUptime); out.writeInt(f.user); out.writeInt(f.display);
        }
        if (packet.key != null) {
            Key k = packet.key; out.writeLong(k.capturedScopeEpoch); out.writeInt(k.qfType); out.writeInt(k.qfRawCode);
            out.writeInt(k.androidKeyCode); out.writeInt(k.scanCode); out.writeInt(k.deviceId); out.writeInt(k.source);
            out.writeInt(k.action); out.writeInt(k.repeat); out.writeInt(k.flags);
            out.writeLong(k.downTime); out.writeLong(k.eventTime); out.writeLong(k.copiedUptime);
        }
        out.flush(); byte[] bytes = buffer.toByteArray();
        if (bytes.length > MAX_FRAME) throw new IOException("Oversize frame");
        DataOutputStream framed = new DataOutputStream(stream); framed.writeInt(bytes.length); framed.write(bytes); framed.flush();
    }
    public static Packet read(InputStream stream) throws IOException {
        DataInputStream framed = new DataInputStream(stream);
        int first = framed.read(); if (first < 0) return null;
        int size = (first << 24) | (framed.readUnsignedByte() << 16) | (framed.readUnsignedByte() << 8) | framed.readUnsignedByte();
        if (size < 1 || size > MAX_FRAME) throw new IOException("Invalid frame size");
        byte[] bytes = new byte[size]; framed.readFully(bytes); DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        try {
            if (in.readInt() != VERSION) throw new IOException("Protocol version");
            int kind = in.readInt(); String session = string(in); long sequence = in.readLong(); Packet packet;
            if (kind == HELLO) packet = new Packet(HELLO, session, sequence, null, null);
            else if (kind == FOCUS) packet = Packet.focus(session, sequence, new Focus(string(in), string(in), in.readLong(), in.readLong(), in.readInt(), in.readInt()));
            else if (kind == KEY) packet = Packet.key(session, sequence, new Key(in.readLong(), in.readInt(), in.readInt(), in.readInt(),
                in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readLong(), in.readLong(), in.readLong()));
            else if (kind == CANCEL) packet = Packet.cancel(session, sequence);
            else throw new IOException("Unknown packet");
            if (in.available() != 0) throw new IOException("Trailing payload");
            return packet;
        } catch (IllegalArgumentException failure) { throw new IOException("Malformed packet", failure); }
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        } catch (CharacterCodingException failure) { throw new IOException("Invalid UTF-8 string", failure); }
        if (bytes.length < 1 || bytes.length > 256) throw new IOException("Invalid string size");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size < 1 || size > 256 || size > in.available()) throw new IOException("Invalid string size");
        byte[] bytes = new byte[size]; in.readFully(bytes);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException failure) { throw new IOException("Invalid UTF-8", failure); }
    }

    /** One connection, fresh hello, strictly increasing sequence and scope; no reconnect replay. */
    public static final class Guard {
        private String session;
        private long sequence, scopeEpoch, scopeTime, sessionAdmissionTime, latestFocusObservedUptime;
        private Focus focus;
        private boolean closed;
        public boolean accept(Packet packet, long uptime) {
            if (closed || packet == null || uptime < 0) return reject();
            if (session == null) {
                if (packet.kind != HELLO || packet.sequence != 1) return reject();
                session = packet.controllerSession; sequence = 1; sessionAdmissionTime = uptime; return true;
            }
            if (!session.equals(packet.controllerSession) || packet.sequence != sequence + 1 || packet.kind == HELLO) return reject();
            sequence = packet.sequence;
            if (packet.kind == CANCEL) { focus = null; sessionAdmissionTime = Math.max(sessionAdmissionTime, uptime); return true; }
            if (packet.kind == FOCUS) {
                Focus next = packet.focus;
                if (next.epoch < scopeEpoch || next.observedUptime < latestFocusObservedUptime || next.observedUptime > uptime || uptime - next.observedUptime > 250) return reject();
                if (next.epoch == scopeEpoch) {
                    if (focus == null || !next.packageName.equals(focus.packageName) || !next.windowIdentity.equals(focus.windowIdentity) ||
                        next.user != focus.user || next.display != focus.display || next.observedUptime < focus.observedUptime) return reject();
                } else {
                    if (next.observedUptime < scopeTime) return reject();
                    scopeEpoch = next.epoch; scopeTime = next.observedUptime;
                }
                latestFocusObservedUptime = next.observedUptime; focus = next; return true;
            }
            Key key = packet.key;
            if (focus == null || key.capturedScopeEpoch != scopeEpoch || key.downTime < Math.max(scopeTime, sessionAdmissionTime) || key.copiedUptime > uptime ||
                uptime - key.copiedUptime > 250 || uptime - key.eventTime > 250 || uptime - focus.observedUptime > 250) return reject();
            return true;
        }
        private boolean reject() { close(); return false; }
        public void close() { closed = true; focus = null; }
        public Focus focus() { return focus; }
        public String session() { return session; }
        public boolean closed() { return closed; }
    }
}
