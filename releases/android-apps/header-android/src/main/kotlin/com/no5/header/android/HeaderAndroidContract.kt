package com.no5.header.android

import android.os.SystemClock
import com.no5.header.*

object HeaderAndroidContract {
    const val BIND_ACTION = "com.no5.header.BIND_V1"
    const val PROTOCOL = 1
    const val MAX_PARCEL_BYTES = 8192
    internal const val DESCRIPTOR = "com.no5.header.NativeHeader.v1"
    internal const val CALLBACK_DESCRIPTOR = "com.no5.header.NativeHeaderCallback.v1"
    internal const val REGISTER = 1
    internal const val STATE = 2
    internal const val PUBLISH = 3
    internal const val DEREGISTER = 4
    internal const val ADMISSION = 5
    internal const val CALLBACK_STATE = 1
}

object AndroidHeaderClock : HeaderClock {
    override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
    override fun epochMs() = System.currentTimeMillis()
}

/** Immutable server-issued grant. Capture at the source callback's origin, never retag late data. */
data class HeaderAdmission(
    val controllerSession: String,
    val producerSession: String,
    val processGeneration: Long,
    val scopeToken: String,
    val foregroundEpoch: Long,
    val pageId: HeaderPage,
    val pageGeneration: Long,
    val sourceSessionId: String?,
    val revision: Long,
    val ready: Boolean,
) {
    init {
        requireHeaderId(controllerSession); requireHeaderId(producerSession); requireHeaderId(scopeToken)
        require(processGeneration > 0 && foregroundEpoch >= 0 && pageGeneration > 0 && revision > 0)
        require(sourceSessionId == null || sourceSessionId.isNotBlank() && sourceSessionId.length <= 256)
        require(!ready || foregroundEpoch > 0)
    }
}

internal data class HeaderPageState(val pageId: HeaderPage, val pageGeneration: Long,
    val sourceSessionId: String?, val resumed: Boolean, val focused: Boolean) {
    init {
        require(pageGeneration > 0)
        require(sourceSessionId == null || sourceSessionId.isNotBlank() && sourceSessionId.length <= 256)
    }
}

internal fun requireHeaderId(value: String) {
    require(value.length in 1..80 && value.all { it.code < 128 && (it.isLetterOrDigit() || it in "._:-") })
}
