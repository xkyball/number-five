package org.lumina.control.integration.v2;

import java.util.Objects;

/** Offline policy/gesture core. No OEM listener, Android injection or audio commands. */
public final class KnobRouter {
    public enum Input {
        LEFT_CW, LEFT_CCW, RIGHT_CW, RIGHT_CCW,
        LEFT_PUSH, RIGHT_PUSH, LEFT_LONG_PUSH, RIGHT_LONG_PUSH
    }

    public enum Owner { APP, HELM_AUDIO, NONE }
    public enum Phase { DOWN, UP, CANCEL }

    /** The adapter must obtain verified foreground identity and advance epochs on changes. */
    public static final class Scope {
        public final String foregroundPackage;
        public final long foregroundEpoch;
        public final long bindingsEpoch;
        public final Owner owner;

        public Scope(String foregroundPackage, long foregroundEpoch, long bindingsEpoch,
                     boolean appClientReady, boolean audioClientReady) {
            if (foregroundEpoch < 0 || bindingsEpoch < 0) throw new IllegalArgumentException("Negative epoch");
            this.foregroundPackage = Objects.requireNonNull(foregroundPackage);
            this.foregroundEpoch = foregroundEpoch;
            this.bindingsEpoch = bindingsEpoch;
            this.owner = foregroundPackage.isEmpty() ? Owner.NONE
                    : isProductApp(foregroundPackage) ? (appClientReady ? Owner.APP : Owner.NONE)
                    : (audioClientReady ? Owner.HELM_AUDIO : Owner.NONE);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Scope)) return false;
            Scope that = (Scope) other;
            return foregroundPackage.equals(that.foregroundPackage)
                    && foregroundEpoch == that.foregroundEpoch && bindingsEpoch == that.bindingsEpoch
                    && owner == that.owner;
        }

        @Override public int hashCode() {
            return Objects.hash(foregroundPackage, foregroundEpoch, bindingsEpoch, owner);
        }
    }

    /** One target per normalized input. A rejected delivery must not execute another target. */
    public static final class Decision {
        public final Input input;
        public final Scope scope;

        private Decision(Input input, Scope scope) { this.input = input; this.scope = scope; }
    }

    private static final class Hold {
        final Scope captured;
        boolean longSeen;
        boolean cancelled;
        Hold(Scope captured) { this.captured = captured; }
    }

    private final Hold[] holds = new Hold[2];
    private Scope scope;

    public KnobRouter(Scope initialScope) { scope = Objects.requireNonNull(initialScope); }

    public Scope currentScope() { return scope; }

    /** Recheck before delivery; queued actions must not survive a foreground/session change. */
    public boolean isCurrent(Decision value) {
        return value != null && value.scope.equals(scope) && scope.owner != Owner.NONE;
    }

    public static boolean isProductApp(String name) {
        return "com.no5.home".equals(name) || "com.no5.map".equals(name) || "com.no5.helm".equals(name);
    }

    /** Invoke on one serialized event loop before accepting events for the new context. */
    public void updateScope(Scope next) {
        Objects.requireNonNull(next);
        if (!scope.equals(next)) {
            cancelHolds();
            scope = next;
        }
    }

    /** Retain cancelled holds until UP so a later long repeat cannot gain a new owner. */
    public void cancelHolds() {
        for (Hold hold : holds) if (hold != null) hold.cancelled = true;
    }

    /**
     * Adapter-only admission of a NEW authenticated OEM short DOWN. It must already have
     * repeat=0, no LONG/CANCEL flag, downTime=eventTime, and a current source/focus cutoff.
     * Fresh long repeats cannot erase a cancelled hold because OEM downTime changes per repeat.
     */
    public void admitFreshShortDown(Scope observedScope, int code) {
        if (!scope.equals(Objects.requireNonNull(observedScope))) return;
        int knob = code == 368 ? 0 : code == 361 ? 1 : -1;
        if (knob >= 0 && holds[knob] != null && holds[knob].cancelled) holds[knob] = null;
    }

    /** Only reviewed, already-normalized OEM codes; no time debounce of rotary detents. */
    public Decision accept(Scope observedScope, int code, Phase phase, int repeat, boolean longPress) {
        Objects.requireNonNull(observedScope);
        Objects.requireNonNull(phase);
        if (repeat < 0) throw new IllegalArgumentException("Negative repeat");
        int knob = code == 368 ? 0 : code == 361 ? 1 : -1;
        if (!scope.equals(observedScope)) {
            // Even a discarded first DOWN records a cancelled gesture. Otherwise a later
            // long repeat stamped with a new foreground could appear to start a new hold.
            if (knob >= 0) {
                Hold old = holds[knob];
                if ((phase == Phase.DOWN || phase == Phase.CANCEL) && old == null) {
                    holds[knob] = old = new Hold(observedScope);
                    old.cancelled = true;
                } else if (phase == Phase.UP && old != null && old.cancelled
                        && old.captured.equals(observedScope)) {
                    holds[knob] = null;
                }
            }
            return null;
        }
        int rotary = code >= 364 && code <= 367 ? code - 364 : -1;
        if (rotary >= 0) {
            return phase == Phase.DOWN && repeat == 0 && !longPress
                    ? decision(Input.values()[rotary], scope) : null;
        }
        if (knob < 0) return null;
        Hold hold = holds[knob];
        if (phase == Phase.CANCEL) {
            if (hold == null) holds[knob] = hold = new Hold(scope);
            hold.cancelled = true;
            return null;
        }
        if (phase == Phase.UP) {
            holds[knob] = null;
            // Orphan UP and a long-marked UP without LONG_DOWN never become a short press.
            if (hold == null || hold.cancelled || hold.longSeen || longPress) return null;
            return decision(knob == 0 ? Input.LEFT_PUSH : Input.RIGHT_PUSH, hold.captured);
        }
        if (hold == null) {
            // Real Lumina holds can start with repeat=1/FLAG_LONG_PRESS and no short DOWN.
            if (!longPress && repeat != 0) return null;
            holds[knob] = hold = new Hold(scope);
        }
        if (!longPress || hold.longSeen || hold.cancelled) return null;
        hold.longSeen = true;
        return decision(knob == 0 ? Input.LEFT_LONG_PUSH : Input.RIGHT_LONG_PUSH, hold.captured);
    }

    private Decision decision(Input input, Scope captured) {
        if (captured.owner == Owner.NONE || !captured.equals(scope)) return null;
        return new Decision(input, captured);
    }
}
