package com.no5.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29,35], qualifiers = "en-rUS-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GatewayConfigurationActivityTest {
    @Before fun before() {
        ShadowBuild.setFingerprint("generic/test")
        ShadowBuild.setHardware("ranchu")
        RuntimeEnvironment.getApplication().getSharedPreferences("no5_gateway_configuration", Context.MODE_PRIVATE).edit().clear().commit()
        GatewayCheckerFactory.testFactory = { object : GatewayAddressChecker {
            override fun online() = false
            override fun discover(origin: com.no5.gateway.GatewayOrigin): String? = null
            override fun reachable(origin: com.no5.gateway.GatewayOrigin, address: String) = false
        } }
    }
    @After fun after() { GatewayCheckerFactory.testFactory = null; GatewayTrustFactory.testFactory = null }
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Test fun failedAccessCheckCanBeRepairedByImportingForTheAlreadySavedOrigin() {
        val origin = com.no5.gateway.GatewayOrigin.parse("https://no5-marineos-lab.local:9443")!!
        GatewayConfigurationStore(RuntimeEnvironment.getApplication()).save(com.no5.gateway.GatewayConfiguration(
            origin, "192.168.68.88", com.no5.gateway.GatewayAddressSource.MANUAL,
            state = com.no5.gateway.GatewayAddressState.NEEDS_INPUT, checkedAt = 1, consecutiveFailures = 2))
        Robolectric.buildActivity(GatewayConfigurationActivity::class.java).setup().use { host ->
            all(host.get().window.decorView).filterIsInstance<android.widget.Button>()
                .first { it.text == "Gateway-Zugang" }.performClick()
            host.get().gatewayAccessDialog!!.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val request = org.robolectric.Shadows.shadowOf(host.get()).nextStartedActivityForResult
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
            assertNull(request.intent.getStringExtra("key"))
        }
    }
    @Test fun firstProvisioningCanChooseMaterialWithoutActivatingAnUnconfirmedAddress() {
        Robolectric.buildActivity(GatewayConfigurationActivity::class.java).setup().use { host ->
            all(host.get().window.decorView).filterIsInstance<android.widget.Button>()
                .first { it.text == "Gateway-Zugang" }.performClick()
            host.get().gatewayAccessDialog!!.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT,
                org.robolectric.Shadows.shadowOf(host.get()).nextStartedActivityForResult.intent.action)
            assertNull(GatewayConfigurationStore(RuntimeEnvironment.getApplication()).read())
        }
    }
    @Test fun deviceScopedNativeFormPreservesLogicalHostnameAndHasNoKeyTextField() {
        for (night in listOf(false,true)) {
            RuntimeEnvironment.getApplication().getSharedPreferences("no5_home", Context.MODE_PRIVATE).edit().putBoolean("night", night).commit()
            Robolectric.buildActivity(GatewayConfigurationActivity::class.java).setup().use { host ->
                val activity = host.get(); val root = activity.findViewById<ViewGroup>(android.R.id.content)
                root.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY)); root.layout(0,0,2000,1200)
                val fields = all(root).filterIsInstance<EditText>()
                assertEquals(2, fields.size)
                assertFalse(all(root).filterIsInstance<TextView>().any { it.text.contains("Passwort einrichten") })
                assertEquals("https://no5-marineos-lab.local:9443", activity.findViewById<EditText>(GatewayConfigurationActivity.ORIGIN_ID).text.toString())
                assertTrue(all(root).filterIsInstance<TextView>().any { it.text.contains("Virtueller Pi") })
                val bitmap = Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
                val file = File(System.getProperty("no5.renderOutputDir"), "gateway-config-api${RuntimeEnvironment.getApiLevel()}-${if(night) "night" else "day"}.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        }
    }
    @Test fun sharedAccessDialogHasExactlyOneEntryAndRetainsFileImportWithoutEnrollment() {
        Robolectric.buildActivity(GatewayConfigurationActivity::class.java).setup().use { host ->
            all(host.get().window.decorView).filterIsInstance<android.widget.Button>()
                .first { it.text == "Gateway-Zugang" }.performClick()
            val dialog = host.get().gatewayAccessDialog!!
            val fields = all(dialog.window!!.decorView).filterIsInstance<EditText>()
            assertEquals(1, fields.size)
            assertFalse(fields.single().isSaveEnabled)
            assertEquals("Gateway-Key oder Passwort", fields.single().hint.toString())
            assertEquals("Key-Datei laden", dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).text.toString())
            assertFalse(all(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("einrichten", true) })
            dialog.dismiss()
        }
    }
    @Test fun caStatusIsSeparateAndManualImportIsHiddenUntilAdvancedIsOpened() {
        Robolectric.buildActivity(GatewayConfigurationActivity::class.java).setup().use { host ->
            val activity = host.get()
            val views = all(activity.window.decorView)
            assertTrue(activity.findViewById<TextView>(GatewayConfigurationActivity.CA_STATUS_ID).text.contains("CA"))
            val import = views.filterIsInstance<android.widget.Button>().single { it.text == "Andere Gateway-CA importieren" }
            assertFalse(import.isShown)
            views.filterIsInstance<android.widget.Button>().single { it.text == "Erweitert" }.performClick()
            assertTrue(import.isShown)
            views.filterIsInstance<android.widget.Button>().single { it.text == "Erweitert" }.performClick()
            assertFalse(import.isShown)
        }
    }
    @Test fun validCaRemainsVisibleWhenThereIsNoNetwork() {
        val ca = java.util.Base64.getDecoder().decode(javaClass.getResource("/gateway-synthetic-ca.b64")!!.readText().trim())
        GatewayTrustFactory.testFactory = { object : com.no5.helm.security.GatewayTrustStore {
            override fun read(origin: com.no5.helm.security.GatewayOrigin) = ca.copyOf()
            override fun save(origin: com.no5.helm.security.GatewayOrigin, caCertificateDer: ByteArray) = error("No network write")
            override fun remove(origin: com.no5.helm.security.GatewayOrigin) = error("No deletion")
        } }
        Robolectric.buildActivity(GatewayConfigurationActivity::class.java).setup().use { host ->
            val text = host.get().findViewById<TextView>(GatewayConfigurationActivity.CA_STATUS_ID).text.toString()
            assertTrue(text, text.contains("Zertifikat gültig"))
            assertFalse(text.contains("wird automatisch geladen"))
        }
    }
    @Test fun scrollingFormAndFooterKeepPhysicalKnobClearanceAndTouchSizeInBothThemes() {
        for (height in listOf(1200, 928)) for (night in listOf(false, true)) {
            RuntimeEnvironment.getApplication().getSharedPreferences("no5_home", Context.MODE_PRIVATE).edit().putBoolean("night", night).commit()
            Robolectric.buildActivity(GatewayConfigurationActivity::class.java).setup().use { host ->
                val root = host.get().findViewById<ViewGroup>(android.R.id.content)
                root.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 2000, height)
                val scroll = host.get().findViewById<android.widget.ScrollView>(GatewayConfigurationActivity.FORM_ID)
                for (bottom in listOf(false, true)) {
                    scroll.scrollTo(0, if (bottom) scroll.getChildAt(0).height else 0)
                    all(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }.forEach { view ->
                        val bounds = Rect()
                        if (view.getGlobalVisibleRect(bounds)) {
                            val offset = IntArray(2); root.getLocationOnScreen(offset); bounds.offset(-offset[0], -offset[1])
                            val scale = minOf(1f, height / 1200f)
                            val left = (2000 - 2000 * scale) / 2
                            val stageBounds = RectF((bounds.left - left) / scale, bounds.top / scale,
                                (bounds.right - left) / scale, bounds.bottom / scale)
                            assertFalse("Gateway control intersects knob safety area: ${view.text} $stageBounds", HomeStage.intersectsSafety(stageBounds))
                            if (view is android.widget.Button && bounds.height() >= view.height * scale - 1) {
                                assertTrue("Button touch target below 48dp: ${view.text} $bounds", bounds.width() >= 96 && bounds.height() >= 96)
                            }
                        }
                    }
                    val bitmap = Bitmap.createBitmap(2000, height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    File(System.getProperty("no5.renderOutputDir"), "gateway-safe-api${RuntimeEnvironment.getApiLevel()}-${if(night) "night" else "day"}-2000x$height-${if(bottom) "bottom" else "top"}.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
    }
}
