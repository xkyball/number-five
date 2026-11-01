package com.no5.ui;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WorkspaceLabelCoverageTest {
    @Test public void implicitNativeWorkspaceLabelsHaveCompleteTranslations() {
        String[][] rows = {
            {"Klang, Hörplatz und Bedienung.", "Sound, listening position and controls.", "Klank, luisterposisie en bediening."},
            {"Entfernung", "Distance", "Afstand"},
            {"Anzeige", "Display", "Vertoning"},
            {"Kartenbibliothek", "Chart library", "Kaartbiblioteek"}
        };
        String[] languages = {"de", "en", "af"};
        for (String[] row : rows) {
            assertTrue(UiTranslations.contains(row[0]));
            for (int n=0;n<3;n++) assertEquals(row[n], UiTranslations.lookup(languages[n],row[0]));
        }
    }
    @Test public void exactSourceOnlyLabelsKeepApprovedWordsAndUserArgumentsUnchanged() {
        assertEquals("Oudio-opstelling", UiTranslations.lookup("af", "Audio einrichten"));
        assertEquals("Preset „%s“ bearbeiten", UiTranslations.lookup("de", "Preset „%s“ bearbeiten"));
        assertEquals("Unbekannter Benutzername", UiTranslations.lookup("af", "Unbekannter Benutzername"));
    }
}
