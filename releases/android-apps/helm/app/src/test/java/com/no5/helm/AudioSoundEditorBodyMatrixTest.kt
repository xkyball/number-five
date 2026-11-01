package com.no5.helm

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.*
import com.no5.helm.network.soundFixture
import com.no5.ui.No5Text
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class BodySoundPort(initial:AudioSoundState):AudioSoundPort{
    override val state=MutableSourceFeed(initial)
    override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
    override val results=MutableSourceFeed<AudioSoundResult?>(null)
    val commands=mutableListOf<AudioSoundCommand>()
    override fun submit(command:AudioSoundCommand):Long{commands+=command;return commands.size.toLong()}
    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
    override fun refresh()=Unit
    override fun close()=Unit
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioSoundEditorBodyMatrixTest{
    @get:Rule val ui=createComposeRule()
    @Test fun everyRealSoundBodyAndCloseDecisionFitsAtNormalFont()=matrix(1f)
    @Test fun everyRealSoundBodyAndCloseDecisionFitsAtLargeFont()=matrix(1.25f)
    private fun matrix(font:Float){
        val h=HelmBodyMatrixSupport(ui,"sound-bodies")
        var audio:AudioNativeController?=null
        val texts=mutableListOf<SoundTextFit>()
        val nativeEvidence=org.json.JSONArray()
        SoundTemplateDiagnostics.observeFit={if(it.source=="AUDIO / ÄNDERUNGEN")texts.clear();texts+=it}
        com.no5.ui.No5DialogDiagnostics.observe={f->texts+=SoundTextFit(f.text,f.text,f.text,f.size,f.available,f.bounds.bottom,android.graphics.RectF(f.bounds))}
        val cases=listOf("eq","eq-no-point","eq-locked","eq-bypassed","room-known","room-unknown","room-front-only","delay","gain","sub-delay","sub-gain","measure","measure-overwrite","measure-invalid","place-unknown","place-listener","place-sub","positions","unavailable","close-preset","close-calibration","close-measurements","close-save-error","foreign-revision")
        try{
            h.run(font,cases,nativeCanvasBody=true,before={case->
                audio?.dispose();RuntimeEnvironment.getApplication().getSharedPreferences("no5-sound-drafts-v1",Context.MODE_PRIVATE).edit().clear().commit()
                val known=case !in setOf("room-unknown","measure","measure-invalid","place-unknown","close-measurements","unavailable")
                val positions=listOf(SoundPosition(0.0,0.0),SoundPosition(1.7,0.0),SoundPosition(0.0,4.6),SoundPosition(1.7,4.6),SoundPosition(.8,2.5))
                var state=soundFixture()
                state=state.copy(preset=state.preset!!.copy(name="OBSERVED_PRESET",eqEnabled=case!="eq-bypassed",eqPoints=if(case=="eq-no-point")emptyList()else listOf(SoundEqPoint("point",1000.0,3.0,1.0,case=="eq-locked"))),headroomDb=if(case=="eq-bypassed")0.0 else 12.0,
                    calibration=if(known)SoundCalibration(SoundSpeakerId.entries.mapIndexed{i,id->SoundSpeaker(id,positions[i])},SoundPosition(.7,2.4),true)else SoundCalibration())
                if(case=="room-front-only")state=state.copy(preset=state.preset!!.copy(routingTemplate="front_stereo_fullrange"),capabilities=state.capabilities.copy(room=false))
                if(case=="unavailable")state=AudioSoundState(reason="HTTP_503")
                val port=BodySoundPort(state)
                val delegate=MockAudioSource(now={10000L}).also{it.loadFixture(AudioFixture.DEMO)}
                val source=object:AudioSourcePort by delegate{override val sound=port}
                val ctl=AudioNativeController(Dispatchers.Unconfined,now={10000L},automaticTicker=false,automaticVerification=false,source=source);audio=ctl
                val speaker=case in setOf("delay","gain","sub-delay","sub-gain","measure","measure-overwrite","measure-invalid","place-unknown","place-listener","place-sub","positions","close-calibration","close-measurements")
                ctl.openSoundEditor(speaker)
                val editor=ctl.soundEditor
                val section=when{case.startsWith("room")->"room";case in setOf("gain","sub-gain")->"gain";case.startsWith("measure")||case=="close-measurements"->"measure";case.startsWith("place")->"place";case=="positions"->"positions";speaker->"delay";else->"eq"}
                editor.changeSection(section)
                if(case!="eq-no-point")editor.choosePoint("point")
                if(case.startsWith("sub-"))editor.chooseSpeaker(SoundSpeakerId.SUB)
                if(case=="place-listener")editor.beginPositioning(SoundPositionTarget.LISTENER)
                if(case=="place-sub")editor.beginPositioning(SoundPositionTarget.SUB)
                if(case=="measure-overwrite")editor.changeCalibration(editor.calibration!!.copy(speakers=editor.calibration!!.speakers.map{if(it.id==SoundSpeakerId.FRONT_LEFT)it.copy(positionM=SoundPosition(.2,.1,.3))else it}))
                if(case=="measure-invalid"){editor.editMeasurement(true,"not a distance");editor.applyMeasurements()}
                if(case.startsWith("close-")){
                    if(case=="close-calibration")editor.changeCalibration(editor.calibration!!.copy(speakers=editor.calibration!!.speakers.map{if(it.id==SoundSpeakerId.FRONT_LEFT)it.copy(manualDelayMs=.1)else it}))
                    else if(case=="close-measurements")editor.editMeasurement(true,"1,7")
                    else editor.changePreset(editor.preset!!.copy(room=SoundRoom(.3,.5)))
                    editor.requestClose(if(speaker)SoundScope.CALIBRATION else SoundScope.PRESET){}
                    if(case=="close-save-error"){editor.saveAndClose();port.results.emit(AudioSoundResult(port.commands.size.toLong(),"REJECTED","FIXTURE_DISK_FAILURE"))}
                }
                if(case=="foreign-revision"){
                    editor.changePreset(editor.preset!!.copy(room=SoundRoom(.3,.5)))
                    port.state.emit(state.copy(presetRevision=state.presetRevision+1))
                }
                texts.clear()
            },after={case,frame->
                // Support has just drawn the real product View before checking these metrics.
                assertTrue("$case rendered no native body text",texts.isNotEmpty())
                val active=if(case.startsWith("close-"))texts.filter{it.source=="AUDIO / ÄNDERUNGEN"||it.source=="Änderungen behalten?"||it.baseline in 300f..900f}else texts
                for(item in active){
                    nativeEvidence.put(org.json.JSONObject().put("language",frame.language).put("night",frame.night).put("fontScale",font).put("case",case).put("source",item.source).put("fullText",item.fullText).put("drawnText",item.drawnText).put("fontPx",item.textSize).put("maxWidth",item.maxWidth.toDouble()).put("bounds",org.json.JSONArray(listOf(item.bounds.left,item.bounds.top,item.bounds.right,item.bounds.bottom))))
                    val dynamic=item.source.startsWith("OBSERVED_")||item.source.any{it.isDigit()}||item.source.isBlank()
                    if(!dynamic&&item.fullText!=item.drawnText)h.problems+="${frame.language}/${frame.night}/$font/$case fixed native body truncated: $item"
                    if(item.bounds.width()>item.maxWidth+.01f)h.problems+="${frame.language}/${frame.night}/$font/$case native glyph exceeds actual width: $item"
                    if(item.bounds.left<0||item.bounds.right>2000||item.bounds.top<130||item.bounds.bottom>1058)h.problems+="${frame.language}/${frame.night}/$font/$case native text outside preserved content area: $item"
                }
                ui.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach{n->val b=n.boundsInRoot;if(b.width>0&&b.height>0)assertTrue("$case overlaps physical knob: $b",KnobGeometry.safe(b.left,b.top,b.right,b.bottom))}
                texts.clear()
            }){f->AudioSoundEditor(.5f,f.night,requireNotNull(audio),{},registerClose={},registerGuard={})}
        }finally{
            val dir=java.io.File(System.getProperty("no5.renderOutputDir"),"sound-bodies").apply{mkdirs()}
            java.io.File(dir,"native-text-font$font.json").writeText(nativeEvidence.toString(2))
            SoundTemplateDiagnostics.observeFit=null;com.no5.ui.No5DialogDiagnostics.observe=null;ui.runOnIdle{audio?.dispose()}
        }
    }
}
