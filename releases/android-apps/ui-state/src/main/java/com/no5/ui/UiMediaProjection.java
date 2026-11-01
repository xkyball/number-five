package com.no5.ui;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, credential-free projection of an already accepted audio observation. No clock or I/O owner. */
public final class UiMediaProjection {
    private UiStateSnapshot.NowPlaying current = UiStateSnapshot.NowPlaying.empty();
    private String source = "";
    private long sourceRevision = -1, sourceGeneration;
    private final String[] retiredSources = new String[16];
    private int retiredAt;
    private long publisherGeneration = -1;
    public synchronized UiStateSnapshot.NowPlaying current() { return current; }

    /** A local owner establishes the boundary before starting an asynchronous transport. */
    public synchronized UiStateSnapshot.NowPlaying beginSource(long generation) {
        if (generation < 0) throw new IllegalArgumentException("INVALID_SOURCE_GENERATION");
        if (generation <= publisherGeneration) return current;
        publisherGeneration = generation;
        source = ""; sourceRevision = -1; retiredAt = 0;
        for (int i = 0; i < retiredSources.length; i++) retiredSources[i] = null;
        sourceGeneration++;
        current = new UiStateSnapshot.NowPlaying("", "", "UNKNOWN", sourceGeneration, 0, 0, false);
        return current;
    }
    /** The numeric generation is supplied by the local transport owner, never by JSON. */
    public synchronized UiStateSnapshot.NowPlaying accept(JSONObject value, long generation, long nowEpoch, long nowElapsed) {
        return generation == publisherGeneration ? accept(value, nowEpoch, nowElapsed) : current;
    }

    public synchronized UiStateSnapshot.NowPlaying accept(JSONObject value, long nowEpoch, long nowElapsed) {
        if (nowEpoch < 0 || nowElapsed < 0) throw new IllegalArgumentException("INVALID_CLOCK");
        try {
            JSONObject media = value.getJSONObject("media"), audio = value.getJSONObject("audioSource");
            JSONObject quality = audio.getJSONObject("quality");
            String key = value.getString("serverSessionId");
            if (key.length() > 200 || key.length() == 0) throw new IllegalArgumentException("INVALID_SOURCE");
            long revision = value.getLong("revision"), observation = quality.optLong("timestampEpochMs", 0);
            if (revision < 0 || observation < 0 || observation > nowEpoch + 5000)
                throw new IllegalArgumentException("INVALID_OBSERVATION");
            boolean changedSource = !key.equals(source);
            if (changedSource) for (String retired : retiredSources) if (key.equals(retired)) return current;
            if (!changedSource && (revision < sourceRevision || observation < current.observedAtEpochMs)) return current;
            String title = safeText(media.isNull("title") ? "" : media.optString("title", ""));
            JSONArray artists = media.optJSONArray("artists");
            if (artists != null && artists.length() > 10) throw new IllegalArgumentException("ARTIST_LIMIT");
            StringBuilder names = new StringBuilder();
            if (artists != null) for (int i = 0; i < artists.length(); i++) {
                String name = safeText(artists.optString(i, ""));
                if (name.isEmpty()) continue;
                int delimiter = names.length() > 0 ? 3 : 0;
                if (names.length() + delimiter + name.length() > UiStateContract.MAX_TEXT_CHARS) break;
                if (delimiter != 0) names.append(" · ");
                names.append(name);
            }
            String playback = media.optString("state", "UNKNOWN");
            if (!"PLAYING".equals(playback) && !"PAUSED".equals(playback) && !"STOPPED".equals(playback)) playback = "UNKNOWN";
            boolean available = audio.optBoolean("connected", false) && audio.optBoolean("active", false) &&
                    "LIVE".equals(quality.optString("status")) && observation > 0;
            long generation = changedSource && !source.isEmpty() ? sourceGeneration + 1 : Math.max(1, sourceGeneration);
            long observedElapsed;
            if (!changedSource && observation == current.observedAtEpochMs) observedElapsed = current.receivedAtElapsedMs;
            else observedElapsed = Math.max(0, nowElapsed - Math.max(0, nowEpoch - observation));
            UiStateSnapshot.NowPlaying next = new UiStateSnapshot.NowPlaying(title, names.toString(), playback,
                    generation, observation, observedElapsed, available);
            if (changedSource) {
                if (!source.isEmpty()) retiredSources[retiredAt++ % retiredSources.length] = source;
                source = key; sourceGeneration = generation;
            }
            sourceRevision = revision;
            if (!same(current, next)) current = next;
            return current;
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception malformed) { throw new IllegalArgumentException("INVALID_AUDIO_OBSERVATION"); }
    }
    public synchronized UiStateSnapshot.NowPlaying unavailable() {
        if (current.available) current = new UiStateSnapshot.NowPlaying(current.title, current.artist, current.playback,
                current.sourceGeneration, current.observedAtEpochMs, current.receivedAtElapsedMs, false);
        return current;
    }
    public synchronized UiStateSnapshot.NowPlaying unavailable(long generation) {
        return generation == publisherGeneration ? unavailable() : current;
    }
    private static String safeText(String value) {
        if (value == null) return "";
        if (value.length() > UiStateContract.MAX_TEXT_CHARS) throw new IllegalArgumentException("UI_TEXT_LIMIT");
        return value;
    }
    private static boolean same(UiStateSnapshot.NowPlaying a, UiStateSnapshot.NowPlaying b) {
        return a.title.equals(b.title) && a.artist.equals(b.artist) && a.playback.equals(b.playback) &&
                a.sourceGeneration == b.sourceGeneration && a.observedAtEpochMs == b.observedAtEpochMs &&
                a.receivedAtElapsedMs == b.receivedAtElapsedMs && a.available == b.available;
    }
}
