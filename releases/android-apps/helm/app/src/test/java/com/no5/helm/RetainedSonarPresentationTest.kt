package com.no5.helm

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.no5.domain.DeterministicMockSonarSource
import org.junit.Test
import org.junit.Assert.*

class RetainedSonarPresentationTest {
    @Test fun acousticHistoryDoesNotInvalidateHostButPreferencesDo() {
        var now=1_000_000L
        val c=SonarNativeController(DeterministicMockSonarSource(now={now})){now}
        val saved=retainedSonarPresentation(c)
        val observer=SnapshotStateObserver{it()};var invalidations=0;observer.start()
        try {
            c.start();val initial=c.snapshot
            observer.observeReads(this,{_:Any->invalidations++}){saved.value}
            repeat(20){now+=100;c.tick();Snapshot.sendApplyNotifications()}
            assertNotEquals(initial,c.snapshot)
            assertEquals(0,invalidations)
            c.gain(1.0);Snapshot.sendApplyNotifications()
            assertEquals(1,invalidations)
        } finally {observer.stop();observer.clear();c.dispose()}
    }
}
