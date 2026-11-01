package com.no5.home

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35], qualifiers="land-mdpi")
class HomeGatewayNoticeTest {
    private fun all(view: View): List<View> = listOf(view) + if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    @Test fun confirmedTlsHidesSetupNoticeWhileVersionRemainsVisible() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { host ->
            val activity = host.get()
            activity.updateGatewayNotice("Gateway · TLS und Status bestätigt", true)
            val views = all(activity.window.decorView)
            assertEquals(View.GONE, views.first { it.tag == "home-gateway-notice" }.visibility)
            val version = views.filterIsInstance<TextView>().single { it.tag == "installed-app-version" }
            assertTrue(version.text.toString().startsWith("HOME · B"))
            assertEquals(View.VISIBLE, version.visibility)
            assertNull(activity.packageManager.resolveContentProvider("com.no5.home.gateway", 0))
        }
    }

    @Test fun missingControllerShowsLocalStatusAndNeverOpensLegacyConfiguration() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { host ->
            val activity = host.get()
            activity.updateGatewayNotice("Gateway · nicht verfügbar · CONTROLLER_UNAVAILABLE", false)
            val notice = all(activity.window.decorView).filterIsInstance<Button>().single { it.tag == "home-gateway-notice" }
            assertEquals(View.VISIBLE, notice.visibility)
            notice.performClick()
            assertNull(shadowOf(activity).nextStartedActivity)
            val version = all(activity.window.decorView).filterIsInstance<TextView>().single { it.tag == "installed-app-version" }
            assertTrue(version.text.toString().startsWith("HOME · B"))
            assertFalse(all(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "Gateway konfigurieren" })
        }
    }
}
