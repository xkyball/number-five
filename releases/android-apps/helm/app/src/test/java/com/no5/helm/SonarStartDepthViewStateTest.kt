package com.no5.helm

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.no5.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class SonarStartDepthViewStateTest {
    private val app:Application get()=RuntimeEnvironment.getApplication()
    @Test fun rangeKnobLabelUsesTheEffectiveViewportAndDoesNotInventAnUnavailableEnd() {
        val source=DeterministicMockSonarSource(now={1000000L});val c=SonarNativeController(source){1000000L}
        val audio=AudioNativeController(source=MockAudioSource(now={1000000L}))
        try {
            c.model.connect("Range label fixture");c.model.acquisition(true)
            c.model.setAnalysis(SonarGeometry(30.69,SonarTxReference.HOST_VERIFIED),SonarBottom(33.0,.9),null,true,
                amplitude=SonarAmplitude(4095,12,true))
            c.model.frame(SonarFrame(ShortArray(1024),1,1000000L));c.refresh()
            c.startDepth(20.0);c.autoRange(true);c.display{it.copy(cropEnd=50.0)}
            val overlay=KnobOverlay(app)
            assertEquals(sonarNumber(20.96),overlay.values(audio,c)[0][1])
            c.startDepth(40.0);assertEquals("—",overlay.values(audio,c)[0][1])
        }finally{audio.dispose();c.dispose();source.close()}
    }
    private fun session(id:String)=object:No5SourceSession {
        override val identity=SourceIdentity(id,SourceMode.PI,"start-depth persistence test")
        override val sonar=UnavailablePiSonarSource(identity)
        override val audio=MockAudioSource(now={1000000L})
        override val helm:HelmSource get()=error("No extra telemetry client")
        override val weather:WeatherSourcePort get()=error("No weather client")
        override fun close(){sonar.close();audio.close()}
    }
    private fun state():JSONObject {
        val file=File(app.filesDir,"helm-view-v1.json");file.delete()
        val store=ViewModelStore();val owner=HelmPresentationViewModel(app);store.put("test",owner)
        try {
            owner.save("SONAR",AudioViewState(2),SonarViewState(SonarDisplay(startDepthM=.3,gainDb=7.0,historySeconds=300.0)))
            val end=System.nanoTime()+2_000_000_000L
            while(!file.exists()&&System.nanoTime()<end)Thread.sleep(5)
            return JSONObject(file.readText())
        }finally{store.clear()}
    }
    @Test fun versionFourRestoresExactMetersAndOtherPreferencesOnColdSource() {
        val saved=state();assertEquals(4,saved.getInt("version"))
        assertEquals(.3,saved.getJSONObject("sonar").getDouble("startDepthM"),0.0)
        val source=session("restart");val store=ViewModelStore();val owner=HelmPresentationViewModel(app);store.put("test",owner)
        try {
            val c=owner.acquire(source,AudioPresentationSettings.DEFAULT).sonar
            assertEquals(.3,c.snapshot.display.startDepthM,0.0);assertEquals(7.0,c.snapshot.display.gainDb,0.0)
            assertEquals(300.0,c.snapshot.display.historySeconds,0.0)
            assertEquals(0,c.model.ring.count);assertNull(c.depthDisplay.valueM)
            assertEquals(SonarViewportState.UNCALIBRATED,c.bounds().state)
        }finally{store.clear();source.close()}
    }
    @Test fun oldStateVersionsKeepTheirSettingsAndStartAtTheSurface() {
        val saved=state()
        for(version in 1..3){
            saved.put("version",version);saved.getJSONObject("sonar").remove("startDepthM")
            File(app.filesDir,"helm-view-v1.json").writeText(saved.toString())
            val source=session("legacy-$version");val store=ViewModelStore();val owner=HelmPresentationViewModel(app);store.put("test",owner)
            try {
                val d=owner.acquire(source,AudioPresentationSettings.DEFAULT).sonar.snapshot.display
                assertEquals(0.0,d.startDepthM,0.0);assertEquals(7.0,d.gainDb,0.0);assertEquals(300.0,d.historySeconds,0.0)
            }finally{store.clear();source.close()}
        }
    }
}
