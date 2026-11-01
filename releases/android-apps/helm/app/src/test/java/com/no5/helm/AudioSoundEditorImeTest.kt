package com.no5.helm

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.*
import com.no5.helm.network.soundFixture
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses the existing signed SpecialKeyboardGeometry boundary, never a parallel inset model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioSoundEditorImeTest {
    @get:Rule val ui=createComposeRule()
    @Test fun measuredDistancesKeepDraftAndFixedActionsAboveTheSignedKeyboard()=matrix(false)
    @Test fun exactPositionsKeepInvalidDraftAndFixedReturnAboveTheSignedKeyboard()=matrix(true)

    @Test fun actualAudioSetupCallerKeepsAliasDraftSaveAndCloseAboveTheSignedKeyboard(){
        val context=RuntimeEnvironment.getApplication()
        val helper=HelmBodyMatrixSupport(ui,"sound-setup-ime")
        var audio:AudioNativeController?=null
        var saves=0
        FaderTemplateDiagnostics.observe={fit->
            if(!fit.dynamic)assertTrue("Native fader label clipped: $fit",fit.width<=fit.available+.01f)
            assertEquals(1.25f,fit.fontScale,0f)
        }
        try{
            helper.run(1.25f,listOf("setup"),nativeCanvasBody=true,before={
                audio?.dispose()
                val source=MockAudioSource(now={10000L}).also{it.loadFixture(AudioFixture.DEMO)}
                audio=AudioNativeController(Dispatchers.Unconfined,now={10000L},automaticTicker=false,automaticVerification=false,source=source).also{it.openPanel("mixer")}
                saves=0
            },after={_,frame->
                ui.onNodeWithTag("sound-setup-alias-MASTER").performClick()
                focusHelmTestForegroundWindow(ui)
                val initial=ui.onNodeWithTag("sound-setup-name-viewport").fetchSemanticsNode().boundsInRoot
                ui.onNodeWithTag("sound-setup-name-input").performTextReplacement("OBSERVED_alias")
                val keyboardTop=initial.bottom.toInt()-80
                ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(context,context.packageName,android.graphics.Rect(353,keyboardTop,1656,1200))}
                ui.waitForIdle()
                val viewport=ui.onNodeWithTag("sound-setup-name-viewport").fetchSemanticsNode().boundsInRoot
                assertEquals(keyboardTop.toFloat(),viewport.bottom,0f);assertTrue(viewport.height<initial.height)
                for(tag in listOf("sound-setup-name-apply","sound-setup-name-cancel")){
                    val b=ui.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
                    assertTrue("$tag must remain wholly above keyboard",b.top>=viewport.top&&b.bottom<=viewport.bottom)
                    assertTrue(KnobGeometry.safe(b.left,b.top,b.right,b.bottom))
                }
                val apply=ui.onNodeWithTag("sound-setup-name-apply").fetchSemanticsNode().boundsInRoot
                ui.onNodeWithTag("sound-setup-name-input").performScrollTo().assertIsDisplayed()
                assertEquals(apply,ui.onNodeWithTag("sound-setup-name-apply").fetchSemanticsNode().boundsInRoot)
                helper.inspect("${frame.language}/${frame.night}/1.25/setup-keyboard-visible",1.25f)
                helper.capture("${frame.language}-${frame.night}-keyboard-visible")
                ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(context,context.packageName,null)}
                ui.waitForIdle()
                assertEquals(initial,ui.onNodeWithTag("sound-setup-name-viewport").fetchSemanticsNode().boundsInRoot)
                ui.onNodeWithTag("sound-setup-name-input").assertTextContains("OBSERVED_alias")
                ui.onNodeWithTag("sound-setup-name-apply").performClick()
                focusHelmTestForegroundWindow(ui)
                ui.onNodeWithTag("sound-setup-save").performClick()
                assertEquals(1,saves)
                assertEquals("OBSERVED_alias",requireNotNull(audio).presentation.alias(AudioChannel.MASTER))
            }){f->AudioScreen(.5f,f.night,requireNotNull(audio),onSavePresentation={saves++;requireNotNull(audio).updatePresentation(it)})}
        }finally{FaderTemplateDiagnostics.observe=null;ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(context,context.packageName,null);audio?.dispose()}}
    }

    private fun matrix(exact:Boolean){
        val context=RuntimeEnvironment.getApplication()
        val helper=HelmBodyMatrixSupport(ui,if(exact)"sound-exact-ime"else"sound-measure-ime")
        var audio:AudioNativeController?=null
        try{
            helper.run(1.25f,listOf(if(exact)"positions"else"measure"),nativeCanvasBody=true,before={section->
                audio?.dispose()
                context.getSharedPreferences("no5-sound-drafts-v1",Context.MODE_PRIVATE).edit().clear().commit()
                val port=object:AudioSoundPort{
                    override val state=MutableSourceFeed(soundFixture().copy(calibration=SoundCalibration(
                        SoundSpeakerId.entries.mapIndexed{i,id->SoundSpeaker(id,when(i){0->SoundPosition(0.0,0.0);1->SoundPosition(1.7,0.0);2->SoundPosition(0.0,4.6);3->SoundPosition(1.7,4.6);else->SoundPosition(.8,2.5)})},SoundPosition(.7,2.4),true)))
                    override val spectrum=MutableSourceFeed<AudioSpectrumFrame?>(null)
                    override val results=MutableSourceFeed<AudioSoundResult?>(null)
                    override fun submit(command:AudioSoundCommand)=1L
                    override fun setEditorVisible(visible:Boolean,eqVisible:Boolean)=Unit
                    override fun refresh()=Unit
                    override fun close()=Unit
                }
                val delegate=MockAudioSource(now={10000L}).also{it.loadFixture(AudioFixture.DEMO)}
                val source=object:AudioSourcePort by delegate{override val sound=port}
                audio=AudioNativeController(Dispatchers.Unconfined,now={10000L},automaticTicker=false,automaticVerification=false,source=source).also{
                    it.openSoundEditor(true);it.soundEditor.changeSection(section)
                }
            },after={_,frame->
                val viewportTag=if(exact)"sound-exact-viewport"else"sound-measure-viewport"
                val bodyTag=if(exact)"sound-exact-body"else"sound-measure-body"
                val actionTag=if(exact)"sound-exact-back"else"sound-measure-apply"
                val inputTag=if(exact)"sound-exact-FRONT_LEFT-x"else"sound-measure-width"
                val initial=ui.onNodeWithTag(viewportTag).fetchSemanticsNode().boundsInRoot
                ui.onNodeWithTag(inputTag).performTextReplacement(if(exact)"pending"else"1,9")
                val keyboardTop=minOf(720,initial.bottom.toInt()-120)
                ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(context,context.packageName,android.graphics.Rect(353,keyboardTop,1656,1200))}
                ui.waitForIdle()
                val viewport=ui.onNodeWithTag(viewportTag).fetchSemanticsNode().boundsInRoot
                assertEquals(keyboardTop.toFloat(),viewport.bottom,0f)
                assertTrue(viewport.height<initial.height)
                val action=ui.onNodeWithTag(actionTag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
                assertTrue("Confirmation/return must be wholly above the actual signed keyboard: $action / $viewport",action.top>=viewport.top&&action.bottom<=viewport.bottom)
                assertTrue(KnobGeometry.safe(action.left,action.top,action.right,action.bottom))
                val finalInput=if(exact)"sound-exact-FRONT_LEFT-z"else"sound-measure-length"
                ui.onNodeWithTag(finalInput).performScrollTo().assertIsDisplayed()
                val shownInput=ui.onNodeWithTag(finalInput).fetchSemanticsNode().boundsInRoot
                val body=ui.onNodeWithTag(bodyTag).fetchSemanticsNode().boundsInRoot
                assertTrue("Actual input must fit in the scroll body",shownInput.top>=body.top&&shownInput.bottom<=body.bottom)
                assertEquals("Scrolling fields cannot move confirmation/return",action,ui.onNodeWithTag(actionTag).fetchSemanticsNode().boundsInRoot)
                helper.inspect("${frame.language}/${frame.night}/1.25/keyboard-visible",1.25f)
                helper.capture("${frame.language}-${frame.night}-keyboard-visible")
                ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(context,context.packageName,null)}
                ui.waitForIdle()
                assertEquals(initial,ui.onNodeWithTag(viewportTag).fetchSemanticsNode().boundsInRoot)
                ui.onNodeWithTag(inputTag).performScrollTo().assertTextContains(if(exact)"pending"else"1,9")
                val editor=requireNotNull(audio).soundEditor
                if(exact){
                    assertTrue("Invalid local coordinate must survive keyboard hide",editor.hasInvalidGeometry)
                    ui.onNodeWithTag(inputTag).performTextReplacement("0.2")
                    assertFalse(editor.hasInvalidGeometry)
                    assertEquals(.2,editor.calibration!!.speakers.single{it.id==SoundSpeakerId.FRONT_LEFT}.positionM!!.x,0.0)
                }else{
                    assertEquals("1,9",editor.measurementDraft!!.width)
                    ui.onNodeWithTag("sound-measure-cancel").performClick()
                    assertFalse(editor.measurementDirty)
                    assertEquals(1.7,editor.calibration!!.speakers.single{it.id==SoundSpeakerId.FRONT_RIGHT}.positionM!!.x,0.0)
                }
            }){f->AudioSoundEditor(.5f,f.night,requireNotNull(audio),{},registerClose={},registerGuard={})}
        }finally{
            ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(context,context.packageName,null);audio?.dispose()}
        }
    }
}
