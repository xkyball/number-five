package com.no5.helm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.ui.No5ActionFeedback
import com.no5.ui.No5Text
import java.time.Instant

/** Approved compact detail shell; the existing body still owns its actual data and links. */
@Composable internal fun HelmInformationDialog(tag:String,title:String,action:String,night:Boolean,
    onClose:()->Unit,body:@Composable ColumnScope.()->Unit){
    com.no5.ui.No5DetailsDialog(title,night,onClose,tag="$tag-dialog",partsTag=tag,
        actions=listOf(com.no5.header.artwork.No5WorkspaceView.Action(action,key="$tag-close",action=onClose)),
        content={Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp),content=body)})
}

@Composable internal fun SettingsReviewDialog(warning:String?,night:Boolean,onClose:()->Unit){
    HelmInformationDialog("settings-review",No5Text.ui("Review app connection"),No5Text.ui("Open SETTINGS"),night,onClose){
        Text(warning?.let(::sourceSettingsWarningLabel)?:No5Text.ui("Saved source settings need review."))
    }
}

@Composable internal fun WeatherDetailsDialog(snapshot:WeatherSnapshot,now:Long,policy:FreshnessPolicy,night:Boolean,onClose:()->Unit){
    val view=WeatherPresentation.from(snapshot,now,policy)
    val uri=LocalUriHandler.current
    HelmInformationDialog("weather-details",No5Text.ui("Weather · %s",No5Text.ui(view.statusLabel)),No5Text.ui("Close"),night,onClose){
            Text(com.no5.ui.No5Text.ui("Provider: %s", view.providerLabel))
            Text(com.no5.ui.No5Text.ui("Delivery: %s", com.no5.ui.No5Text.ui(view.deliveryLabel)))
            snapshot.scene?.let { Text(com.no5.ui.No5Text.ui("SIMULATED SCENE · %s · fixed UTC · revision %d · %s", Instant.ofEpochMilli(it.utcEpochMs), it.revision, view.status)) }
            Text(com.no5.ui.No5Text.ui("True wind: %s kn from %s", view.windSpeed, view.windDirection))
            Text(com.no5.ui.No5Text.ui("Air: %s °C", view.airTemperature))
            Text(com.no5.ui.No5Text.ui("Humidity: %s %%", view.humidity))
            Text(com.no5.ui.No5Text.ui("Pressure: %s hPa", view.pressure))
            fun sceneField(value: Double?, supported: Boolean)=if(view.status==TelemetryStatus.LIVE && supported && value!=null)
                String.format(java.util.Locale.ROOT,"%.0f",value) else "—"
            Text(com.no5.ui.No5Text.ui("Cloud cover: %s %%", sceneField(snapshot.values.cloudCoverPercent,snapshot.capabilities.cloudCover)))
            Text(com.no5.ui.No5Text.ui("Visibility: %s m", sceneField(snapshot.values.visibilityMeters,snapshot.capabilities.visibility)))
            Text(com.no5.ui.No5Text.ui("WMO weather code: %s", sceneField(snapshot.values.weatherCode?.toDouble(),snapshot.capabilities.weatherCode)))
            Text(com.no5.ui.No5Text.ui("Ocean animation is illustrative, not a sea-state measurement. Missing or stale weather uses a neutral scene."))
            Text(com.no5.ui.No5Text.ui("Weather location: %s", view.observedLocation))
            Text(com.no5.ui.No5Text.ui("Observation / model valid time: %s", snapshot.observedAtEpochMs?.let { Instant.ofEpochMilli(it).toString() } ?: com.no5.ui.No5Text.ui("Unavailable")))
            Text(com.no5.ui.No5Text.ui("Retrieved: %s", snapshot.receivedAtEpochMs?.let { Instant.ofEpochMilli(it).toString() } ?: com.no5.ui.No5Text.ui("Unavailable")))
            Text(com.no5.ui.No5Text.ui("Pressure reference: %s", snapshot.pressureReference))
            snapshot.requestedLocation?.let {
                Text(com.no5.ui.No5Text.ui("Requested boat location:"))
                Text(it.latitudeDegrees.toString())
                Text(it.longitudeDegrees.toString())
            }
            if(snapshot.provider?.kind == WeatherProviderKind.FORECAST) Text(com.no5.ui.No5Text.ui("Model issue time: not supplied by the provider. The time above is model validity, not publication time."))
            else Text(com.no5.ui.No5Text.ui("Published: %s", snapshot.timestampEpochMs?.let { Instant.ofEpochMilli(it).toString() } ?: com.no5.ui.No5Text.ui("Unavailable")))
            val explanation=when {
                snapshot.simulated || snapshot.provider?.kind==WeatherProviderKind.SIMULATED -> "Synthetic weather for testing. Scene time is independent of voyage and transport clocks. This is not an onboard measurement."
                snapshot.provider?.kind==WeatherProviderKind.FORECAST -> "External weather model forecast. Wind is modeled at the provider's reference height; pressure may be reduced to mean sea level. These are not onboard sensor readings."
                snapshot.provider?.kind==WeatherProviderKind.ONBOARD -> "Measured onboard weather from the reported sensors. Source and observation time determine availability and freshness; missing or stale values are not replaced."
                else -> "Weather source is not confirmed. The reported source and time information determine which values are available."
            }
            Text(com.no5.ui.No5Text.ui(explanation))
            snapshot.error?.let { Text(it) }
            if(snapshot.provider?.id?.contains("meteo", ignoreCase=true)==true) {
                No5ActionFeedback { feedback -> TextButton(onClick={uri.openUri("https://open-meteo.com/")},
                    enabled=feedback.enabled,interactionSource=feedback.interactionSource,modifier=feedback.modifier) { Text(com.no5.ui.No5Text.ui("Weather data by Open-Meteo")) } }
                No5ActionFeedback { feedback -> TextButton(onClick={uri.openUri("https://creativecommons.org/licenses/by/4.0/")},
                    enabled=feedback.enabled,interactionSource=feedback.interactionSource,modifier=feedback.modifier) { Text(com.no5.ui.No5Text.ui("CC BY 4.0 attribution")) } }
            }

    }
}
