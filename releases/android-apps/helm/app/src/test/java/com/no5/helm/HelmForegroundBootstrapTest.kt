package com.no5.helm

import android.view.ViewGroup
import android.widget.TextView
import com.no5.helm.control.HelmSourceRegistry
import com.no5.helm.control.HelmSourceService
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class HelmForegroundBootstrapTest {
    @After fun releaseOwner() = HelmSourceRegistry.resetForTest()

    @Test fun pendingServiceLeavesOnlyNativeVersionViewOnFirstActivityTurn() {
        Robolectric.buildActivity(MainActivity::class.java).create().use { host ->
            val activity = host.get()
            assertFalse(HelmSourceService.isForegroundReady(activity))
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertEquals(1, content.childCount)
            val placeholder = content.getChildAt(0)
            assertTrue("A pending FGS must not attach the full Compose tree", placeholder is TextView)
            assertEquals("No5 HELM · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                (placeholder as TextView).text.toString())
        }
    }
}
