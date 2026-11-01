package com.no5.helm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.no5.preparation.ui.*
import com.no5.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapRouteDialogMatrixTest {
    @get:Rule val ui=createEmptyComposeRule()
    @After fun reset(){No5DialogDiagnostics.observe=null;RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
    private val route=RouteDocument("fixture","TEST · behalten",frozenListOf(RoutePoint(1.0,2.0,"a"),RoutePoint(3.0,4.0,"b")),revision=23)
    @Test fun routeFormsAndConfirmationsKeepDraftsAndFitEveryApprovedLanguageSizeAndTheme(){
        var epoch by mutableIntStateOf(0);var night by mutableStateOf(false)
        var state by mutableStateOf(RoutesState(coordinate=CoordinateEntry()))
        val fits=mutableListOf<No5DialogTextFit>();No5DialogDiagnostics.observe={fits+=it}
        Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{host->
            host.get().setContent{key(epoch){RoutesDialogs(state,{state=reduceRoutes(state,it)},night)}}
            for(font in listOf(1f,1.25f))for(language in listOf("de","en","af"))for(n in listOf(false,true)){
                ui.runOnIdle{RuntimeEnvironment.setFontScale(font);UiLanguage.acceptConfirmed(host.get(),language);night=n;state=RoutesState(draft=route,coordinate=CoordinateEntry());epoch++}
                focusHostInputWindow(host.get(),ui)
                val panel=ui.onNodeWithTag("route-coordinate").fetchSemanticsNode().boundsInRoot
                assertEquals(1000f,panel.width,.01f);assertEquals(220f,panel.top,.01f)
                ui.onNodeWithTag("route-point-apply").assertIsNotEnabled()
                ui.onNodeWithTag("route-point-name").performTextInput("RAW NAME")
                ui.onNodeWithTag("route-point-latitude").performTextInput("0")
                ui.onNodeWithTag("route-point-longitude").performTextInput("0")
                ui.onNodeWithTag("route-point-apply").assertIsEnabled()
                ui.runOnIdle{assertEquals("RAW NAME",state.coordinate!!.name);assertEquals("0",state.coordinate!!.latitude)}
                for(kind in listOf("coordinate","stop","review")){
                    ui.runOnIdle{
                        state=when(kind){
                            "stop"->RoutesState(stopDialog=true,active=route)
                            "review"->RoutesState(routes=frozenListOf(route),draft=route,shown=route,review=route,freshFix=true,mode=RouteNavigationMode.MANUAL_GUIDANCE)
                            else->state
                        }
                    }
                    if(kind!="coordinate")focusHostInputWindow(host.get(),ui)
                    ui.runOnIdle{
                        fits.clear()
                        val v=ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView
                        val output=File(System.getProperty("no5.renderOutputDir"),"route-dialogs-api${RuntimeEnvironment.getApiLevel()}").apply{mkdirs()}
                        val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
                        try{v.draw(Canvas(bitmap));File(output,"$kind-$language-$n-$font.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
                        fits.forEach{assertTrue("Fixed label clipped: $it",it.bounds.width()<=it.available+.01f)}
                    }
                    val tag=when(kind){"stop"->"route-stop";"review"->"route-start";else->"route-coordinate"}
                    val body=ui.onNodeWithTag("$tag-body").fetchSemanticsNode().boundsInRoot
                    val actions=ui.onNodeWithTag("$tag-actions").fetchSemanticsNode().boundsInRoot
                    assertTrue(body.bottom<=actions.top);assertTrue(actions.bottom<=1058f)
                    if(kind=="review"){
                        ui.onNodeWithTag("route-start-confirm").assertIsNotEnabled()
                        ui.onNodeWithTag("route-entry-0").performScrollTo().performClick()
                        ui.onNodeWithTag("route-start-confirm").assertIsEnabled()
                        ui.onNodeWithTag("route-start-confirm").performClick()
                        ui.runOnIdle{assertEquals(0,state.startRequest!!.entryLeg);assertNull(state.active)}
                    }
                }
            }
        }
    }
}
