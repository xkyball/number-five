package com.no5.ui;

import android.os.Parcel;

/** Immutable snapshots use original producer observation times; reading never renews freshness. */
public class UiStateSnapshot {
    public final String language;
    public final long revision;
    public final boolean connected;
    public final NowPlaying media;

    public UiStateSnapshot(String language, long revision, boolean connected, NowPlaying media) {
        this.language = UiLanguage.requireSupported(language);
        if (revision < 0 || media == null) throw new IllegalArgumentException("INVALID_UI_STATE");
        this.revision = revision;
        this.connected = connected;
        this.media = media;
    }
    public UiStateSnapshot withConnection(boolean connected) {
        return new UiStateSnapshot(language, revision, connected, media);
    }
    public static UiStateSnapshot offline(String language) {
        return new UiStateSnapshot(language, 0, false, NowPlaying.empty());
    }
    public void writeTo(Parcel out) {
        out.writeInt(UiStateContract.VERSION);
        out.writeString(language);
        out.writeLong(revision);
        media.writeTo(out);
    }
    public static UiStateSnapshot readFrom(Parcel in) {
        if (in.dataSize() > UiStateContract.MAX_PARCEL_BYTES || in.readInt() != UiStateContract.VERSION)
            throw new IllegalArgumentException("INVALID_UI_VERSION");
        String language = in.readString();
        long revision = in.readLong();
        UiStateSnapshot snapshot = new UiStateSnapshot(language, revision, true, NowPlaying.readFrom(in));
        if (in.dataAvail() != 0) throw new IllegalArgumentException("INVALID_UI_TRAILING_DATA");
        return snapshot;
    }

    public static final class NowPlaying {
        public final String title, artist, playback;
        public final long sourceGeneration, observedAtEpochMs, receivedAtElapsedMs;
        public final boolean available;

        public NowPlaying(String title, String artist, String playback, long sourceGeneration,
                          long observedAtEpochMs, long receivedAtElapsedMs, boolean available) {
            this.title = checkedText(title);
            this.artist = checkedText(artist);
            if (!"PLAYING".equals(playback) && !"PAUSED".equals(playback) &&
                    !"STOPPED".equals(playback) && !"UNKNOWN".equals(playback))
                throw new IllegalArgumentException("INVALID_PLAYBACK");
            if (sourceGeneration < 0 || observedAtEpochMs < 0 || receivedAtElapsedMs < 0)
                throw new IllegalArgumentException("INVALID_OBSERVATION");
            this.playback = playback;
            this.sourceGeneration = sourceGeneration;
            this.observedAtEpochMs = observedAtEpochMs;
            this.receivedAtElapsedMs = receivedAtElapsedMs;
            this.available = available;
        }
        public boolean isFresh(long nowElapsedMs) {
            return available && observedAtEpochMs > 0 && receivedAtElapsedMs > 0 &&
                    nowElapsedMs >= receivedAtElapsedMs && nowElapsedMs - receivedAtElapsedMs < UiStateContract.MEDIA_FRESH_MS;
        }
        public static NowPlaying empty() { return new NowPlaying("", "", "UNKNOWN", 0, 0, 0, false); }
        private void writeTo(Parcel out) {
            out.writeString(title); out.writeString(artist); out.writeString(playback);
            out.writeLong(sourceGeneration); out.writeLong(observedAtEpochMs);
            out.writeLong(receivedAtElapsedMs); out.writeInt(available ? 1 : 0);
        }
        private static NowPlaying readFrom(Parcel in) {
            String title = in.readString(), artist = in.readString(), playback = in.readString();
            long generation = in.readLong(), observation = in.readLong(), received = in.readLong();
            int available = in.readInt();
            if (available != 0 && available != 1) throw new IllegalArgumentException("INVALID_AVAILABILITY");
            return new NowPlaying(title, artist, playback, generation, observation, received, available == 1);
        }
        private static String checkedText(String text) {
            if (text == null || text.length() > UiStateContract.MAX_TEXT_CHARS)
                throw new IllegalArgumentException("INVALID_UI_TEXT");
            for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i)))
                throw new IllegalArgumentException("INVALID_UI_TEXT");
            return text;
        }
    }
}
