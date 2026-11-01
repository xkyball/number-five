package com.no5.helm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import com.no5.domain.*
import com.no5.ui.UiLanguage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedFaderNamePixelParityTest {
    @get:Rule val ui=createComposeRule()
    private data class Frame(val language:String="de",val night:Boolean=false,val font:Float=1f,val epoch:Int=0)
    @Test fun actualNameEditorMatchesItsFrozenCompletePanel(){
        val root=File("../../design/app-unification-20261019")
        val delegate=MockAudioSource(now={10_000L}).also{it.loadFixture(AudioFixture.DEMO)}
        val base=delegate.state.value
        val channels=listOf(AudioChannel.MASTER,AudioChannel.FRONT,AudioChannel.REAR,AudioChannel.SUB)
        val catalog=channels.mapIndexed{i,c->base.dsp.faderControls[i].copy(gatewayId=c,physicalIndex=i,label=c.name)}
        val source=object:AudioSourcePort by delegate{override val state=MutableSourceFeed(base.copy(dsp=base.dsp.copy(faderControls=catalog)))}
        val proposal=JSONObject(File(root,"proposal.json").readText())
        val controller=AudioNativeController(automaticTicker=false,automaticVerification=false,source=source)
        controller.selectKnobFader(controller.faderControls.single{it.gatewayId==AudioChannel.REAR})
        val frame=mutableStateOf(Frame())
        ui.setContent{
            val f=frame.value;UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),f.language)
            val names=when(f.language){"en"->listOf("MAIN","FRONT","REAR","SUB");"af"->listOf("HOOF","VOOR","AGTER","SUB");else->listOf("HAUPT","VORNE","HINTEN","SUB")}
            val settings=AudioPresentationSettings(listOf(AudioChannel.MASTER,AudioChannel.FRONT,AudioChannel.REAR,AudioChannel.SUB).zip(names).associate{it.first to AudioFaderPresentation(if(it.first==AudioChannel.REAR)"Cockpit"else it.second,true)})
            CompositionLocalProvider(LocalDensity provides Density(2f,f.font)){
                MaterialTheme{Box(Modifier.size(1000.dp,600.dp)){
                    val underlay=remember(f.night){BitmapFactory.decodeFile(File(proposal.getString("baselineRoot"),"AUDIO-${if(f.night)"night"else"day"}.png").absolutePath)}
                    val backdrop=remember{com.no5.header.artwork.No5WorkspaceBackdrop(RuntimeEnvironment.getApplication().assets)}
                    DisposableEffect(underlay){onDispose{underlay.recycle()}};DisposableEffect(backdrop){onDispose{backdrop.close()}}
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()){
                        val n=drawContext.canvas.nativeCanvas;val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                        n.drawBitmap(underlay,0f,0f,p);p.color=if(f.night)android.graphics.Color.argb(241,0,0,0)else android.graphics.Color.argb(241,246,249,250)
                        n.drawRect(0f,130f,2000f,1058f,p);backdrop.draw(n,f.night)
                    }
                    key(f.epoch){
                    FaderAssignmentPanel(.5f,f.night,controller,settings,false,null,{},{},{_,_,_->},Modifier.offset(300.dp,120.dp).size(400.dp,310.dp))
                }}}
            }
        }
        val out=File(System.getProperty("no5.renderOutputDir"),"approved-fader-name").apply{mkdirs()};val result=JSONArray()
        try{
            for(language in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f)){
                ui.runOnIdle{frame.value=Frame(language,night,font,frame.value.epoch+1)};ui.waitForIdle();focusHelmTestForegroundWindow(ui)
                ui.onNodeWithTag("sound-setup-alias-REAR").performClick();ui.waitForIdle();focusHelmTestForegroundWindow(ui)
                val name="fader-name-$language-${if(night)"night"else"day"}-font$font.png"
                val expected=BitmapFactory.decodeFile(File(root,"renders/$name").absolutePath);assertNotNull(expected)
                var changed=0
                ui.runOnIdle{
                    val view=org.robolectric.shadows.ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView
                    val actual=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val underlay=BitmapFactory.decodeFile(File(proposal.getString("baselineRoot"),"AUDIO-${if(night)"night"else"day"}.png").absolutePath)
                    val c=Canvas(actual);c.drawBitmap(underlay,0f,0f,null);underlay.recycle()
                    val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=if(night)android.graphics.Color.argb(241,0,0,0)else android.graphics.Color.argb(241,246,249,250)}
                    c.drawRect(0f,130f,2000f,1058f,paint);view.draw(c)
                    assertEquals(2000,actual.width);assertEquals(1200,actual.height)
                    for(y in 240 until 640)for(x in 440 until 1560)if(actual.getPixel(x,y)!=expected.getPixel(x,y))changed++
                    if(changed>0)File(out,"actual-$name").outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}
                    actual.recycle()
                };expected.recycle();result.put(JSONObject().put("language",language).put("night",night).put("font",font).put("differentPixels",changed))
            }
        }finally{ui.runOnIdle{controller.dispose()};UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de");File(out,"RESULTS.json").writeText(result.toString(2))}
        assertTrue("Complete approved fader panel mismatch: $result",(0 until result.length()).all{result.getJSONObject(it).getInt("differentPixels")==0})
    }
}
