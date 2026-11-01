package com.no5.helm

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.*
import com.no5.ui.No5Text
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HelmReachableDialogBodyMatrixTest{
    @get:Rule val ui=createComposeRule()
    @Test fun everyRealInformationDialogBodyFitsAtNormalFont()=matrix(1f)
    @Test fun everyRealInformationDialogBodyFitsAtLargeFont()=matrix(1.25f)
    private fun matrix(font:Float){
        val h=HelmBodyMatrixSupport(ui,"dialogs")
        val now=1_800_000_000_000L
        val cases=listOf("weather-forecast","weather-onboard","weather-simulated","weather-unavailable","settings-warning","settings-long-error")
        var closes=0
        h.run(font,cases,after={case,frame->
            val tag=if(case.startsWith("weather"))"weather-details"else"settings-review"
            if(case.startsWith("weather")){
                val onboard="Measured onboard weather from the reported sensors. Source and observation time determine availability and freshness; missing or stale values are not replaced."
                val forecast="External weather model forecast. Wind is modeled at the provider's reference height; pressure may be reduced to mean sea level. These are not onboard sensor readings."
                val unknown="Weather source is not confirmed. The reported source and time information determine which values are available."
                val expected=when(case){"weather-onboard"->onboard;"weather-forecast"->forecast;"weather-unavailable"->unknown;else->"Synthetic weather for testing. Scene time is independent of voyage and transport clocks. This is not an onboard measurement."}
                ui.onNodeWithText(No5Text.text(frame.language,expected)).assertExists()
                if(case!="weather-onboard")ui.onNodeWithText(No5Text.text(frame.language,onboard)).assertDoesNotExist()
                if(case!="weather-forecast")ui.onNodeWithText(No5Text.text(frame.language,forecast)).assertDoesNotExist()
            }
            h.stableBody("$tag-body","$tag-title","$tag-actions")
            h.inspect("${frame.language}/${frame.night}/$font/$case/scrolled",font)
            val action=ui.onNodeWithTag("$tag-close").assertIsDisplayed().assertIsEnabled()
            val bounds=action.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height>=96f)
        }){frame->
            if(frame.case.startsWith("weather")){
                val kind=when(frame.case){"weather-onboard"->WeatherProviderKind.ONBOARD;"weather-simulated"->WeatherProviderKind.SIMULATED;else->WeatherProviderKind.FORECAST}
                val snapshot=if(frame.case=="weather-unavailable")WeatherSnapshot.unavailable(No5Text.ui("Saved source settings need review."))else WeatherSnapshot(
                    status=TelemetryStatus.LIVE,provider=WeatherProvider(if(kind==WeatherProviderKind.FORECAST)"open-meteo"else"fixture","OBSERVED_provider",kind),
                    delivery=WeatherDelivery("OBSERVED_gateway","HTTPS"),capabilities=WeatherCapabilities(true,true,true,true,true,true,true,true),
                    values=WeatherValues(16.0,8.2,275.0,65.0,1013.0,-33.9,18.4,12.0,45.0,15000.0,2),timestampEpochMs=now,observedAtEpochMs=now,receivedAtEpochMs=now,
                    simulated=kind==WeatherProviderKind.SIMULATED,requestedLocation=WeatherLocation(-33.9,18.4),scene=if(kind==WeatherProviderKind.SIMULATED)WeatherScene(now,5,WeatherLocation(-33.9,18.4))else null)
                WeatherDetailsDialog(snapshot,now,FreshnessPolicy(),frame.night){closes++}
            }else SettingsReviewDialog(if(frame.case=="settings-warning")null else "HTTP_503 · "+No5Text.ui("Saved source settings need review.").repeat(30),frame.night){closes++}
        }
        assertEquals(0,closes)
    }
}
