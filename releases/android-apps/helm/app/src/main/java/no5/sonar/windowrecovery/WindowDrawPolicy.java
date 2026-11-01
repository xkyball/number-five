package no5.sonar.windowrecovery;

/** Monotonic, bounded admission state; no renderer, packets, arrays, or timer. */
public final class WindowDrawPolicy {
    public static final long MISSING_DRAW_MS = 1000;
    public static final long FRESH_MS = 2000;
    private String source;
    private Long ping;
    private boolean eligible, advancing, drawn;
    private long lastProgress, lastDraw;
    private Long provedAgeAtProgress;

    public void progress(String sourceKey, Long pingId, boolean ownLiveVisibleUnfrozen,
                         long originalAgeMs, long now) {
        boolean same = sourceKey != null && sourceKey.equals(source);
        if (!same) { source = sourceKey; ping = null; advancing = false; }
        eligible = ownLiveVisibleUnfrozen && sourceKey != null && pingId != null
            && originalAgeMs >= 0 && originalAgeMs <= FRESH_MS;
        if (!eligible) { advancing = false; ping = null; return; }
        if (!pingId.equals(ping)) {
            advancing = ping != null;
            ping = pingId;
            lastProgress = now;
            provedAgeAtProgress = originalAgeMs;
        }
    }
    /** Candidate only: a new measured ping was observed locally. No origin age is invented.
     * A request-bound source-clock proof remains mandatory before any repair action.
     */
    public void observedProgress(String sourceKey, Long pingId, boolean ownLiveVisibleUnfrozen, long now) {
        boolean same = sourceKey != null && sourceKey.equals(source);
        if (!same) { source = sourceKey; ping = null; advancing = false; }
        eligible = ownLiveVisibleUnfrozen && sourceKey != null && pingId != null;
        if (!eligible) { advancing = false; ping = null; return; }
        if (ping == null || pingId > ping) {
            advancing = ping != null; ping = pingId; lastProgress = now;
            provedAgeAtProgress = null; // No origin age exists until the request-scoped proof completes.
        }
    }
    public void drawn(long now) { lastDraw = now; drawn = true; }
    public boolean needsProbe(long now) {
        return eligible && advancing && drawn && now >= lastDraw && now >= lastProgress
            && now - lastDraw >= MISSING_DRAW_MS && lastProgress > lastDraw
            && now - lastProgress <= (provedAgeAtProgress == null ? FRESH_MS : FRESH_MS - provedAgeAtProgress);
    }
    public boolean stillMissingAfterProof(long expectedDrawSequence,long currentDrawSequence,long now) {
        return expectedDrawSequence==currentDrawSequence && needsProbe(now);
    }
    public void pause() { eligible = false; advancing = false; ping = null; }
}
