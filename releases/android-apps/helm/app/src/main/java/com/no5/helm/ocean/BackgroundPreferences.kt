package com.no5.helm.ocean

import android.content.Context

/** Separate display preference: no source-session churn, connection or sharing change. */
class BackgroundPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("no5_background", Context.MODE_PRIVATE)
    fun load(): BackgroundMode = runCatching {
        BackgroundMode.valueOf(prefs.getString("mode", BackgroundMode.IMAGE.name) ?: BackgroundMode.IMAGE.name)
    }.getOrDefault(BackgroundMode.IMAGE)
    fun loadVideoUri(): String? = runCatching { prefs.getString("video_uri", null)?.takeIf { it.startsWith("content://") } }.getOrNull()
    /** Call on an IO dispatcher; only publish the selected state after successful persistence. */
    @Synchronized fun save(mode: BackgroundMode, videoUri: String? = loadVideoUri()): Boolean {
        if (videoUri != null && !videoUri.startsWith("content://")) return false
        val previousUri = loadVideoUri()
        val previous = try { prefs.getString("mode", null) }
            catch (_: ClassCastException) { null } // Explicit Save can repair a malformed value.
            catch (_: RuntimeException) { return false }
        return try {
            if (prefs.edit().putString("mode", mode.name).putString("video_uri",videoUri).commit()) true else {
                // SharedPreferences changes its process cache even when disk persistence fails.
                restore(previous, previousUri)
                false
            }
        } catch (_: RuntimeException) {
            restore(previous, previousUri)
            false
        }
    }
    private fun restore(previous: String?, previousUri: String?) {
        runCatching {
            val editor = prefs.edit()
            if (previous == null) editor.remove("mode") else editor.putString("mode", previous)
            editor.putString("video_uri",previousUri).commit()
        }
    }
}
