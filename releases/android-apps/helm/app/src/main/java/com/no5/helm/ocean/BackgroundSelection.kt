package com.no5.helm.ocean

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Display-only transaction state. No endpoint, consent, or source-session reference is accepted. */
class BackgroundSelection(initial: BackgroundMode, initialVideoUri: String? = null) {
    data class Save internal constructor(val mode: BackgroundMode, val videoUri: String?, internal val serial: Long)
    var activeVideoUri by mutableStateOf(initialVideoUri); private set
    var draftVideoUri by mutableStateOf(initialVideoUri); private set
    fun selectVideo(uri: String) {
        if (saving || !uri.startsWith("content://")) return
        draftVideoUri=uri; draft=BackgroundMode.VIDEO; error=null
    }
    var active by mutableStateOf(initial); private set
    var draft by mutableStateOf(initial); private set
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var serial = 0L
    private var pending: Save? = null
    val dirty: Boolean get() = draft != active || draftVideoUri != activeVideoUri
    fun select(mode: BackgroundMode) {
        if (saving) return
        draft = mode
        error = null
    }
    fun cancel() {
        if (saving) return // An explicit Save already in progress cannot be cancelled halfway.
        draftVideoUri = activeVideoUri
        draft = active
        error = null
    }
    fun beginSave(): Save? {
        if (saving || !dirty) return null
        val request = Save(draft, draftVideoUri, ++serial)
        pending = request
        saving = true
        error = null
        return request
    }
    fun finishSave(request: Save, successful: Boolean) {
        if (pending != request) return
        if (successful) { active = request.mode; activeVideoUri = request.videoUri }
        else error = "Background could not be saved. The previous background remains active."
        saving = false
        pending = null
    }
}
