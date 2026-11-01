package com.no5.helm.ui

import android.os.Build

/** Reports the runtime API separately; an OEM release string is not an API/release mapping. */
internal fun rendererDiagnosticRuntimeTitle(): String =
    rendererDiagnosticTitleForApi(Build.VERSION.SDK_INT)

internal fun rendererDiagnosticTitleForApi(runtimeSdkInt: Int): String {
    val api = runtimeSdkInt.takeIf { it > 0 }?.toString() ?: "—"
    return "Renderer · ARM64 · Android API $api"
}
