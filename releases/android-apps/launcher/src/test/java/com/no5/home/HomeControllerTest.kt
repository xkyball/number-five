package com.no5.home

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import com.no5.navigation.AppNavigation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class HomeControllerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun candidate(pkg: String, title: String, enabled: Boolean = true, exported: Boolean = true) = ResolveInfo().apply {
        nonLocalizedLabel = title
        activityInfo = ActivityInfo().apply {
            packageName = pkg; name = "$pkg.MainActivity"; this.enabled = enabled; this.exported = exported
            applicationInfo = ApplicationInfo().apply { packageName = pkg; this.enabled = true }
        }
    }
    @Test fun secondaryListIncludesDiagnosticsAndMeasurementWithoutMainDestinationsOrDuplicates() {
        val diagnostic = candidate("com.no5.knobdiagnostic", "Diagnose")
        val measurement = candidate("com.no5.knobmeasurement", "Messung")
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowOf(app.packageManager).addResolveInfoForIntent(query, listOf(measurement, diagnostic, diagnostic,
            candidate(AppNavigation.HOME, "Home"), candidate(AppNavigation.HELM, "Helm"), candidate(AppNavigation.MAP, "Map"),
            candidate("disabled", "Disabled", enabled = false), candidate("private", "Private", exported = false)))
        assertEquals(listOf("Diagnose", "Messung"), HomeController(app).apps().map { it.label })
    }
    @Test fun secondaryLaunchRechecksStaleEntryAndPreservesTaskAndThemeOwnership() {
        val ctl = HomeController(app)
        val item = HomeController.App("Diagnose", ComponentName("diagnostic", "diagnostic.MainActivity"))
        assertNotNull(ctl.open(item))
        val info = candidate("diagnostic", "Diagnose").activityInfo
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = info.packageName; applicationInfo = info.applicationInfo; activities = arrayOf(info)
        })
        repeat(10) {
            assertNull(ctl.open(item))
            val started = shadowOf(app).nextStartedActivity
            assertEquals(item.component, started.component)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, started.flags)
            assertFalse(started.hasExtra(AppNavigation.NIGHT))
        }
        shadowOf(app.packageManager).removePackage("diagnostic")
        assertNotNull(ctl.open(item))
    }
    @Test fun homeIntentClosesSecondaryListAndStoredNightSurvivesNewController() {
        val ctl = HomeController(app)
        ctl.acceptHome(Intent().putExtra(AppNavigation.NIGHT, true))
        ctl.showApps()
        ctl.acceptHome(Intent(Intent.ACTION_MAIN))
        assertFalse(ctl.appsVisible); assertTrue(ctl.night)
        assertTrue(HomeController(app).night)
        ctl.toggleNight(); assertFalse(HomeController(app).night)
        ctl.showApps(); ctl.back(); assertFalse(ctl.appsVisible)
    }
}
