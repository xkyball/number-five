package com.no5.helm.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class No5AppNavigationTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun helmAndMapUseSeparateAndroidPackagesWithStableComponentClasses() {
        assertEquals("com.no5.helm", No5AppNavigation.Target.HELM.packageName)
        assertEquals("com.no5.map", No5AppNavigation.Target.MAP.packageName)
        assertEquals("com.no5.helm.MainActivity", No5AppNavigation.Target.HELM.activityName)
        assertEquals("com.no5.helm.MainActivity", No5AppNavigation.Target.MAP.activityName)
    }

    private fun install(target: No5AppNavigation.Target, enabled: Boolean = true,
        exported: Boolean = true, appEnabled: Boolean = true) {
        shadowOf(context.packageManager).addOrUpdateActivity(ActivityInfo().apply {
            packageName = target.packageName; name = target.activityName
            this.enabled = enabled; this.exported = exported
            applicationInfo = ApplicationInfo().apply { packageName = target.packageName; this.enabled = appEnabled }
        })
        context.packageManager.setApplicationEnabledSetting(requireNotNull(target.packageName),
            if (appEnabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    @Test fun verifiedComponentsWorkWithoutAnImplicitLauncherResolution() {
        listOf(No5AppNavigation.Target.HELM, No5AppNavigation.Target.MAP, No5AppNavigation.Target.HOME).forEach { target ->
            install(target) // No implicit MAIN/LAUNCHER intent filter is registered.
            assertNull(context.packageManager.getLaunchIntentForPackage(target.packageName!!))
            val intent = requireNotNull(No5AppNavigation.appIntent(context, target, false))
            assertEquals(ComponentName(target.packageName, target.activityName!!), intent.component)
            assertEquals(Intent.ACTION_MAIN, intent.action)
            assertEquals(setOf(Intent.CATEGORY_LAUNCHER), intent.categories)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, intent.flags)
            assertNull(intent.data); assertNull(intent.clipData)
        }
    }

    @Test fun onlyHomeReceivesTheBooleanNightPresentation() {
        No5AppNavigation.Target.entries.filter { it != No5AppNavigation.Target.SETTINGS }.forEach { target ->
            install(target)
            listOf(false, true).forEach { night ->
                val intent = requireNotNull(No5AppNavigation.appIntent(context, target, night))
                if (target == No5AppNavigation.Target.HOME) {
                    assertEquals(setOf(No5AppNavigation.NIGHT), intent.extras!!.keySet())
                    assertEquals(night, intent.getBooleanExtra(No5AppNavigation.NIGHT, !night))
                } else assertNull(intent.extras)
            }
        }
    }

    @Test fun mediaOpensExplicitHelmAudioWithoutClearingItsTask() {
        assertNull(No5AppNavigation.audioIntent(context))
        install(No5AppNavigation.Target.HELM)
        val intent = requireNotNull(No5AppNavigation.audioIntent(context))
        assertEquals(ComponentName("com.no5.helm", "com.no5.helm.MainActivity"), intent.component)
        assertEquals("AUDIO", intent.getStringExtra(No5AppNavigation.OPEN_PAGE))
        assertEquals(setOf(No5AppNavigation.OPEN_PAGE), intent.extras!!.keySet())
        assertEquals(0, intent.flags and (Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertNull(No5AppNavigation.openAudio(context))
        assertEquals(intent.component, shadowOf(context).nextStartedActivity.component)
    }

    @Test fun missingDisabledUnexportedAndDisabledApplicationPreventLaunch() {
        val target = No5AppNavigation.Target.HELM
        assertFalse(No5AppNavigation.available(context, target))
        assertNotNull(No5AppNavigation.open(context, target, false))
        install(target, enabled = false); assertFalse(No5AppNavigation.available(context, target))
        install(target, exported = false); assertFalse(No5AppNavigation.available(context, target))
        install(target, appEnabled = false); assertFalse(No5AppNavigation.available(context, target))
        install(target); assertTrue(No5AppNavigation.available(context, target))
    }

    @Test fun removalAndDeniedLaunchRacesAreReportedWithoutFallback() {
        install(No5AppNavigation.Target.HELM)
        fun rejecting(error: RuntimeException) = object : ContextWrapper(context) {
            var attempts = 0
            override fun startActivity(intent: Intent) { attempts++; throw error }
        }
        val removed = rejecting(ActivityNotFoundException())
        assertEquals("HELM ist nicht mehr verfügbar.", No5AppNavigation.open(removed, No5AppNavigation.Target.HELM, false))
        assertEquals(1, removed.attempts)
        val denied = rejecting(SecurityException())
        assertEquals("Android erlaubt den Start von HELM nicht.", No5AppNavigation.open(denied, No5AppNavigation.Target.HELM, false))
        assertEquals(1, denied.attempts)
    }

    @Test fun systemSettingsUsesItsStandardActionAndReportsUnavailability() {
        var launched: Intent? = null
        val recording = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        assertNull(No5AppNavigation.open(recording, No5AppNavigation.Target.SETTINGS, true))
        val intent = requireNotNull(launched)
        assertEquals(Settings.ACTION_SETTINGS, intent.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertNull(intent.component); assertNull(intent.extras)
        val missing = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertNotNull(No5AppNavigation.open(missing, No5AppNavigation.Target.SETTINGS, false))
    }
}
