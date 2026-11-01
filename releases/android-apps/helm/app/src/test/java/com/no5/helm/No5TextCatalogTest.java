package com.no5.helm;

import com.no5.ui.No5Text;
import org.junit.Test;
import static org.junit.Assert.*;

/** No Android runtime or transport fixtures: these exercise only the exact UI catalog. */
public class No5TextCatalogTest {
    @Test public void sameSourceKeysHaveAllThreeLanguages() {
        assertEquals("Einstellungen", No5Text.text("de", "Settings"));
        assertEquals("Settings", No5Text.text("en", "Einstellungen"));
        assertEquals("Instellings", No5Text.text("af", "Settings"));
        assertEquals("SPEICHERN UND SCHLIESSEN", No5Text.text("de", "SAVE AND CLOSE"));
        assertEquals("Stoor en sluit", No5Text.text("af", "Speichern und schließen"));
    }

    @Test public void onlyExactUiKeysAreTranslated() {
        String metadata = "Ocean Drive — Granger Bay / 海";
        assertSame(metadata, No5Text.text("af", metadata));
        assertEquals("prefix Settings suffix", No5Text.text("af", "prefix Settings suffix"));
        assertEquals(" Settings ", No5Text.text("af", " Settings "));
        assertEquals("SOURCE_EPOCH_CHANGED", No5Text.text("af", "SOURCE_EPOCH_CHANGED"));
        assertEquals("https://gateway.example:9443", No5Text.text("af", "https://gateway.example:9443"));
    }

    @Test public void formattedDataRemainArgumentsAndLiteralPercentIsSafe() {
        assertEquals("Lêer: Private charts.zip", No5Text.text("af", "Datei: %s", "Private charts.zip"));
        assertEquals("LÊER: Private charts.zip", No5Text.text("af", "DATEI: %s", "Private charts.zip"));
        assertEquals("3 AKTIEF", No5Text.text("af", "%d AKTIV", 3));
        assertEquals("Humidity: 55 %", No5Text.text("en", "Luftfeuchtigkeit: %s %%", "55"));
        assertTrue(No5Text.text("de", "UI-Helligkeit muss zwischen 30 und 100 % liegen.").contains("100 %"));
        assertEquals("https://gateway.example:9443", No5Text.text("en", "https://gateway.example:9443"));
    }

    @Test public void languageNamesRemainSelfIdentifyingAndCatalogCannotBeMutated() {
        assertEquals("Deutsch", No5Text.languageName("de"));
        assertEquals("English", No5Text.languageName("en"));
        assertEquals("Afrikaans", No5Text.languageName("af"));
        assertTrue(No5Text.hasTranslation("Save and close"));
        try { No5Text.translationKeys().clear(); fail("immutable catalog required"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void historicalNotesTranslateWithoutChangingOldClaimsOrTechnicalIds() {
        assertEquals("All apps are shown as icon tiles.",
                No5Text.text("en", "Alle Apps werden als Icon-Kacheln angezeigt."));
        assertEquals("Alle apps word as ikoonteëls vertoon.",
                No5Text.text("af", "Alle Apps werden als Icon-Kacheln angezeigt."));
        String original = "Strikte S01R04/S01R04Cold-Bildgates bleiben FAIL. Lumina-Kapazität und eine daraus abgeleitete Zoomgrenze sind noch nicht abgenommen.";
        String translated = No5Text.text("af", original);
        assertNotEquals(original, translated);
        assertTrue(translated.contains("S01R04/S01R04Cold"));
        assertTrue(translated.contains("FAIL"));
        assertTrue(translated.contains("nog nie"));
        assertTrue(No5Text.text("en", "HELM übernimmt den Gateway-Zugang und die origin-gebundene Sicherheit aus Home.").contains("from Home"));
    }
}
