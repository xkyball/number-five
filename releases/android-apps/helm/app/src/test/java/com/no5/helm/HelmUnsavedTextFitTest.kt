package com.no5.helm

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.ui.No5Text
import com.no5.ui.UiLanguage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Actual dialog text layout and host Canvas evidence; no claim of a physical keyboard/device run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HelmUnsavedTextFitTest {
    @get:Rule val ui=createComposeRule()

    @Test fun translatedFixedActionsAndTitleStayOutsideTheOnlyScrollableBodyAtNormalFontScale()=translatedDialogMatrix(1f)
    @Test fun translatedFixedActionsAndTitleStayOutsideTheOnlyScrollableBodyAtLargeFontScale()=translatedDialogMatrix(1.25f)

    private fun translatedDialogMatrix(scale:Float){
        val context=RuntimeEnvironment.getApplication();val original=UiLanguage.current(context);val originalFont=RuntimeEnvironment.getFontScale()
        val renderEpoch=mutableStateOf(0)
        val language=mutableStateOf("de");val night=mutableStateOf(false)
        // Changing Android configuration while a test Activity is running recreates it.
        // Each font-scale case therefore creates its real Dialog owner at that scale.
        RuntimeEnvironment.setFontScale(scale)
        val error="HTTP_503 · "+"Der Entwurf bleibt erhalten. Die gespeicherte Konfiguration wurde nicht ersetzt. ".repeat(45)
        ui.setContent{
            UiLanguage.acceptConfirmed(context,language.value)
            key(renderEpoch.value){MaterialTheme{UnsavedChangesDialog({},{},{},error=error,night=night.value)}}
        }
        try{
            for(lang in listOf("de","en","af"))for(isNight in listOf(false,true)){
                ui.runOnIdle{language.value=lang;night.value=isNight;renderEpoch.value++}
                assertTextGeometry(scale)
                val title=ui.onNodeWithTag("unsaved-changes-title").fetchSemanticsNode().boundsInRoot
                val actions=ui.onNodeWithTag("unsaved-changes-actions").fetchSemanticsNode().boundsInRoot
                val body=ui.onNodeWithTag("unsaved-changes-body")
                assertTrue(body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()>0)
                body.performSemanticsAction(SemanticsActions.ScrollBy){assertTrue(it(0f,100000f))}
                assertEquals("Title must not scroll with a long error",title,ui.onNodeWithTag("unsaved-changes-title").fetchSemanticsNode().boundsInRoot)
                assertEquals("Actions must not scroll with a long error",actions,ui.onNodeWithTag("unsaved-changes-actions").fetchSemanticsNode().boundsInRoot)
                ui.runOnUiThread{
                    val view=ShadowDialog.getLatestDialog().window!!.decorView
                    val image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(image))
                    val folder=File(System.getProperty("no5.renderOutputDir"),"audio-textfit").apply{mkdirs()}
                    File(folder,"$lang-unsaved-${if(isNight)"night"else"day"}-font$scale.png").outputStream().use{assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
                    image.recycle()
                }
            }
        }finally{ui.runOnIdle{RuntimeEnvironment.setFontScale(originalFont);UiLanguage.acceptConfirmed(context,original)}}
    }

    @Test @Config(sdk=[29],qualifiers="af-w1000dp-h360dp-land-xhdpi")
    fun reducedAvailableHeightKeepsFixedTextAndActionsInsteadOfScalingTheWholeDialog(){
        val context=RuntimeEnvironment.getApplication();val original=UiLanguage.current(context);val originalFont=RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(1.25f)
        ui.setContent{
            UiLanguage.acceptConfirmed(context,"af")
            MaterialTheme{UnsavedChangesDialog({},{},{},error="HTTP_503 · "+"Die konsep bly behoue. ".repeat(100))}
        }
        try{
            assertTextGeometry(1.25f)
            val body=ui.onNodeWithTag("unsaved-changes-body").fetchSemanticsNode()
            assertTrue(body.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()>0)
        }finally{ui.runOnIdle{RuntimeEnvironment.setFontScale(originalFont);UiLanguage.acceptConfirmed(context,original)}}
    }

    @Test fun outsideAndContinueNeverDiscardAndBusyStateBlocksAllCompletionActions(){
        var saves=0;var discards=0;var continues=0
        val busy=mutableStateOf(false)
        ui.setContent{MaterialTheme{UnsavedChangesDialog({saves++},{discards++},{continues++},saving=busy.value)}}
        ui.onNodeWithTag("unsaved-changes-stage").performTouchInput{click(androidx.compose.ui.geometry.Offset(5f,5f))}
        ui.runOnIdle{assertEquals(1,continues);assertEquals(0,discards);assertEquals(0,saves)}
        // Robolectric does not deliver focus to the newly shown native Dialog itself.
        // Exercise the real window callback, preserving the production focus guard.
        ui.runOnIdle{ShadowDialog.getLatestDialog().window!!.decorView.dispatchWindowFocusChanged(true)}
        ui.waitForIdle()
        ui.onNodeWithTag("unsaved-changes-continue").assertIsEnabled().performClick()
        ui.runOnIdle{assertEquals(2,continues);busy.value=true}
        for(tag in listOf("unsaved-changes-save","unsaved-changes-discard","unsaved-changes-continue"))ui.onNodeWithTag(tag).assertIsNotEnabled()
        ui.onNodeWithTag("unsaved-changes-stage").performTouchInput{click(androidx.compose.ui.geometry.Offset(5f,5f))}
        ui.runOnIdle{assertEquals(2,continues);assertEquals(0,discards);assertEquals(0,saves)}
    }

    @Test fun existingSignedSpecialKeyboardViewportConstrainsTheDialogAndRestoresWithoutShrinkingItsFonts(){
        val context=RuntimeEnvironment.getApplication()
        ui.setContent{MaterialTheme{UnsavedChangesDialog({},{},{},error="HTTP_503 · "+"Draft is retained. ".repeat(100))}}
        val initial=ui.onNodeWithTag("unsaved-changes-viewport").fetchSemanticsNode().boundsInRoot
        try{
            ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(context,context.packageName,android.graphics.Rect(353,720,1656,1200))}
            ui.waitForIdle()
            val limited=ui.onNodeWithTag("unsaved-changes-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue("The same trusted keyboard path must reduce the dialog body budget",limited.height<initial.height)
            assertTextGeometry(1f)
            val panel=ui.onNodeWithTag("unsaved-changes-dialog").fetchSemanticsNode().boundsInRoot
            assertTrue(panel.top>=limited.top&&panel.bottom<=limited.bottom)
            ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(context,context.packageName,null)}
            ui.waitForIdle()
            assertEquals(initial,ui.onNodeWithTag("unsaved-changes-viewport").fetchSemanticsNode().boundsInRoot)
        }finally{ui.runOnIdle{com.no5.keyboard.SpecialKeyboardGeometry.publish(context,context.packageName,null)}}
    }

    @Test fun appOwnedFailureTextUsesTheConfirmedLanguageWhileTheDraftStaysOpen(){
        val context=RuntimeEnvironment.getApplication();val original=UiLanguage.current(context)
        val language=mutableStateOf("en")
        ui.setContent {
            UiLanguage.acceptConfirmed(context,language.value)
            key(language.value){MaterialTheme{UnsavedChangesDialog({},{},{},error="Änderung nicht übernommen")}}
        }
        try{
            for(tag in listOf("en","af","de")){
                ui.runOnIdle{language.value=tag}
                ui.onNodeWithTag("unsaved-changes-error").assertTextEquals(No5Text.text(tag,"Änderung nicht übernommen"))
                ui.onNodeWithTag("unsaved-changes-dialog").assertExists()
            }
        }finally{ui.runOnIdle{UiLanguage.acceptConfirmed(context,original)}}
    }

    private fun assertTextGeometry(fontScale:Float){
        val context=RuntimeEnvironment.getApplication()
        val stage=ui.onNodeWithTag("unsaved-changes-stage").fetchSemanticsNode().boundsInRoot
        val panel=ui.onNodeWithTag("unsaved-changes-dialog").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val title=ui.onNodeWithTag("unsaved-changes-title").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val body=ui.onNodeWithTag("unsaved-changes-body").fetchSemanticsNode().boundsInRoot
        val actions=ui.onNodeWithTag("unsaved-changes-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(stage.contains(panel.topLeft)&&stage.contains(panel.bottomRight))
        assertTrue(title.bottom<=body.top);assertTrue(body.bottom<=actions.top)
        val fits=mutableListOf<com.no5.ui.No5DialogTextFit>()
        ui.runOnUiThread {
            val view=ShadowDialog.getLatestDialog().window!!.decorView
            val image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            com.no5.ui.No5DialogDiagnostics.observe={fits+=it}
            try{view.draw(android.graphics.Canvas(image))}finally{com.no5.ui.No5DialogDiagnostics.observe=null;image.recycle()}
        }
        val heading=fits.last{it.tag=="unsaved-changes-title"}
        assertEquals("Approved D03 title font",35f*fontScale,heading.size,0f)
        assertTrue("Title ink must stay in its fixed native row",heading.bounds.top>=title.top&&heading.bounds.bottom<=title.bottom)
        assertTrue(heading.bounds.width()<=heading.available)
        for((tag,source)in listOf("unsaved-changes-save" to "Speichern","unsaved-changes-discard" to "Verwerfen","unsaved-changes-continue" to "Zurück")){
            val node=ui.onNodeWithTag(tag).assertIsDisplayed();val bounds=node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height/2>=48);assertTrue(panel.contains(bounds.topLeft)&&panel.contains(bounds.bottomRight))
            val draw=fits.last{it.tag==tag}
            assertEquals(No5Text.text(context,source),draw.text)
            assertEquals("Approved D03 button font must never shrink with the viewport",27f*fontScale,draw.size,0f)
            assertEquals(fontScale,draw.fontScale,0f)
            assertTrue("Actual native ink exceeds its action",draw.bounds.width()<=draw.available+.01f)
            assertTrue(draw.bounds.left>=bounds.left&&draw.bounds.right<=bounds.right&&draw.bounds.top>=bounds.top&&draw.bounds.bottom<=bounds.bottom)
        }

    }
}
