package com.no5.helm.control

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.provider.Settings

/** Shell/root-only operator bridge.  It cannot accept arbitrary caller packages or app leases. */
class LuminaControlProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = Binder.getCallingUid()
        if (caller != 0 && caller != 2000) throw SecurityException("Lumina control requires ADB shell/root")
        val context = checkNotNull(context)
        val result = Bundle()
        // The shell/root caller is allowed to invoke this operator bridge, but it
        // must not lend its Binder identity to AppOps.  Overlay state belongs to
        // HELM, and Android must also attribute the Settings activity to HELM.
        val identity = Binder.clearCallingIdentity()
        try {
            when (method) {
                "enable" -> {
                    if (!Settings.canDrawOverlays(context)) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        result.putString("status", "overlay_permission_required")
                        result.putString("settings", Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    } else {
                        HelmSourceService.startDurable(context, HelmSourceService.ACTION_ENABLE)
                        result.putString("status", "enable_requested")
                    }
                }
                "disable" -> { HelmSourceService.startDurable(context, HelmSourceService.ACTION_DISABLE); result.putString("status", "disable_requested") }
                "hide-header" -> { HelmSourceService.startDurable(context, HelmSourceService.ACTION_HIDE_HEADER); result.putString("status", "header_hide_requested") }
                "show-header" -> { HelmSourceService.startDurable(context, HelmSourceService.ACTION_SHOW_HEADER); result.putString("status", "header_show_requested") }
                "status" -> result.putString("status", HelmSourceService.durableStatus(context))
                "matrix" -> result.putStringArrayList("rows", ArrayList(HelmSourceService.durableMatrix(context)))
                else -> throw IllegalArgumentException("Unsupported Lumina operation")
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return result
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
