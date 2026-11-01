package com.no5.helm

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.no5.domain.*
import com.no5.helm.network.SonarDiagnosticsDecoder
import com.no5.ui.UiLanguage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Explicit synthetic wire/port fixtures: no sockets, Sonard process, TX, device or real location.
 * These tests prove host data-to-UI behavior only; P0-11 requires an actual shared operator view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="de-rDE-w1000dp-h600dp-land-mdpi",
    shadows=[NavigationComposeRenderTest.HostPowerManager::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SonarDetailedEvidenceUiTest {
    @get:Rule val ui=createComposeRule()
    private val now=1_000_000L
    private val source=SyntheticEvidenceSource(now)
    private val controller=SonarNativeController(source){now}
    private var previousLanguage:String?=null

    @Before fun languageFixture(){
        val context=RuntimeEnvironment.getApplication()
        previousLanguage=UiLanguage.current(context)
        UiLanguage.acceptConfirmed(context,"de")
    }
    @After fun releaseFixture(){
        controller.dispose()
        previousLanguage?.let {UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),it)}
    }

    @Test fun measuredTemplateQualificationKeepsEveryIndependentMetricThroughWireAndUi(){
        val templatePing=(Long.MAX_VALUE-1).toString()
        val fields=listOf(
            field("block:0","detector","direct_envelope"),
            field("block:0","qualification_method","track_local_measured_template_v1"),
            field("block:0","template_ping_id",templatePing),
            field("block:0","template_correlation","0.86"),
            field("block:0","template_score","0.67"),
            field("block:0","template_fit_window","measured_core_v1"),
            field("block:0","template_fit_window_samples","101"),
            field("block:0","template_fit_offset_samples","-5"),
            field("block:0","template_observed_peak_sample","973"),
            field("track:73","confidence","0.55"),
            field("track:73","rule_score","0.42"))
        show(fields)
        val decoded=requireNotNull(controller.diagnostics)
        assertEquals(source.pingId,decoded.pingId)
        assertEquals(source.serverSession,decoded.serverSessionId)
        assertEquals(SonarPingSource.OWN,decoded.pingSource)
        assertEquals(fields,decoded.fields) // No score merging, rounding, ID conversion or inferred qualification.
        sonarWorkspaceTap(ui,"no5-row-sonar-host")
        ui.onNodeWithTag("Sonar details block:0").performScrollTo().performClick()
        for(row in fields.filter {it.group=="block:0"})fact(row.name,row.value)
        ui.onNodeWithTag("Sonar details track:73").performScrollTo().performClick()
        fact("confidence","0.55")
        fact("rule_score","0.42")
        assertEquals("direct_envelope",decoded.fields.single {it.name=="detector"}.value)
        assertNotEquals(decoded.fields.single {it.name=="template_correlation"}.value,
            decoded.fields.single {it.name=="template_score"}.value)
        assertEquals(0,source.writeAttempts)
    }

    @Test fun displayProfileStatisticsUseAll1024DisplayBinsAndKeepRawGeometrySeparate(){
        val samples=ShortArray(1024){(it%16).toShort()}.also {it[1023]=4095}
        val original=samples.copyOf()
        show(listOf(
            field("accepted_config","ack.actual_sample_count","8192"),
            field("accepted_config","ack.actual_sample_period_ns","2000","ns")),samples)
        assertArrayEquals(original,controller.model.latest!!.samples)
        assertEquals(4095,controller.model.amplitudeFullScale)
        assertEquals(0.03,controller.model.metersPerBin!!,1e-12)
        sonarWorkspaceTap(ui,"no5-row-sonar-geometry")
        fact("Displayprofil Min / Max","0 / 4095")
        fact("Displayprofil Mittel","11.5") // Sum=11760, mean=11.484375, never divided by the raw count 8192.
        fact("Anzeige / Vollskala","1024 Bins / 4095")
        fact("ADC-Abstand","2.00 µs")
        fact("Anzeigebin-Abstand","0.03000 m")
        sonarWorkspaceCloseSheet(ui);sonarWorkspaceTap(ui,"no5-row-sonar-host")
        ui.onNodeWithTag("Sonar details accepted_config").performScrollTo().performClick()
        fact("ack.actual_sample_count","8192")
        fact("ack.actual_sample_period_ns","2000\u00a0ns")
        assertArrayEquals(original,controller.model.latest!!.samples)
        assertEquals(0,source.writeAttempts)
    }

    @Test fun absentMeasuredContextStaysAbsentWhileAnObservedZeroCounterRemainsZero(){
        // The current bounded Gateway projection has no qualified GPS/water/mount values.
        // Missing source/time evidence must not be supplied from a boat drawing or other panel.
        val supplied=listOf(field("transmit","physical_markers","0"))
        show(supplied)
        assertEquals(supplied,controller.diagnostics!!.fields)
        assertTrue(controller.diagnostics!!.fields.none {it.group in setOf("gps","water","mount","context")})
        sonarWorkspaceTap(ui,"no5-row-sonar-host")
        fact("Physische Marker · Host","0")
        for(name in listOf("gps","latitude","longitude","temperature_c","voltage_v","current_a","draft_m"))
            ui.onNodeWithTag("Sonar fact $name").assertDoesNotExist()
        sonarWorkspaceCloseSheet(ui);sonarWorkspaceTap(ui,"no5-row-sonar-geometry")
        fact("Displayprofil Min / Max","0 / 0") // Real observed zeros are not turned into missing measurements.
        fact("Displayprofil Mittel","0.0")
        assertEquals(0,source.writeAttempts)
    }

    private fun fact(name:String,expected:String){
        ui.onNodeWithTag("Sonar fact $name").performScrollTo().assertTextEquals(expected)
    }
    private fun field(group:String,name:String,value:String,unit:String?=null)=SonarDiagnosticField(group,name,value,unit)
    private fun show(fields:List<SonarDiagnosticField>,samples:ShortArray=ShortArray(1024)){
        source.publish(samples)
        controller.open(SonarDrawerTab.DIAGNOSTICS)
        val rows=JSONArray()
        fields.forEach {row->rows.put(JSONObject().put("group",row.group).put("name",row.name).put("value",row.value)
            .apply {row.unit?.let {put("unit",it)}})}
        val wire=JSONObject().put("version",1).put("kind","sonar-diagnostics")
            .put("serverSessionId",source.serverSession).put("generation",source.serverGeneration)
            .put("pingId",source.pingId).put("observedAtEpochMs",now).put("source","own")
            .put("available",true).put("stale",false).put("fields",rows)
            .put("trackTotal",fields.map {it.group}.distinct().count {it.startsWith("track:")}).put("trackOmitted",0)
            .put("blockTotal",fields.map {it.group}.distinct().count {it.startsWith("block:")}).put("blockOmitted",0)
        source.detail.emit(SonarDiagnosticsDecoder.decode(wire.toString(),source.identity))
        ui.setContent {Box(Modifier.size(1000.dp,600.dp)){SonarScreen(.5f,false,controller)}}
        assertEquals(source.pingId,controller.model.latest!!.pingId)
        assertTrue(source.diagnosticsDemand)
    }

    private class SyntheticEvidenceSource(private val observed:Long):SonarSourcePort{
        override val identity=SourceIdentity("synthetic-detail-port",SourceMode.PI,"SYNTHETIC HOST TEST")
        val serverSession="synthetic-detail-session"
        val serverGeneration=3L
        val pingId=Long.MAX_VALUE
        val current=MutableSourceFeed(SonarSourceStatus(identity))
        override val status:SourceFeed<SonarSourceStatus> get()=current
        override val capabilities=MutableSourceFeed(SonarSourceCapabilities(identity,bottomDetection=true,diagnostics=true))
        val packets=MutableSourceFeed<SonarSourceFrame?>(null)
        override val frames:SourceFeed<SonarSourceFrame?> get()=packets
        private val selection=SonarDisplaySelection(SonarPingSource.OWN,7)
        override val sourceState=MutableSourceFeed(SonarSourceSelectionState(identity,1,true,selection))
        val detail=MutableSourceFeed<SonarDiagnostics?>(null)
        override val diagnostics:SourceFeed<SonarDiagnostics?> get()=detail
        var diagnosticsDemand=false
        var writeAttempts=0
        override fun setDiagnosticsVisible(visible:Boolean){diagnosticsDemand=visible}
        fun publish(samples:ShortArray){
            current.emit(SonarSourceStatus(identity,1,true,true,source="SYNTHETIC HOST TEST",simulated=true,
                serverSessionId=serverSession,serverGeneration=serverGeneration,displaySelection=selection,
                amplitude=SonarAmplitude(4095,12,true,"HELLO_12bit_checked_host_contract")))
            packets.emit(SonarSourceFrame(identity,1,SonarFrame(samples,pingId,observed),
                serverSessionId=serverSession,simulated=true,geometry=SonarGeometry(30.69,SonarTxReference.HOST_VERIFIED,1,2.0),
                hostPackageVersion="0.4.10",displaySelection=selection,amplitude=SonarAmplitude(4095,12,true,"HELLO_12bit_checked_host_contract"),
                pingSource=SonarPingSource.OWN,serverGeneration=serverGeneration))
        }
        override fun start(request:SonarAcquisitionRequest):SonarAcquisitionOutcome{
            writeAttempts++
            return SonarAcquisitionOutcome.Rejected(identity.sessionId,request.requestId,SonarRejection.UNSUPPORTED,"SYNTHETIC TEST DOES NOT WRITE")
        }
        override fun stop(request:SonarAcquisitionRequest)=start(request)
        override fun tick()=Unit
        override fun close()=Unit
    }
}
