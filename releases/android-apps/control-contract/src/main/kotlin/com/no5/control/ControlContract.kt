package com.no5.control

import android.content.ComponentName
import android.os.SystemClock

object SystemControlContract {
    const val PROTOCOL = 3
    const val PERMISSION = "com.no5.helm.permission.SYSTEM_CONTROL"
    const val HELM_PACKAGE = "com.no5.helm"
    val SERVICE = ComponentName(HELM_PACKAGE, "com.no5.helm.control.HelmSourceService")
    val CLIENT_PACKAGES: Set<String> = setOf("com.no5.home", "com.no5.map", HELM_PACKAGE)
    internal const val USER_UID_RANGE = 100_000
    internal const val MAX_PARCEL_BYTES = 2048
    internal const val SERVICE_DESCRIPTOR = "com.no5.control.SystemControl.v2"
    internal const val CALLBACK_DESCRIPTOR = "com.no5.control.SystemControlCallback.v2"
    internal const val REGISTER = 1
    internal const val STATE = 2
    internal const val DEREGISTER = 3
    internal const val CALLBACK_STATE = 1
    internal const val CALLBACK_INPUT = 2
}

/** Names match the Android-independent router; wire codes are explicit, not ordinals. */
enum class NormalizedKnobInput(val wireCode: Int) {
    LEFT_CW(1), LEFT_CCW(2), RIGHT_CW(3), RIGHT_CCW(4),
    LEFT_PUSH(5), RIGHT_PUSH(6), LEFT_LONG_PUSH(7), RIGHT_LONG_PUSH(8);

    companion object {
        internal fun fromWire(code: Int): NormalizedKnobInput =
            values().singleOrNull { it.wireCode == code }
                ?: throw IllegalArgumentException("Unknown normalized input")
    }
}

enum class ClientDisposition(val wireCode: Int) {
    REFUSED(0), HANDLED(1), AUDIO_DEFAULT(2);

    companion object {
        internal fun fromWire(code: Int): ClientDisposition =
            values().singleOrNull { it.wireCode == code }
                ?: throw IllegalArgumentException("Unknown client disposition")
    }
}

data class KnobEnvelope(
    val protocol: Int = SystemControlContract.PROTOCOL,
    val controllerSession: String,
    val sequence: Long,
    val foregroundEpoch: Long,
    val clientEpoch: Long,
    val bindingsEpoch: Long,
    val input: NormalizedKnobInput,
    val issuedAtUptimeMs: Long = SystemClock.uptimeMillis(),
    val expiresAtUptimeMs: Long = issuedAtUptimeMs + 250,
) {
    init {
        require(protocol == SystemControlContract.PROTOCOL)
        requireSession(controllerSession)
        require(sequence > 0 && foregroundEpoch > 0 && clientEpoch > 0 && bindingsEpoch > 0)
        require(issuedAtUptimeMs >= 0 && expiresAtUptimeMs > issuedAtUptimeMs &&
            expiresAtUptimeMs - issuedAtUptimeMs <= 250)
    }
}

/** Supplied in-process by Root's OS observer. There is no Binder transaction to set it. */
data class ObservedForeground(
    val packageName: String?,
    val epoch: Long,
    val resumed: Boolean,
    val userId: Int,
    val displayId: Int = 0,
) {
    init {
        require(epoch >= 0 && userId >= 0 && displayId >= 0)
        require(packageName == null || packageName.length in 1..128)
    }
}

data class ReadyClient(
    val packageName: String,
    val uid: Int,
    val controllerSession: String,
    val clientEpoch: Long,
    val bindingsEpoch: Long,
    val foregroundEpoch: Long,
)

internal data class ClientState(val bindingsEpoch: Long, val resumed: Boolean, val focused: Boolean,
                                val claimedInputs: Set<NormalizedKnobInput> = emptySet()) {
    init { require(bindingsEpoch > 0); require(claimedInputs.size <= NormalizedKnobInput.values().size) }
}

internal data class ClientAdmission(
    val controllerSession: String,
    val clientEpoch: Long,
    val bindingsEpoch: Long,
    val foregroundEpoch: Long,
    val revision: Long,
    val ready: Boolean,
) {
    init {
        requireSession(controllerSession)
        require(clientEpoch > 0 && bindingsEpoch > 0 && revision > 0 && foregroundEpoch >= 0)
        require(!ready || foregroundEpoch > 0)
    }
}

internal fun requireSession(value: String) {
    require(value.length in 1..80 && value.all { it.isLetterOrDigit() && it.code < 128 || it in "._:-" })
}
