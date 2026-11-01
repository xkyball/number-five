package com.no5.helm.ocean

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity-scoped transaction owner: explicit Save survives page changes and configuration recreation. */
class BackgroundSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = BackgroundPreferences(application)
    val selection = BackgroundSelection(store.load(),store.loadVideoUri())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun save() {
        val request = selection.beginSave() ?: return
        scope.launch {
            val successful = withContext(Dispatchers.IO) {
                try { store.save(request.mode, request.videoUri) } catch (_: RuntimeException) { false }
            }
            selection.finishSave(request, successful)
        }
    }
    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
