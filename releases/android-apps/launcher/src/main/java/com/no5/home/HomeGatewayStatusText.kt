package com.no5.home

import android.content.Context
import com.no5.gateway.GatewaySessionPhase
import com.no5.gateway.GatewaySessionStatus
import com.no5.ui.No5Text

/** UI projection only. Phase labels are localized; numeric countdowns and protocol reasons remain values. */
internal fun homeGatewaySessionLabel(context: Context, status: GatewaySessionStatus): String = when (status.phase) {
    GatewaySessionPhase.READY -> No5Text.text(context, "Gateway · Zugang bereit")
    GatewaySessionPhase.WAITING -> No5Text.text(context, "Gateway · Verbindung wird erneuert · %d s", status.retryRemainingSeconds)
    GatewaySessionPhase.UNAVAILABLE -> No5Text.text(context, "Gateway · nicht verfügbar · %s", status.reason ?: No5Text.text(context, "Zugang prüfen"))
    GatewaySessionPhase.CLOSED -> No5Text.text(context, "Gateway · Sitzung beendet")
}
