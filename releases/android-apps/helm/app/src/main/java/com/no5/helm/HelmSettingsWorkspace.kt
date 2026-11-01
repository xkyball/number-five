package com.no5.helm

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.no5.domain.*
import com.no5.header.artwork.No5WorkspaceView
import com.no5.helm.ocean.*
import com.no5.ui.*

/** D03 presentation only. Draft, persistence and navigation decisions remain with SETTINGS. */
@Composable internal fun HelmSettingsWorkspace(page:String,night:Boolean,units:DisplayUnits,weather:WeatherProviderMode,
    consent:Boolean,onUnits:(DisplayUnits)->Unit,onWeather:(WeatherProviderMode)->Unit,onConsent:(Boolean)->Unit,
    background:BackgroundSelection?,backgroundDescription:String,videoDescription:String,
    onVideo:()->Unit,onRetry:()->Unit,onGateway:()->Unit,onPage:(String)->Unit,onBack:()->Unit,
    onSave:()->Unit,onSaveBackground:()->Unit,canSave:Boolean,error:String?,modifier:Modifier=Modifier) {
    var choice by rememberSaveable{mutableStateOf<String?>(null)}
    var info by rememberSaveable{mutableStateOf<String?>(null)}
    val uri=androidx.compose.ui.platform.LocalUriHandler.current
    val rows=buildList{
        when(page){
            "DISPLAY"->{
                add(No5WorkspaceView.Row("Geschwindigkeit",value=units.speed.symbol,rawValue=true,key="settings-speed-dropdown",action={choice="speed"}))
                add(No5WorkspaceView.Row("Tiefe",value=units.depth.symbol,rawValue=true,key="settings-depth-dropdown",action={choice="depth"}))
                add(No5WorkspaceView.Row("Entfernung",value=units.speed.distanceSymbol,rawValue=true,key="settings-distance-dropdown",action={choice="speed"}))
                add(No5WorkspaceView.Row("Consumption",value=units.consumption.symbol,rawValue=true,key="settings-consumption-dropdown",action={choice="consumption"}))
            }
            "WEATHER"->{
                add(No5WorkspaceView.Row("Anbieter",value=when(weather){WeatherProviderMode.OPEN_METEO->"Open-Meteo";WeatherProviderMode.SIMULATED->"Gateway weather";WeatherProviderMode.DISABLED->"Weather off"},key="settings-weather-dropdown",action={choice="weather"}))
                add(No5WorkspaceView.Row("Bootsposition übermitteln","Für standortbezogene Vorhersage",value=if(consent)"An"else"Aus",selected=consent,
                    enabled=weather==WeatherProviderMode.OPEN_METEO,key="weather-location-consent",control="toggle",action={onConsent(!consent)}))
                add(No5WorkspaceView.Row("Datenschutz und Herkunft",key="weather-privacy",action={info="privacy"}))
            }
            "BACKGROUND"->{
                add(No5WorkspaceView.Row("Darstellung",value=background?.draft?.label?:"Nicht verfügbar",enabled=background!=null&&background.saving==false,
                    key="background-mode",action={choice="background"}))
                add(No5WorkspaceView.Row("Animation","Nur wenn technisch verfügbar",enabled=background!=null&&background.saving==false,key="background-animation",action={background?.select(BackgroundMode.ANIMATION)}))
                add(No5WorkspaceView.Row("Eigenes Video","Lokales Dokument wählen",enabled=background!=null&&background.saving==false,key="video-choose",action=onVideo))
                if(background?.draft==BackgroundMode.VIDEO)add(No5WorkspaceView.Row("Retry video",key="video-retry",action=onRetry))
                if(backgroundDescription.isNotBlank())add(No5WorkspaceView.Row("Status",backgroundDescription,rawDetail=true,key="background-status",action={info="background"}))
                if(background?.draft==BackgroundMode.VIDEO&&videoDescription.isNotBlank())add(No5WorkspaceView.Row("Video",No5Text.ui(videoDescription),rawDetail=true,key="video-status",action={info="video"}))
            }
            else->{
                add(No5WorkspaceView.Row("Anzeige","Tag/Nacht und Hintergrund",key="open-background-settings",action={onPage("BACKGROUND")}))
                add(No5WorkspaceView.Row("Einheiten","Lokale Anzeigeeinheiten",key="open-display-settings",action={onPage("DISPLAY")}))
                add(No5WorkspaceView.Row("Verbindung","Gemeinsamer Gateway-Zugang über Control",key="open-gateway-connection",action=onGateway))
                add(No5WorkspaceView.Row("System und Diagnose","Version, WLAN und Quellenstatus",key="open-weather-settings",action={onPage("WEATHER")}))
            }
        }
        error?.let{add(No5WorkspaceView.Row("Status",it,rawDetail=true,key="source-settings-warning",action={info="error"}))}
    }
    val actions=if(page=="MAIN")emptyList()else listOf(No5WorkspaceView.Action("Zurück",enabled=background?.saving!=true,key="settings-detail-back",action=onBack),
        No5WorkspaceView.Action(if(page=="BACKGROUND"&&background?.saving==true)"Saving…"else"Speichern",
            enabled=if(page=="BACKGROUND")background?.let{it.dirty&&!it.saving}==true else canSave,
            key=if(page=="BACKGROUND")"background-save"else"source-settings-apply",action=if(page=="BACKGROUND")onSaveBackground else onSave))
    val title=when(page){"DISPLAY"->"Anzeigeeinheiten";"WEATHER"->"Wetterquelle";"BACKGROUND"->"Hintergrund";else->"Einstellungen"}
    No5Workspace(title,when(page){"DISPLAY"->"Lokale Anzeigeeinheiten";"WEATHER"->"Provider and privacy controls for this app.";"BACKGROUND"->"Visual mode is saved separately from the gateway connection.";else->"Short overview · details open on their own page."},
        rows,night,onBack,modifier.testTag("source-settings-body"),showBack=false,actions=actions)
    choice?.let{kind->
        val options=when(kind){
            "speed"->SpeedUnit.entries.map{unit->No5WorkspaceView.Row(when(unit){SpeedUnit.KNOTS->"Knoten · kn";SpeedUnit.KMH->"Kilometer pro Stunde · km/h";SpeedUnit.MPH->"Meilen pro Stunde · mph"},
                selected=unit==units.speed,key="settings-speed-${unit.name}",control="check",action={onUnits(units.copy(speed=unit))})}
            "depth"->DepthUnit.entries.map{unit->No5WorkspaceView.Row(unit.symbol,rawLabel=true,selected=unit==units.depth,key="settings-depth-${unit.name}",control="check",action={onUnits(units.copy(depth=unit))})}
            "consumption"->ConsumptionUnit.entries.map{unit->No5WorkspaceView.Row(unit.symbol,rawLabel=true,selected=unit==units.consumption,key="settings-consumption-${unit.name}",control="check",action={onUnits(units.copy(consumption=unit))})}
            "weather"->WeatherProviderMode.entries.map{mode->No5WorkspaceView.Row(when(mode){WeatherProviderMode.OPEN_METEO->"Open-Meteo";WeatherProviderMode.SIMULATED->"Gateway weather";WeatherProviderMode.DISABLED->"Weather off"},
                selected=mode==weather,key="settings-weather-${mode.name}",control="check",action={onWeather(mode)})}
            else->BackgroundMode.entries.map{mode->No5WorkspaceView.Row(mode.label,selected=mode==background?.draft,enabled=background?.saving==false,key="background-${mode.name}",control="check",action={background?.select(mode)})}
        }
        No5DetailsDialog(when(kind){"speed","depth","consumption"->"Einheit wählen";"weather"->"Wetterquelle";else->"Hintergrund"},night,{choice=null},rows=options,tag="settings-choice",
            actions=listOf(No5WorkspaceView.Action("Schließen",key="settings-choice-close",action={choice=null})))
    }
    info?.let{kind->
        if(kind=="privacy")No5DetailsDialog("Datenschutz und Herkunft",night,{info=null},
            body=No5Text.ui("Allow current boat-source latitude/longitude to be sent to Open-Meteo. Coordinates may remain in service logs for up to 90 days. No phone location permission is used."),
            rows=listOf(No5WorkspaceView.Row("Weather data",key="weather-data-link",action={uri.openUri("https://open-meteo.com/")}),
                No5WorkspaceView.Row("Terms / privacy",key="weather-privacy-link",action={uri.openUri("https://open-meteo.com/en/terms")})),tag="settings-privacy")
        else No5DetailsDialog("Status",night,{info=null},body=when(kind){"background"->backgroundDescription;"video"->No5Text.ui(videoDescription);else->error},tag="settings-status")
    }
}
