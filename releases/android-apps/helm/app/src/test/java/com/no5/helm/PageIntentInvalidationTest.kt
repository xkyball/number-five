package com.no5.helm

import android.content.Intent
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class PageIntentInvalidationTest {
    private fun intent(page:String)=Intent().putExtra(com.no5.navigation.AppNavigation.OPEN_PAGE,page)
    @Test fun requestingCommittedPageDoesNotWriteRootSnapshotButColdNavigationStillWorks() {
        val activity=MainActivity();activity.committedPageForIntents="DASHBOARD"
        var writes=0;val same=intent("DASHBOARD")
        Snapshot.observe(null,{writes++}) { activity.acceptPageIntent(same) }
        assertNull(activity.requestedPage)
        assertEquals("An already displayed page must not invalidate the complete application root",0,writes)
        assertFalse(same.hasExtra(com.no5.navigation.AppNavigation.OPEN_PAGE))
        activity.committedPageForIntents=null
        activity.acceptPageIntent(intent("SONAR"))
        assertEquals("SONAR",activity.requestedPage)
    }
    @Test fun newerRequestCanReturnToCommittedPageAndUnknownRouteIsIgnored() {
        val activity=MainActivity();activity.committedPageForIntents="HELM"
        activity.acceptPageIntent(intent("SONAR"));assertEquals("SONAR",activity.requestedPage)
        activity.acceptPageIntent(intent("HELM"));assertEquals("HELM",activity.requestedPage)
        activity.acceptPageIntent(intent("UNKNOWN"));assertEquals("HELM",activity.requestedPage)
        activity.requestedPage=null;activity.committedPageForIntents="SONAR"
        activity.samePageIntentNeedsRouting=true
        activity.acceptPageIntent(intent("SONAR"));assertEquals("SONAR",activity.requestedPage)
    }
}
