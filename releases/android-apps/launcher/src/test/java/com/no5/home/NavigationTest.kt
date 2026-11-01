package com.no5.home

import android.app.Application
import android.content.*
import android.content.pm.*
import com.no5.navigation.AppNavigation as Nav
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class NavigationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun install(pkg: String, enabled: Boolean = true, exported: Boolean = true) {
        val component = Nav.component(pkg)!!
        val appInfo = ApplicationInfo().apply { packageName = pkg; this.enabled = true }
        val info = PackageInfo().apply {
            packageName = pkg
            applicationInfo = appInfo
            activities = arrayOf(ActivityInfo().apply {
                packageName = pkg; name = component.className; this.enabled = enabled; this.exported = exported
                applicationInfo = appInfo
            })
        }
        // No intent-filter resolver entry: this models the reported Lumina failure.
        shadowOf(app.packageManager).installPackage(info)
    }
    @Test fun explicitComponentsWorkWithoutLauncherResolutionAndRetainTasks() {
        assertEquals("com.no5.helm", Nav.HELM)
        assertEquals("com.no5.map", Nav.MAP)
        assertEquals(ComponentName("com.no5.helm", "com.no5.helm.MainActivity"), Nav.component(Nav.HELM))
        assertEquals(ComponentName("com.no5.map", "com.no5.helm.MainActivity"), Nav.component(Nav.MAP))
        for (pkg in listOf(Nav.MAP, Nav.HELM)) {
            install(pkg)
            assertTrue(Nav.installed(app, pkg))
            repeat(10) {
                assertNull(Nav.openApp(app, pkg, true))
                val started = shadowOf(app).nextStartedActivity
                assertEquals(Nav.component(pkg), started.component)
                assertEquals(Intent.ACTION_MAIN, started.action)
                assertTrue(started.hasCategory(Intent.CATEGORY_LAUNCHER))
                assertEquals(0, started.flags and (Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                assertFalse(started.hasExtra(Nav.NIGHT))
            }
        }
    }
    @Test fun mediaOpensExplicitHelmAudioWithoutClearingItsTask() {
        install(Nav.HELM)
        val intent = requireNotNull(Nav.audioIntent(app))
        assertEquals(Nav.component(Nav.HELM), intent.component)
        assertEquals("AUDIO", intent.getStringExtra(Nav.OPEN_PAGE))
        assertEquals(setOf(Nav.OPEN_PAGE), intent.extras!!.keySet())
        assertEquals(0, intent.flags and (Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertNull(Nav.openAudio(app))
        assertEquals(Nav.component(Nav.HELM), shadowOf(app).nextStartedActivity.component)
    }
    @Test fun missingDisabledAndPrivateActivitiesAreUnavailable() {
        assertFalse(Nav.installed(app, Nav.MAP))
        assertNotNull(Nav.openApp(app, Nav.MAP, false))
        install(Nav.MAP, enabled = false); assertFalse(Nav.installed(app, Nav.MAP))
        install(Nav.HELM, exported = false); assertFalse(Nav.installed(app, Nav.HELM))
        assertNull(Nav.appIntent(app, "untrusted.package", true))
    }
    @Test fun homeAloneReceivesThemeHint() {
        val intent = Nav.appIntent(app, Nav.HOME, true)!!
        assertEquals(Nav.component(Nav.HOME), intent.component)
        assertTrue(intent.getBooleanExtra(Nav.NIGHT, false))
    }
    @Test fun removedOrDeniedLaunchReportsError() {
        install(Nav.MAP)
        for (exception in listOf(ActivityNotFoundException(), SecurityException())) {
            val denied = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) { throw exception }
            }
            assertNotNull(Nav.openApp(denied, Nav.MAP, false))
        }
    }
    @Test fun settingsUsesPublicAction() {
        assertNull(Nav.openSettings(app))
        assertEquals(android.provider.Settings.ACTION_SETTINGS, shadowOf(app).nextStartedActivity.action)
    }
    @Test fun oemChoiceIsExplicitAndCannotLoopBackToNo5() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        fun candidate(pkg: String) = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg; name = "$pkg.Home"; enabled = true; exported = true
                applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true }
            }
            nonLocalizedLabel = pkg
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(query, listOf(candidate(Nav.HOME), candidate("oem.home")))
        val choices = Nav.otherHomes(app)
        assertEquals(1, choices.size)
        assertNull(Nav.openOtherHome(app, choices.single()))
        assertEquals(ComponentName("oem.home", "oem.home.Home"), shadowOf(app).nextStartedActivity.component)
        assertNotNull(Nav.openOtherHome(app, Nav.HomeChoice("Unknown", ComponentName("unknown", "unknown.Home"))))
    }
}
