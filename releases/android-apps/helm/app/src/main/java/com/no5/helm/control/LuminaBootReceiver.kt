package com.no5.helm.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot does not enable a controller on its own: it resumes only a locally recorded enable. */
class LuminaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val preferences = context.getSharedPreferences(LuminaDurableController.PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(LuminaDurableController.KEY_ENABLED, false)) return
        HelmSourceService.startDurable(context.applicationContext, HelmSourceService.ACTION_RECONNECT)
    }
}
