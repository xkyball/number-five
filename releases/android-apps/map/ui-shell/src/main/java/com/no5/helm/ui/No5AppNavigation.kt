package com.no5.helm.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Minimal cross-module media action; keeps the general target list private to the shell. */
fun openNo5Audio(context: Context): String? = No5AppNavigation.openAudio(context)

/** Confirmed No5 Home contract v1; components avoid the Lumina implicit resolver. */
internal object No5AppNavigation {
    const val NIGHT = "com.no5.navigation.NIGHT"
    const val OPEN_PAGE = "com.no5.navigation.OPEN_PAGE"
    const val PAGE_AUDIO = "AUDIO"
    enum class Target(val title: String, val packageName: String?, val activityName: String?) {
        HELM("HELM", "com.no5.helm", "com.no5.helm.MainActivity"),
        MAP("Karte", "com.no5.map", "com.no5.helm.MainActivity"),
        HOME("No5 Home", "com.no5.home", "com.no5.home.HomeActivity"),
        SETTINGS("Systemeinstellungen", null, null),
    }

    @Suppress("DEPRECATION")
    fun appIntent(context: Context, target: Target, night: Boolean): Intent? {
        if (target == Target.SETTINGS) return Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val component = ComponentName(requireNotNull(target.packageName), requireNotNull(target.activityName))
        val activity = try { context.packageManager.getActivityInfo(component, 0) }
            catch (_: PackageManager.NameNotFoundException) { return null }
            catch (_: SecurityException) { return null }
        if (!activity.enabled || !activity.exported || !activity.applicationInfo.enabled) return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (target == Target.HOME) putExtra(NIGHT, night)
        }
    }

    /** A media-header action requests AUDIO on the verified HELM component, retaining its task. */
    fun audioIntent(context: Context): Intent? = appIntent(context, Target.HELM, false)?.putExtra(OPEN_PAGE, PAGE_AUDIO)
    fun openAudio(context: Context): String? {
        val intent = audioIntent(context) ?: return com.no5.ui.No5Text.text(context, "%s ist nicht installiert oder nicht verfügbar.", "HELM")
        return try { context.startActivity(intent); null }
            catch (_: ActivityNotFoundException) { com.no5.ui.No5Text.text(context, "%s ist nicht mehr verfügbar.", "HELM") }
            catch (_: SecurityException) { com.no5.ui.No5Text.text(context, "Android erlaubt den Start von %s nicht.", "HELM") }
    }

    fun available(context: Context, target: Target) = appIntent(context, target, false) != null

    /** Rechecks at selection time, including removal/denial between discovery and launch. */
    fun open(context: Context, target: Target, night: Boolean): String? {
        val intent = appIntent(context, target, night) ?: return com.no5.ui.No5Text.text(context, "%s ist nicht installiert oder nicht verfügbar.", com.no5.ui.No5Text.text(context, target.title))
        return try { context.startActivity(intent); null }
            catch (_: ActivityNotFoundException) { com.no5.ui.No5Text.text(context, "%s ist nicht mehr verfügbar.", com.no5.ui.No5Text.text(context, target.title)) }
            catch (_: SecurityException) { com.no5.ui.No5Text.text(context, "Android erlaubt den Start von %s nicht.", com.no5.ui.No5Text.text(context, target.title)) }
    }
}
