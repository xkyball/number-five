package com.no5.home

import android.content.Intent
import org.robolectric.shadows.ShadowAlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.RadioButton
import com.no5.navigation.AppNavigation
import com.no5.ui.No5UiClient
import com.no5.ui.UiLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], qualifiers = "land-mdpi")
class HomeActivityTest {
    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun find(activity: HomeActivity, prefix: String) = all(activity.window.decorView)
        .filterIsInstance<TextView>().first { it.text.startsWith(prefix) }
    @Test fun repeatedSettingsReturnRetainsNightAndMissingAppsAreHonest() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            find(activity, "Nacht").performClick()
            assertNotNull(find(activity, "Tag"))
            repeat(10) {
                find(activity, "SYSTEM").performClick()
                assertEquals(android.provider.Settings.ACTION_SETTINGS, shadowOf(activity).nextStartedActivity.action)
                controller.pause().stop().restart().start().resume()
                assertNotNull(find(activity, "Tag"))
            }
            assertTrue(find(activity, "No5 MAP").text.contains("Nicht installiert"))
            find(activity, "No5 MAP").performClick()
            assertNotNull(find(activity, "App nicht installiert"))
        }
    }
    @Test fun incomingHomeThemeAndRecreationPersistWithoutDefaultChanges() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { controller ->
            controller.newIntent(Intent(Intent.ACTION_MAIN).putExtra(AppNavigation.NIGHT, true))
            assertNotNull(find(controller.get(), "Tag"))
            controller.newIntent(Intent(Intent.ACTION_MAIN))
            assertNotNull(find(controller.get(), "Tag"))
            controller.recreate()
            assertNotNull(find(controller.get(), "Tag"))
        }
    }
    @Suppress("DEPRECATION")
    @Test fun backClosesAppsAndHomeNeverFinishes() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { host ->
            val activity = host.get()
            find(activity, "Alle Apps").performClick()
            assertTrue(all(activity.window.decorView).filterIsInstance<HomeWorkspaceView>().isNotEmpty())
            host.recreate()
            assertTrue(all(host.get().window.decorView).filterIsInstance<HomeWorkspaceView>().isNotEmpty())
            host.get().onBackPressed()
            assertNotNull(find(host.get(), "No5 HELM"))
            repeat(5) { host.get().onBackPressed() }
            assertFalse(host.get().isFinishing)
            find(host.get(), "Alle Apps").performClick()
            host.newIntent(Intent(Intent.ACTION_MAIN))
            assertNotNull(find(host.get(), "No5 HELM"))
        }
    }

    @Test fun brandReturnsFromAppsStraightToHomeAndKeepsTheme() {
        Robolectric.buildActivity(HomeActivity::class.java,
            Intent().putExtra(AppNavigation.NIGHT, true)).setup().use { host ->
            find(host.get(), "Alle Apps").performClick()
            val brand = all(host.get().window.decorView).filterIsInstance<HomeHeaderView>().single()
            brand.performClick()
            assertNotNull(find(host.get(), "No5 HELM"))
            assertNotNull(find(host.get(), "Tag"))
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        }
    }

    @Suppress("DEPRECATION")
    @Test fun backDismissesMaintenanceWithoutLeavingHomeOrResettingTheme() {
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { host ->
            val activity = host.get()
            find(activity, "Nacht").performClick()
            find(activity, "Wartung").performClick()
            assertEquals(1,all(activity.window.decorView).filterIsInstance<HomeWorkspaceView>().size)
            activity.onBackPressed()
            assertTrue(all(activity.window.decorView).filterIsInstance<HomeWorkspaceView>().isEmpty())
            assertFalse(activity.isFinishing)
            assertNotNull(find(activity, "Tag"))
        }
    }

    @Test fun changedThemeSurvivesRecreationAfterAnIncomingThemeHint() {
        Robolectric.buildActivity(HomeActivity::class.java,
            Intent().putExtra(AppNavigation.NIGHT, true)).setup().use { host ->
            find(host.get(), "Tag").performClick()
            assertNotNull(find(host.get(), "Nacht"))
            host.recreate()
            assertNotNull(find(host.get(), "Nacht"))
        }
    }

    @Test fun unconfirmedLanguageClickRestoresSelectionWithoutClosingMaintenance() {
        UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(), "de")
        Robolectric.buildActivity(HomeActivity::class.java).setup().use { host ->
            val activity = host.get()
            find(activity, "Wartung").performClick()
            all(activity.window.decorView).single {it.tag=="no5-row-Sprache für alle Apps"}.performClick()
            val dialog = HomeActivity::class.java.getDeclaredField("dialog").apply{isAccessible=true}.get(activity) as android.app.Dialog
            val choices = all(dialog.window!!.decorView).filterIsInstance<RadioButton>()
            val german = choices.single { it.tag == "de" }
            val english = choices.single { it.tag == "en" }
            assertTrue(german.isChecked)

            english.performClick()
            // Native RadioButton auto-check must not masquerade as broker ACK.
            assertTrue(german.isChecked)
            assertFalse(english.isChecked)
            val field = HomeActivity::class.java.getDeclaredField("uiObserver").apply { isAccessible = true }
            (field.get(activity) as No5UiClient.Observer).onUiError("UI_UNAVAILABLE")

            assertTrue(dialog.isShowing)
            assertTrue(german.isChecked)
            assertFalse(english.isChecked)
            assertTrue(all(dialog.window!!.decorView).filterIsInstance<TextView>().any {
                it.text.toString() == "Nicht bestätigt · bisherige Sprache bleibt aktiv"
            })
            assertFalse(activity.isFinishing)
        }
    }

}
