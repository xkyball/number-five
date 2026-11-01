package com.no5.home

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import com.no5.navigation.AppNavigation
import java.text.Collator

/** Launcher-only state and secondary app discovery. Does not change preferred HOME. */
internal class HomeController(private val context: Context) {
    private val preferences = context.getSharedPreferences("no5_home", Context.MODE_PRIVATE)
    var night = preferences.getBoolean("night", false)
        private set
    var appsVisible = false
        private set
    private val nameOrder = Collator.getInstance().apply { strength = Collator.PRIMARY }
    data class App(val label: String, val component: ComponentName)

    fun acceptHome(intent: Intent) {
        appsVisible = false
        if (intent.hasExtra(AppNavigation.NIGHT)) setNight(intent.getBooleanExtra(AppNavigation.NIGHT, night))
    }
    private fun setNight(value: Boolean) {
        night = value
        preferences.edit().putBoolean("night", value).apply()
    }
    fun toggleNight() = setNight(!night)
    fun showApps() { appsVisible = true }
    fun back() { appsVisible = false }

    @Suppress("DEPRECATION")
    fun apps(): List<App> = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    ).mapNotNull { result ->
        val info = result.activityInfo ?: return@mapNotNull null
        if (!info.enabled || !info.exported || !info.applicationInfo.enabled ||
            info.packageName in setOf(AppNavigation.HOME, AppNavigation.HELM, AppNavigation.MAP)) null
        else App(result.loadLabel(context.packageManager).toString(), ComponentName(info.packageName, info.name))
    }.distinctBy { it.component }.sortedWith { a, b ->
        val names = nameOrder.compare(a.label, b.label)
        if (names != 0) names else a.component.flattenToString().compareTo(b.component.flattenToString())
    }

    fun icon(app: App): Drawable {
        val pm = context.packageManager
        // Prefer the launch activity's icon (including activity aliases), then the application icon.
        fun load(get: () -> Drawable): Drawable? = try { get() }
            catch (_: PackageManager.NameNotFoundException) { null }
            catch (_: Resources.NotFoundException) { null }
            catch (_: SecurityException) { null }
        return load { pm.getActivityIcon(app.component) }
            ?: load { pm.getApplicationIcon(app.component.packageName) }
            ?: pm.defaultActivityIcon
    }

    @Suppress("DEPRECATION")
    fun open(app: App): String? {
        // Recheck exported/enabled state immediately before every launch, including stale rows.
        val info = try { context.packageManager.getActivityInfo(app.component, 0) }
            catch (_: PackageManager.NameNotFoundException) { return "App nicht mehr verfügbar." }
        if (!info.enabled || !info.exported || !info.applicationInfo.enabled) return "App nicht verfügbar."
        return try {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            null
        } catch (_: ActivityNotFoundException) { "App nicht mehr verfügbar." }
        catch (_: SecurityException) { "Android erlaubt diesen App-Start nicht." }
    }
}
