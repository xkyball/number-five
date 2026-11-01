package com.no5.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class WifiUiTextTest {
    @Test public void allThreeLanguagesDistinguishRadioConnectionAndMissingSignal() {
        String[] languages = {"de", "en", "af"};
        String[] off = {"WLAN aus", "Wi-Fi off", "Wi-Fi af"};
        String[] disconnected = {"WLAN nicht verbunden", "Wi-Fi disconnected", "Wi-Fi nie verbind nie"};
        String[] unknown = {"WLAN unbekannt", "Wi-Fi unknown", "Wi-Fi onbekend"};
        String[] missing = {"WLAN verbunden · Signal unbekannt", "Wi-Fi connected · signal unknown", "Wi-Fi verbind · sein onbekend"};
        for (int n = 0; n < languages.length; n++) {
            assertEquals(off[n], WifiUiText.summary(languages[n], WifiSignal.off()));
            assertEquals(disconnected[n], WifiUiText.summary(languages[n], WifiSignal.disconnected()));
            assertEquals(unknown[n], WifiUiText.summary(languages[n], null));
            assertEquals(missing[n], WifiUiText.summary(languages[n], WifiSignal.connected(null)));
        }
    }
    @Test public void actualDbmIsPreservedAndScopeNeverClaimsGatewayOrInternetReachability() {
        String[] languages = {"de", "en", "af"};
        String[] scope = {"Lokaler Funkempfang · keine Aussage über Internet oder Gateway",
            "Local radio reception · does not indicate Internet or gateway availability",
            "Plaaslike radio-ontvangs · dui nie internet- of gatewaybeskikbaarheid aan nie"};
        for (int n = 0; n < languages.length; n++) {
            String details = WifiUiText.details(languages[n], WifiSignal.connected(-71));
            assertTrue(details.contains("RSSI: -71 dBm"));
            assertTrue(details.endsWith(scope[n]));
            assertTrue(WifiUiText.summary(languages[n], WifiSignal.connected(-71)).endsWith("2/4"));
            assertFalse(WifiUiText.details(languages[n], WifiSignal.connected(null)).contains("0 dBm"));
            assertFalse(WifiUiText.details(languages[n], WifiSignal.off()).contains("-71"));
        }
    }
}
