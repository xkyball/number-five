package com.no5.helm

import com.no5.ui.No5Text
import com.no5.ui.No5ActionFeedback
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.no5.gateway.GatewaySessionStatus
import com.no5.gateway.GatewaySessionPhase

/** Only fixed UI labels are translated; the gateway reason remains a protocol value. */
internal fun luminaGatewaySessionLabel(status: GatewaySessionStatus?): String = when (status?.phase) {
    GatewaySessionPhase.READY -> No5Text.ui("Gateway · Zugang bereit")
    GatewaySessionPhase.WAITING -> No5Text.ui("Gateway · Verbindung wird erneuert · %d s", status.retryRemainingSeconds)
    GatewaySessionPhase.UNAVAILABLE -> No5Text.ui("Gateway · nicht verfügbar · %s", status.reason ?: No5Text.ui("Zugang prüfen"))
    GatewaySessionPhase.CLOSED -> No5Text.ui("Gateway · Sitzung beendet")
    null -> No5Text.ui("Lumina Control nicht verfügbar")
}

@Composable internal fun LuminaGatewayConnectionScreen(scale: Float, night: Boolean,
    status: GatewaySessionStatus?, connectionStatus: String, onSetup: () -> Unit,
    onBack: () -> Unit, onRefresh: () -> Unit) {
    BackHandler(onBack = onBack)
    var details by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    com.no5.ui.No5Workspace("Verbindung","Zugang wird in Lumina Control eingerichtet.",listOf(
        com.no5.header.artwork.No5WorkspaceView.Row("HELM / Audio / Sonar",luminaGatewaySessionLabel(status),rawDetail=true,key="gateway-session-status",action={details=true}),
        com.no5.header.artwork.No5WorkspaceView.Row("Wetter","Unabhängiger Quellenstatus",key="gateway-source-details",action={details=true}),
        com.no5.header.artwork.No5WorkspaceView.Row("In Control einrichten",key="gateway-setup",action=onSetup)
    ),night,onBack,Modifier.fillMaxSize().testTag("gateway-status-page"),showBack=false,
        actions=listOf(com.no5.header.artwork.No5WorkspaceView.Action("Zurück",key="gateway-back",action=onBack),
            com.no5.header.artwork.No5WorkspaceView.Action("Neu prüfen",key="gateway-test",action=onRefresh)))
    if(details)com.no5.ui.No5DetailsDialog("Gatewaydiagnose",night,{details=false},
        body=listOfNotNull(luminaGatewaySessionLabel(status),status?.origin,connectionStatus,
            No5Text.ui("Lumina Control richtet den Zugang einmal ein. HELM verbindet sich direkt mit dem Pi.")).joinToString("\n"),tag="gateway-status-details")
}
