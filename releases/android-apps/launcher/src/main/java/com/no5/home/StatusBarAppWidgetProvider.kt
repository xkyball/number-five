package com.no5.home

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Real OEM AppWidget slot candidate. Local app headers stay enabled until physical slot acceptance. */
class StatusBarAppWidgetProvider:AppWidgetProvider() {
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray) {
        update(context, manager, ids)
    }

    companion object {
        /** Activation and binding belong to the scoped OEM acceptance, not app startup. */
        fun refreshBoundWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StatusBarAppWidgetProvider::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            @Suppress("DEPRECATION") val info=context.packageManager.getPackageInfo(context.packageName,0)
            val night = context.getSharedPreferences("no5_home", Context.MODE_PRIVATE).getBoolean("night", false)
            val style = HomeStyle(night)
            val view=RemoteViews(context.packageName,R.layout.no5_statusbar_widget)
            view.setTextViewText(R.id.header_version,"HOME ${info.versionName} · ${info.longVersionCode}")
            // No app/source observation is inferred from widget delivery or elapsed time.
            view.setTextViewText(R.id.header_context,"SYSTEMUI · SLOT-TEST")
            view.setTextViewText(R.id.header_data,"App-Bereich · keine Messdaten · ${if (night) "Rotnacht" else "Tag"}")
            view.setInt(R.id.header_shell, "setBackgroundColor", style.shell)
            for (id in intArrayOf(R.id.header_brand, R.id.header_context, R.id.header_data,
                    R.id.header_version, R.id.header_bottom)) view.setTextColor(id, style.shellText)
            view.setInt(R.id.header_top_rule, "setBackgroundColor", style.accent)
            view.setInt(R.id.header_bottom_rule, "setBackgroundColor", style.accent)
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(context, HomeActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            view.setOnClickPendingIntent(R.id.header_home, PendingIntent.getActivity(context, 23, home,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            for(id in ids)manager.updateAppWidget(id,view)
        }
    }
}
