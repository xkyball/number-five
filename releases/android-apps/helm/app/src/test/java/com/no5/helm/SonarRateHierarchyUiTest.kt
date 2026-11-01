package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.helm.numeric.NativeNumericText
import com.no5.helm.numeric.NumericSlots
import com.no5.ui.UiLanguage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Synthetic native-host UI evidence; no hardware rate or physical display acceptance is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="de-rDE-w1000dp-h600dp-land-mdpi",shadows=[NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarRateHierarchyUiTest {
    @get:Rule val ui=createComposeRule()
    private var now=1_000_000L
    private val source=ObservedSource()
    private val controller=SonarNativeController(source){now}
    @After fun dispose(){controller.dispose();com.no5.header.artwork.No5WorkspaceDiagnostics.observe=null;UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
    private fun publication(selection:SonarPingSource,generation:Long,reception:Boolean=false) {
        source.connect(selection,generation)
        // Switching time basis starts the existing window at the first reception.
        // Twenty further 250-ms intervals then prove a complete 5-s fallback window.
        repeat(if(reception)21 else 20) {id->
            now+=250
            if(reception) controller.model.frame(SonarFrame(ShortArray(1024),id.toLong(),now),now,
                measurementTimeVerified=false,pingSource=if(selection==SonarPingSource.ALL)SonarPingSource.OWN else selection)
            else source.packets.emit(SonarSourceFrame(source.identity,1,SonarFrame(ShortArray(1024),id.toLong(),now),
                serverSessionId="synthetic-rate-host",displaySelection=SonarDisplaySelection(selection,generation),
                pingSource=if(selection==SonarPingSource.ALL)SonarPingSource.OWN else selection))
        }
        controller.refresh()
        source.detail.emit(source.diagnostic(now,selection,generation))
    }

    @Test fun selectedPublishedRateIsPrimaryAndOnlyMatchingUsableDepthIsSecondaryInEveryLanguageAndTheme() {
        var language by mutableStateOf("de");var night by mutableStateOf(false)
        var capturedView:android.view.View?=null
        ui.setContent {capturedView=LocalView.current;key(language){Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,night,controller)}}}
        val output=File(System.getProperty("no5.renderOutputDir"),"selected-rate-hierarchy").apply{mkdirs()}
        var generation=0L
        for(lang in listOf("de","en","af"))for(isNight in listOf(false,true))for(selection in SonarPingSource.entries) {
            ui.runOnIdle {
                UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),lang);language=lang;night=isNight
                publication(selection,++generation)
            }
            val sourceName=when(selection){SonarPingSource.OWN->mapOf("de" to "Eigene","en" to "Own","af" to "Eie").getValue(lang);SonarPingSource.NATIVE->"Venterior";SonarPingSource.ALL->mapOf("de" to "Alle","en" to "All","af" to "Alles").getValue(lang)}
            val secondaryTitle=mapOf("de" to "Brauchbare Tiefe · Auswahl","en" to "Usable depth · selected","af" to "Bruikbare diepte · gekies").getValue(lang)
            val secondary=if(selection==SonarPingSource.OWN)"6.0\u00a0Hz" else "— · "+mapOf("de" to "Unbestätigt","en" to "Unverified","af" to "Onbevestig").getValue(lang)
            fun assertRates() {
                if(controller.drawer!=null){
                    ui.runOnIdle{
                        val workspaces=sonarWorkspaceViews().filter{it.tag=="no5-workspace"&&it.isShown}
                        assertEquals("Only one live Sonar workspace",1,workspaces.size)
                        val workspace=workspaces.single()
                        assertTrue(workspace.isShown)
                        val caption=workspace.contentDescription.toString()
                        assertTrue(caption,caption.contains("Pings: 4.0\u00a0Hz"));assertTrue(caption,caption.contains(sourceName))
                        assertTrue(caption,caption.contains(com.no5.ui.No5Text.ui("Brauchbar: %s",secondary)))
                        val fits=mutableListOf<com.no5.header.artwork.No5WorkspaceTextFit>()
                        com.no5.header.artwork.No5WorkspaceDiagnostics.observe={fits+=it}
                        val view=checkNotNull(capturedView);val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                        try{view.draw(Canvas(bitmap))}finally{bitmap.recycle();com.no5.header.artwork.No5WorkspaceDiagnostics.observe=null}
                        val actual=fits.filter{it.dynamic&&it.text.contains("Pings: ")}
                        assertTrue("No actual caption drawn",actual.isNotEmpty())
                        actual.forEach{assertEquals("Actual live caption may not hide either rate",it.text,it.drawn);assertTrue("Rate caption overflow: $it",it.width<=it.available+.01f)}
                    }
                    return
                }
                val main=ui.onNodeWithTag("Sonar main published rate").assertIsDisplayed().assertContentDescriptionEquals("Pings: 4.0\u00a0Hz · $sourceName").fetchSemanticsNode().boundsInRoot
                val usable=ui.onNodeWithTag("Sonar selected usable depth rate").assertIsDisplayed().assertContentDescriptionEquals("$secondaryTitle: $secondary · $sourceName").fetchSemanticsNode().boundsInRoot
                assertEquals(65f,main.top,0f);assertEquals(87f,main.bottom,0f)
                assertEquals(main.top,usable.top,0f);assertEquals(main.bottom,usable.bottom,0f)
                assertTrue("Independent rate fields overlap",main.right<usable.left)
                assertTrue("Rates invade measured-depth field",usable.right<770f)
            }
            assertRates()
            for(tab in SonarDrawerTab.entries) {
                ui.runOnIdle {controller.open(tab)}
                assertRates()
            }
            ui.runOnIdle {
                controller.close()
                assertEquals(4.0,controller.pingRate.hz!!,0.0)
                // The host counter remains explicitly OWN even when another publication is selected.
                assertEquals(6.0,controller.usableRate.hz!!,0.0)
                assertEquals("4.0\u00a0Hz",controller.rateLabel())
                val context=RuntimeEnvironment.getApplication()
                val caption=Paint().apply{typeface=context.resources.getFont(R.font.barlow_condensed_semibold);textSize=18f}
                assertTrue(caption.measureText(controller.rateTitle())<=135f)
                val value=Paint().apply{typeface=context.resources.getFont(R.font.barlow_condensed_extrabold);textSize=24f}
                assertTrue(NativeNumericText.layout(controller.rateLabel(),value,NumericSlots(4,1)).width<=250f)
                value.textSize=22f
                assertTrue(NativeNumericText.layout(secondary,value,NumericSlots(4,1)).width<=215f)
                val view=checkNotNull(capturedView)
                val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                File(output,"$lang-${if(isNight)"night" else "day"}-${selection.name.lowercase()}.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
                image.recycle()
            }
        }
    }

    @Test fun frozenImageDoesNotFreezeMainRateAndReceptionFallbackIsExplicit() {
        controller.screenVisible(true);publication(SonarPingSource.OWN,1)
        controller.freeze();val frozenId=controller.model.viewRing.pingIdAt(0)
        ui.setContent {Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,false,controller)}}
        ui.onNodeWithTag("Sonar main published rate").assertContentDescriptionEquals("Pings: 4.0\u00a0Hz · Eigene")
        ui.runOnIdle {
            now+=5001
            source.detail.emit(source.diagnostic(now,SonarPingSource.OWN,1,count=0))
            controller.tick()
            assertTrue(controller.snapshot.display.freeze)
            assertEquals(frozenId,controller.model.viewRing.pingIdAt(0))
            assertEquals(20,controller.model.viewRing.count)
        }
        ui.onNodeWithTag("Sonar main published rate").assertContentDescriptionEquals("Pings: 0.0\u00a0Hz · Eigene")
        ui.onNodeWithTag("Sonar selected usable depth rate").assertContentDescriptionEquals("Brauchbare Tiefe · Auswahl: 0.0\u00a0Hz · Eigene")
        ui.runOnIdle {source.state.emit(source.state.value.copy(stale=true))}
        ui.onNodeWithTag("Sonar main published rate").assertContentDescriptionEquals("Pings: — · Veraltet · Eigene")
        ui.runOnIdle {publication(SonarPingSource.NATIVE,2,reception=true)}
        ui.onNodeWithTag("Sonar main published rate").assertContentDescriptionEquals("Empfangsrate: 4.0\u00a0Hz · Venterior")
        ui.onNodeWithTag("Sonar selected usable depth rate").assertContentDescriptionEquals("Brauchbare Tiefe · Auswahl: — · Unbestätigt · Venterior")
        assertEquals(SonarRateTimeBasis.RECEPTION,controller.pingRate.timeBasis)
    }

    private class ObservedSource:SonarSourcePort {
        override val identity=SourceIdentity("explicit-hierarchy-test",SourceMode.PI,"SYNTHETIC RATE HIERARCHY")
        val state=MutableSourceFeed(SonarSourceStatus(identity))
        override val status:SourceFeed<SonarSourceStatus> get()=state
        override val capabilities=MutableSourceFeed(SonarSourceCapabilities(identity,sourceSelection=true,diagnostics=true))
        val packets=MutableSourceFeed<SonarSourceFrame?>(null)
        override val frames:SourceFeed<SonarSourceFrame?> get()=packets
        val detail=MutableSourceFeed<SonarDiagnostics?>(null)
        override val diagnostics:SourceFeed<SonarDiagnostics?> get()=detail
        fun connect(selection:SonarPingSource,generation:Long) {
            state.emit(SonarSourceStatus(identity,1,true,true,serverSessionId="synthetic-rate-host",serverGeneration=4,
                displaySelection=SonarDisplaySelection(selection,generation)))
        }
        fun diagnostic(at:Long,selection:SonarPingSource,generation:Long,count:Int=30)=SonarDiagnostics(identity,4,"synthetic-rate-host",null,null,selection,
            listOf(SonarDiagnosticField("host_package","name","no5-sonard-openecho"),SonarDiagnosticField("host_package","version","0.4.15"),
                SonarDiagnosticField("display_selection","source",selection.name.lowercase()),SonarDiagnosticField("display_selection","generation",generation.toString()),
                SonarDiagnosticField("transmit","rate_control.schema","no5.depth-rate-control/1"),SonarDiagnosticField("transmit","ping_rates.window_s","5"),
                SonarDiagnosticField("transmit","ping_rates.observed_s","5"),SonarDiagnosticField("transmit","ping_rates.complete","true"),
                SonarDiagnosticField("transmit","ping_rates.start_device_us","1000000"),SonarDiagnosticField("transmit","ping_rates.end_device_us","6000000"),
                SonarDiagnosticField("transmit","ping_rates.time_basis","device_marker_us_host_extrapolated_now"),
                SonarDiagnosticField("transmit","ping_rates.rates.usable.count",count.toString()),SonarDiagnosticField("transmit","ping_rates.rates.usable.hz",(count/5.0).toString())),
            available=true,statusObservedAtEpochMs=at,statusStale=false,statusTimeBasis="GATEWAY_STATUS_REQUEST_STARTED_AT_UTC")
        override fun setDiagnosticsVisible(visible:Boolean)=Unit
        override fun start(request:SonarAcquisitionRequest)=SonarAcquisitionOutcome.Rejected(identity.sessionId,request.requestId,SonarRejection.UNSUPPORTED,"UNSUPPORTED")
        override fun stop(request:SonarAcquisitionRequest)=start(request)
        override fun tick()=Unit
        override fun close()=Unit
    }
}
