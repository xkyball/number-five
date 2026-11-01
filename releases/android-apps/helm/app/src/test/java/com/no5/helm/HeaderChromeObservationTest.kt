package com.no5.helm

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.*
import org.junit.Test

class HeaderChromeObservationTest {
    private fun values()=HelmRenderValues(quality="LIVE",demo=false,text=mapOf(
        "rpm-port" to "0","rpm-stbd" to "—","hud-sog" to "12.3","hud-sog-unit" to "kn",
        "header-quality-portRpm" to "SOURCE · LIVE","header-quality-starboardRpm" to "SOURCE · UNAVAILABLE",
        "header-quality-speed" to "SOURCE · LIVE","header-source-label" to "SOURCE TELEMETRY",
        "deck-cog" to "24","secondary-port-trim" to "40%"))
    @Test fun hiddenInstrumentMotionDoesNotInvalidateTheVisibleHeader(){
        val initial=values();var feed by mutableStateOf(initial)
        val header=derivedStateOf{headerChromeValues(feed)}
        var redraws=0;val scope=Any();val observer=SnapshotStateObserver{it()};observer.start()
        try{
            observer.observeReads(scope,{redraws++}){header.value}
            feed=initial.copy(heading=190f,portTrim=55f,fuel=12f,text=initial.text+mapOf("deck-cog" to "190","secondary-port-trim" to "55%"))
            Snapshot.sendApplyNotifications();assertEquals(0,redraws)
            feed=feed.copy(text=feed.text+("rpm-port" to "1"));Snapshot.sendApplyNotifications();assertEquals(1,redraws)
        }finally{observer.stop();observer.clear()}
    }
    @Test fun realZeroUnknownAndEveryVisibleFieldKeepTheirOriginalText(){
        val before=values();val shown=headerChromeValues(before)
        for(name in listOf("rpm-port","rpm-stbd","hud-sog","hud-sog-unit","header-quality-portRpm","header-quality-starboardRpm","header-quality-speed","header-source-label"))assertEquals(before.text[name],shown.text[name])
        assertEquals("0",shown.text["rpm-port"]);assertEquals("—",shown.text["rpm-stbd"])
    }
    @Test fun staleQualityAndSourceChangesAreNeverSuppressed(){
        val old=values();val before=headerChromeValues(old)
        assertNotEquals(before,headerChromeValues(old.copy(quality="STALE")))
        assertNotEquals(before,headerChromeValues(old.copy(demo=true)))
        assertNotEquals(before,headerChromeValues(old.copy(text=old.text+("header-quality-speed" to "SOURCE · STALE"))))
    }
}
