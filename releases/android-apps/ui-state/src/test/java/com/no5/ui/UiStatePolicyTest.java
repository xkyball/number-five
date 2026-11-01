package com.no5.ui;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Host tests exercise real admission and metadata/freshness decisions, without a transport or live token. */
public final class UiStatePolicyTest {
    @Test public void callerMustBeOneApprovedPackageInSameAndroidUser() {
        for (String name : new String[]{"com.no5.home", "com.no5.helm", "com.no5.map", "com.no5.lumina.control"})
            assertTrue(UiAdmissionPolicy.permits(10123, 10124, new String[]{name}, true, true));
        assertFalse(UiAdmissionPolicy.permits(110123, 10124, new String[]{"com.no5.map"}, true, true));
        assertFalse(UiAdmissionPolicy.permits(10123, 10124, new String[]{"com.no5.map", "other"}, true, true));
        assertFalse(UiAdmissionPolicy.permits(10123, 10124, new String[]{"other"}, true, true));
        assertFalse(UiAdmissionPolicy.permits(10123, 10124, new String[]{"com.no5.map"}, false, true));
        assertFalse(UiAdmissionPolicy.permits(10123, 10124, new String[]{"com.no5.map"}, true, false));
        assertFalse(UiAdmissionPolicy.permits(-1, 10124, null, true, true));
    }
    @Test public void exactlyTheThreeLanguagesAreAccepted() {
        assertTrue(UiLanguage.supported("de")); assertTrue(UiLanguage.supported("en")); assertTrue(UiLanguage.supported("af"));
        assertFalse(UiLanguage.supported("DE")); assertFalse(UiLanguage.supported("en-US")); assertFalse(UiLanguage.supported(null));
    }
    @Test public void staleObservationIsNotRenewedByRepeatedReadingOrReceipt() throws Exception {
        UiMediaProjection projection = new UiMediaProjection();
        JSONObject observation = audio("a", 1, 100000, "Song");
        UiStateSnapshot.NowPlaying first = projection.accept(observation, 100000, 10000);
        assertTrue(first.isFresh(14999)); assertFalse(first.isFresh(15000));
        UiStateSnapshot.NowPlaying duplicate = projection.accept(observation, 106000, 16000);
        assertSame(first, duplicate); assertEquals(10000, duplicate.receivedAtElapsedMs);
        assertFalse(duplicate.isFresh(16000));
    }
    @Test public void alreadyOldUpstreamDataStartsStaleDespiteRecentNetworkReceipt() throws Exception {
        UiMediaProjection projection = new UiMediaProjection();
        UiStateSnapshot.NowPlaying old = projection.accept(audio("a", 1, 94000, "Old"), 100000, 10000);
        assertEquals(4000, old.receivedAtElapsedMs); assertFalse(old.isFresh(10000));
    }
    @Test public void sourceAndRevisionChangesRejectOldReplies() throws Exception {
        UiMediaProjection projection = new UiMediaProjection();
        UiStateSnapshot.NowPlaying a = projection.accept(audio("a", 9, 100000, "A"), 100000, 10000);
        assertSame(a, projection.accept(audio("a", 8, 100100, "Too late"), 100100, 10100));
        UiStateSnapshot.NowPlaying b = projection.accept(audio("b", 1, 100200, "B"), 100200, 10200);
        assertEquals(a.sourceGeneration + 1, b.sourceGeneration);
        assertSame(b, projection.accept(audio("a", 10, 100300, "Retired A"), 100300, 10300));
        assertEquals("B", projection.current().title);
    }
    @Test public void invalidNewSourceCannotRetireTheCurrentValidSource() throws Exception {
        UiMediaProjection projection = new UiMediaProjection();
        UiStateSnapshot.NowPlaying a = projection.accept(audio("a", 1, 100000, "A"), 100000, 10000);
        JSONObject invalid = audio("b", 1, 100100, "Bad\nTitle");
        assertThrows(IllegalArgumentException.class, () -> projection.accept(invalid, 100100, 10100));
        assertSame(a, projection.current());
        UiStateSnapshot.NowPlaying updated = projection.accept(audio("a", 2, 100200, "A2"), 100200, 10200);
        assertEquals(a.sourceGeneration, updated.sourceGeneration); assertEquals("A2", updated.title);
    }
    @Test public void disconnectPreservesLastTextAndObservationButWithdrawsAvailability() throws Exception {
        UiMediaProjection projection = new UiMediaProjection();
        UiStateSnapshot.NowPlaying a = projection.accept(audio("a", 1, 100000, "A"), 100000, 10000);
        UiStateSnapshot.NowPlaying unavailable = projection.unavailable();
        assertEquals(a.title, unavailable.title); assertEquals(a.observedAtEpochMs, unavailable.observedAtEpochMs);
        assertFalse(unavailable.available); assertFalse(unavailable.isFresh(10001));
        assertSame(unavailable, projection.unavailable());
    }
    @Test public void fieldLimitsAndFutureClockCannotCreateUsableMetadata() throws Exception {
        UiMediaProjection projection = new UiMediaProjection();
        assertThrows(IllegalArgumentException.class, () -> projection.accept(audio("a", 1, 106000, "Future"), 100000, 10000));
        assertThrows(IllegalArgumentException.class, () -> new UiStateSnapshot.NowPlaying(new String(new char[513]), "", "PLAYING", 1, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new UiStateSnapshot.NowPlaying("", "", "MAYBE", 1, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new UiStateSnapshot.NowPlaying("", "", "PLAYING", -1, 1, 1, true));
        JSONObject artists = audio("a", 1, 100000, "A"); JSONArray tooMany = new JSONArray();
        for (int i = 0; i < 11; i++) tooMany.put("Artist"); artists.getJSONObject("media").put("artists", tooMany);
        assertThrows(IllegalArgumentException.class, () -> projection.accept(artists, 100000, 10000));
    }
    @Test public void capturedTransportGenerationRejectsLatePacketsAndDisconnects() throws Exception {
        UiMediaProjection projection = new UiMediaProjection();
        projection.beginSource(1);
        UiStateSnapshot.NowPlaying a = projection.accept(audio("a", 9, 100000, "A"), 1, 100000, 10000);
        UiStateSnapshot.NowPlaying waiting = projection.beginSource(2);
        assertFalse(waiting.available); assertEquals("", waiting.title);
        assertSame(waiting, projection.accept(audio("a", 10, 100100, "Old A"), 1, 100100, 10100));
        // The new source can have its own older observation/revision. It must replace old-source text,
        // while freshness still uses that actual (here already stale) observation.
        UiStateSnapshot.NowPlaying b = projection.accept(audio("b", 1, 94000, "B"), 2, 100200, 10200);
        assertEquals("B", b.title); assertFalse(b.isFresh(10200));
        assertTrue(b.sourceGeneration > a.sourceGeneration);
        assertSame(b, projection.unavailable(1));
        assertSame(b, projection.beginSource(1));
        assertSame(b, projection.accept(audio("a", 99, 100300, "Old A again"), 1, 100300, 10300));
    }
    private static JSONObject audio(String source, long revision, long observation, String title) throws Exception {
        return new JSONObject().put("serverSessionId", source).put("revision", revision)
                .put("media", new JSONObject().put("title", title).put("artists", new JSONArray().put("Artist")).put("state", "PLAYING"))
                .put("audioSource", new JSONObject().put("connected", true).put("active", true)
                        .put("quality", new JSONObject().put("status", "LIVE").put("timestampEpochMs", observation)));
    }
}
