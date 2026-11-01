package com.no5.ui;

import android.os.Parcel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Real Android Parcel decoding, in the existing host API-29 runtime; no app launch or token cycle. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class UiStateWireTest {
    @Test public void snapshotRoundTripPreservesProducerObservationAndGeneration() {
        UiStateSnapshot original = snapshot("A title", 1);
        Parcel parcel = Parcel.obtain();
        try {
            original.writeTo(parcel); parcel.setDataPosition(0);
            UiStateSnapshot decoded = UiStateSnapshot.readFrom(parcel);
            assertEquals(original.language, decoded.language); assertEquals(original.revision, decoded.revision);
            assertEquals(original.media.title, decoded.media.title); assertEquals(original.media.artist, decoded.media.artist);
            assertEquals(original.media.observedAtEpochMs, decoded.media.observedAtEpochMs);
            assertEquals(original.media.receivedAtElapsedMs, decoded.media.receivedAtElapsedMs);
            assertEquals(original.media.sourceGeneration, decoded.media.sourceGeneration);
            assertTrue(decoded.media.isFresh(14999)); assertFalse(decoded.media.isFresh(15000));
        } finally { parcel.recycle(); }
    }
    @Test public void unknownVersionIsRefusedBeforeOtherFields() {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeInt(2); parcel.setDataPosition(0);
            assertThrows(IllegalArgumentException.class, () -> UiStateSnapshot.readFrom(parcel));
        } finally { parcel.recycle(); }
    }
    @Test public void oversizedParcelIsRefused() {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeByteArray(new byte[UiStateContract.MAX_PARCEL_BYTES + 1]); parcel.setDataPosition(0);
            assertThrows(IllegalArgumentException.class, () -> UiStateSnapshot.readFrom(parcel));
        } finally { parcel.recycle(); }
    }
    @Test public void trailingPayloadIsRefused() {
        Parcel parcel = Parcel.obtain();
        try {
            snapshot("A", 1).writeTo(parcel); parcel.writeString("extra"); parcel.setDataPosition(0);
            assertThrows(IllegalArgumentException.class, () -> UiStateSnapshot.readFrom(parcel));
        } finally { parcel.recycle(); }
    }
    @Test public void unsupportedLanguageIsRefused() {
        Parcel parcel = raw("fr", "A", 1, 1);
        try { assertThrows(IllegalArgumentException.class, () -> UiStateSnapshot.readFrom(parcel)); }
        finally { parcel.recycle(); }
    }
    @Test public void controlCharactersCannotReachHeaderText() {
        Parcel parcel = raw("de", "A\nB", 1, 1);
        try { assertThrows(IllegalArgumentException.class, () -> UiStateSnapshot.readFrom(parcel)); }
        finally { parcel.recycle(); }
    }
    @Test public void oversizedTextCannotReachHeaderText() {
        Parcel parcel = raw("de", new String(new char[513]).replace('\0', 'A'), 1, 1);
        try { assertThrows(IllegalArgumentException.class, () -> UiStateSnapshot.readFrom(parcel)); }
        finally { parcel.recycle(); }
    }
    @Test public void invalidAvailabilityIsRefused() {
        Parcel parcel = raw("de", "A", 1, 2);
        try { assertThrows(IllegalArgumentException.class, () -> UiStateSnapshot.readFrom(parcel)); }
        finally { parcel.recycle(); }
    }
    @Test public void negativeSourceGenerationIsRefused() {
        Parcel parcel = raw("de", "A", -1, 1);
        try { assertThrows(IllegalArgumentException.class, () -> UiStateSnapshot.readFrom(parcel)); }
        finally { parcel.recycle(); }
    }
    private static UiStateSnapshot snapshot(String title, long generation) {
        return new UiStateSnapshot("af", 8, true,
                new UiStateSnapshot.NowPlaying(title, "Artist", "PLAYING", generation, 100000, 10000, true));
    }
    private static Parcel raw(String language, String title, long generation, int availability) {
        Parcel parcel = Parcel.obtain();
        parcel.writeInt(UiStateContract.VERSION); parcel.writeString(language); parcel.writeLong(8);
        parcel.writeString(title); parcel.writeString("Artist"); parcel.writeString("PLAYING");
        parcel.writeLong(generation); parcel.writeLong(100000); parcel.writeLong(10000); parcel.writeInt(availability);
        parcel.setDataPosition(0); return parcel;
    }
}
