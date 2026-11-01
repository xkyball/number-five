package com.no5.navigation

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Permission-free navigation contract kept locally so the launcher builds alone. */
object AppNavigation {
    const val HOME = "com.no5.home"
    const val HELM = "com.no5.helm"
    const val MAP = "com.no5.map"
    const val NIGHT = "com.no5.navigation.NIGHT"
    const val OPEN_PAGE = "com.no5.navigation.OPEN_PAGE"
    const val PAGE_AUDIO = "AUDIO"
    data class HomeChoice(val label: String, val component: ComponentName)

    fun component(packageName: String): ComponentName? = when (packageName) {
        HOME -> ComponentName(HOME, "com.no5.home.HomeActivity")
        HELM -> ComponentName(HELM, "com.no5.helm.MainActivity")
        MAP -> ComponentName(MAP, "com.no5.helm.MainActivity")
        else -> null
    }

    @Suppress("DEPRECATION")
    fun appIntent(context: Context, packageName: String, night: Boolean): Intent? {
        val target = component(packageName) ?: return null
        val activity = try { context.packageManager.getActivityInfo(target, 0) }
        catch (_: PackageManager.NameNotFoundException) { return null }
        if (!activity.enabled || !activity.exported || !activity.applicationInfo.enabled) return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(target).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            if (packageName == HOME) putExtra(NIGHT, night)
        }
    }

    /** An explicit request overrides the retained page without clearing its task/session. */
    fun audioIntent(context: Context): Intent? = appIntent(context, HELM, false)?.putExtra(OPEN_PAGE, PAGE_AUDIO)
    fun openAudio(context: Context): String? = launch(context, audioIntent(context))

    fun installed(context: Context, packageName: String) = appIntent(context, packageName, false) != null
    fun openApp(context: Context, packageName: String, night: Boolean): String? = launch(context, appIntent(context, packageName, night))
    fun openSettings(context: Context): String? = launch(context, Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    @Suppress("DEPRECATION")
    fun otherHomes(context: Context): List<HomeChoice> = context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        .mapNotNull { result ->
            val activity = result.activityInfo ?: return@mapNotNull null
            if (!activity.enabled || !activity.exported || activity.packageName in setOf(HOME, HELM, MAP)) null
            else HomeChoice(result.loadLabel(context.packageManager).toString(), ComponentName(activity.packageName, activity.name))
        }.distinctBy { it.component }.sortedBy { it.label }

    fun openOtherHome(context: Context, selected: HomeChoice): String? {
        if (otherHomes(context).none { it.component == selected.component }) return com.no5.ui.No5Text.text(context, "Startbildschirm nicht mehr verfügbar.")
        return launch(context, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setComponent(selected.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    private fun launch(context: Context, intent: Intent?): String? {
        if (intent == null) return com.no5.ui.No5Text.text(context, "App nicht installiert oder keine Startaktivität verfügbar.")
        return try { context.startActivity(intent); null }
        catch (_: ActivityNotFoundException) { com.no5.ui.No5Text.text(context, "App nicht mehr verfügbar.") }
        catch (_: SecurityException) { com.no5.ui.No5Text.text(context, "Android erlaubt diesen App-Start nicht.") }
    }
}
