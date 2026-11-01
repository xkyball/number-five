package com.no5.helm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ScrollView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.*
import com.no5.gateway.*
import com.no5.header.artwork.*
import com.no5.helm.ocean.*
import com.no5.ui.*
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
class HelmSettingsBodyMatrixTest{
    @get:Rule val ui=createComposeRule()
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun roots()=WindowInspector.getGlobalWindowViews().filter{it.isAttachedToWindow&&it.visibility==View.VISIBLE}
    private fun target(tag:String)=roots().flatMap{all(it)}.single{it.tag==tag}
    private fun tap(tag:String){ui.runOnIdle{assertTrue("Disabled $tag",target(tag).isEnabled);target(tag).performClick()};ui.waitForIdle()}
    private fun bounds(view:View)=Rect().also{assertTrue(view.getGlobalVisibleRect(it))}
    @Test fun everyReachableSettingsBodyAndPopupFitsAtNormalFont()=matrix(1f)
    @Test fun everyReachableSettingsBodyAndPopupFitsAtLargeFont()=matrix(1.25f)
    private fun matrix(font:Float){
        val h=HelmBodyMatrixSupport(ui,"settings-bodies-d03")
        val nativeFits=mutableListOf<No5WorkspaceTextFit>();val dialogFits=mutableListOf<No5DialogTextFit>()
        No5WorkspaceDiagnostics.observe={nativeFits+=it};No5DialogDiagnostics.observe={dialogFits+=it}
        var background=BackgroundSelection(BackgroundMode.IMAGE)
        val cases=listOf("overview","display","weather","background-image","background-animation","background-none","background-video","background-error","background-saving","gateway-null","gateway-ready","gateway-waiting","gateway-unavailable","gateway-closed")
        fun inspectNative(label:String){ui.runOnIdle{
            nativeFits.clear();dialogFits.clear()
            val image=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);val rowImage=Bitmap.createBitmap(1280,104,Bitmap.Config.ARGB_8888)
            try{roots().forEach{root->root.draw(Canvas(image));all(root).filter{it.tag?.toString()?.startsWith("no5-row-")==true}.forEach{it.draw(Canvas(rowImage))}}}finally{image.recycle();rowImage.recycle()}
            assertTrue("$label must draw actual native text",nativeFits.isNotEmpty()||dialogFits.isNotEmpty())
            nativeFits.filterNot{it.dynamic}.forEach{assertEquals(font,it.fontScale,0f);assertTrue("$label fixed native text clipped: $it",it.width<=it.available+.01f)}
            dialogFits.forEach{assertEquals(font,it.fontScale,0f);assertTrue("$label fixed detail text clipped: $it",it.bounds.width()<=it.available+.01f)}
        }}
        try{h.run(font,cases,nativeCanvasBody=true,before={case->
            background=BackgroundSelection(when(case){"background-animation"->BackgroundMode.ANIMATION;"background-none"->BackgroundMode.NONE;"background-video"->BackgroundMode.VIDEO;else->BackgroundMode.IMAGE})
            if(case=="background-error"||case=="background-saving"){
                background.select(BackgroundMode.NONE)
                background.beginSave()?.let{if(case=="background-error")background.finishSave(it,false)}
            }
        },after={case,frame->
            val label="${frame.language}/${frame.night}/$font/$case"
            if(case.startsWith("gateway")){
                for(tag in listOf("no5-action-gateway-test","no5-row-gateway-setup","no5-action-gateway-back"))ui.runOnIdle{
                    val b=bounds(target(tag));assertTrue("$label/$tag physical target",b.height()>=96&&b.width()>=96)}
                tap("no5-row-gateway-session-status");focusHelmTestForegroundWindow(ui);inspectNative("$label/details")
                ui.onNodeWithTag("gateway-status-details").assertIsDisplayed()
                ui.onNodeWithTag("Schließen").performClick();focusHelmTestForegroundWindow(ui)
            }else{
                val open=when{case=="display"->"no5-row-open-display-settings";case=="weather"->"no5-row-open-weather-settings";case.startsWith("background")->"no5-row-open-background-settings";else->null}
                if(open!=null)tap(open)
                inspectNative(label)
                if(case=="display")for(kind in listOf("speed","consumption","depth")){
                    tap("no5-row-settings-$kind-dropdown");focusHelmTestForegroundWindow(ui)
                    inspectNative("$label/$kind-choice");h.capture("${frame.language}-${frame.night}-font$font-$case-$kind-choice")
                    ui.onNodeWithTag("settings-choice-close").performClick();focusHelmTestForegroundWindow(ui)
                }
                if(case=="weather"){
                    tap("no5-row-settings-weather-dropdown");focusHelmTestForegroundWindow(ui)
                    ui.onNodeWithTag("settings-weather-OPEN_METEO").performClick()
                    inspectNative("$label/provider-choice")
                    ui.onNodeWithTag("settings-choice-close").performClick();focusHelmTestForegroundWindow(ui)
                    tap("no5-row-weather-privacy");focusHelmTestForegroundWindow(ui);inspectNative("$label/privacy")
                    ui.onNodeWithTag("Schließen").performClick();focusHelmTestForegroundWindow(ui)
                }
                ui.runOnIdle{
                    val actions=roots().flatMap{all(it)}.filter{it.tag?.toString()?.startsWith("no5-action-")==true}
                    val before=actions.associate{it.tag to bounds(it)}
                    roots().flatMap{all(it)}.filterIsInstance<ScrollView>().forEach{it.scrollTo(0,100000)}
                    actions.forEach{assertEquals("$label actions moved into scroll",before[it.tag],bounds(it));assertTrue(it.height>=96&&it.width>=96)}
                }
                inspectNative("$label/scrolled")
            }
        }){frame->
            ImeFormViewport(.5f,0f,130f,2000f,928f){
                if(frame.case.startsWith("gateway")){
                    val phase=when(frame.case){"gateway-ready"->GatewaySessionPhase.READY;"gateway-waiting"->GatewaySessionPhase.WAITING;"gateway-unavailable"->GatewaySessionPhase.UNAVAILABLE;else->GatewaySessionPhase.CLOSED}
                    LuminaGatewayConnectionScreen(.5f,frame.night,if(frame.case=="gateway-null")null else GatewaySessionStatus(phase,"https://observed-fixture.invalid:9443",reason="TLS_HOSTNAME",retryRemainingSeconds=17),
                        "OBSERVED_HELM · SOURCE · GOOD | WEATHER · SOURCE · UNAVAILABLE",{},{},{})
                }else SourceSettingsScreenWithHistory(.5f,frame.night,SourceConfig(),{},onOpenGatewayConnection={},bodyModifier=Modifier.fillMaxSize(),background=background,
                    backgroundDescription=No5Text.ui("Visual presentation"),videoDescription=No5Text.ui("No licensed footage included. Choose your own local file."),onChooseVideo={},onRetryVideo={},onSaveBackground={},onLanguageSelected={})
            }
        }}finally{No5WorkspaceDiagnostics.observe=null;No5DialogDiagnostics.observe=null}
    }
}
