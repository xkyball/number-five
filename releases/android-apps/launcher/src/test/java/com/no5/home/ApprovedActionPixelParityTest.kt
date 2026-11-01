package com.no5.home

import android.app.Activity
import android.graphics.*
import android.view.View
import android.view.ViewGroup
import com.no5.header.artwork.No5WorkspaceView
import com.no5.ui.UiLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Exact unchanged D03 action-board component, including the entire outside focus contour.
 * The approved design environment is API35/native Skia at 2000x1200; no masks/tolerances. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="en-rUS-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedActionPixelParityTest {
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    @Test fun allFourActionStatesMatchTheFrozenDesignInEveryLanguageThemeAndFont(){
        val reference=File("../design/app-unification-20261019/renders")
        val output=File(System.getProperty("no5.renderOutputDir"),"approved-action-pixels").apply{mkdirs()}
        for(language in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f)){
            UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language);RuntimeEnvironment.setFontScale(font)
            Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true).use{host->
                val labels=listOf("Normal","Gedrückt","Fokus","Ausgewählt")
                val workspace=No5WorkspaceView(host.get(),night,"","",emptyList(),{},false,font,
                    labels.mapIndexed{i,text->No5WorkspaceView.Action(text,selected=i==3,key="state-$i",action={})})
                host.get().setContentView(workspace)
                workspace.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(928,View.MeasureSpec.EXACTLY));workspace.layout(0,0,2000,928)
                val actions=labels.indices.map{i->all(workspace).single{it.tag=="no5-action-state-$i"}}
                val parent=actions.first().parent as View;parent.layout(360,180,1640,276)
                actions.forEachIndexed{i,v->v.layout(i*330,0,i*330+290,96)}
                actions[1].isPressed=true;actions[2].isFocusableInTouchMode=true;assertTrue(actions[2].requestFocus())
                assertTrue(actions.all{it.hasWindowFocus()})
                val frame=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888)
                frame.eraseColor(if(night)Color.BLACK else 0xfff6f9fa.toInt())
                val canvas=Canvas(frame);canvas.translate(0f,130f);workspace.draw(canvas)
                val name="interaction-states-$language-${if(night)"night"else"day"}-font$font.png"
                val expected=BitmapFactory.decodeFile(File(reference,name).absolutePath);assertNotNull("Missing approved D03 $name",expected)
                val actual=Bitmap.createBitmap(frame,354,304,1292,108)
                val golden=Bitmap.createBitmap(expected,354,304,1292,108)
                File(output,"actual-$name").outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}
                var different=0;for(y in 0 until actual.height)for(x in 0 until actual.width)if(actual.getPixel(x,y)!=golden.getPixel(x,y))different++
                assertEquals("Full action component differs from approved D03: $name",0,different)
                expected.recycle();actual.recycle();golden.recycle();frame.recycle()
            }
        }
        RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")
    }
    @Test fun workspaceComposesExactlyOverTheFrozenAuthorizedPrimaryInput(){
        val root=File("../design/app-unification-20261019")
        val model=org.json.JSONObject(File(root,"proposal.json").readText())
        val scenes=model.getJSONArray("screens")
        val spec=(0 until scenes.length()).map{scenes.getJSONObject(it)}.single{it.getString("id")=="home-maintenance"}
        val output=File(System.getProperty("no5.renderOutputDir"),"approved-workspace-pixels").apply{mkdirs()}
        for((lang,language)in listOf("de","en","af").withIndex())for(night in listOf(false,true))for(font in listOf(1f,1.25f)){
            val theme=if(night)"night"else"day"
            UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language);RuntimeEnvironment.setFontScale(font)
            Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true).use{host->
                val data=spec.getJSONArray("rows")
                val rows=(0 until data.length()).map{i->val row=data.getJSONObject(i)
                    No5WorkspaceView.Row(row.getJSONArray("label").getString(lang),row.optJSONArray("detail")?.getString(lang),
                        row.optJSONArray("value")?.getString(lang),rawLabel=true,rawDetail=true,rawValue=true,key="fixture-$i",action={})}
                val view=No5WorkspaceView(host.get(),night,spec.getJSONArray("title").getString(lang),spec.getJSONArray("subtitle").getString(lang),rows,{},showBack=spec.optString("back").let{it.isNotBlank()&&it!="null"},fontScale=font)
                host.get().setContentView(view)
                view.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(928,View.MeasureSpec.EXACTLY));view.layout(0,0,2000,928)
                // A neutral touch-state reference; keyboard focus is independently checked above.
                host.get().window.decorView.isFocusableInTouchMode=true;host.get().window.decorView.requestFocus()
                val original=BitmapFactory.decodeFile(File(model.getString("homeBaselineRoot"),"landing-$language-$theme-font$font-h1200.png").absolutePath)
                assertNotNull(original)
                val actual=original.copy(Bitmap.Config.ARGB_8888,true);original.recycle()
                val c=Canvas(actual);c.translate(0f,130f);view.draw(c)
                val golden=BitmapFactory.decodeFile(File(root,"renders/home-maintenance-$language-$theme-font$font.png").absolutePath)
                var changed=0
                for(y in 130 until 1058)for(x in 0 until 2000)if(actual.getPixel(x,y)!=golden.getPixel(x,y))changed++
                File(output,"actual-$language-$theme-$font.png").outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}
                assertEquals("Entire 2000x928 workspace; fixed approved underlay; no mask/tolerance: $language/$theme/$font",0,changed)
                actual.recycle();golden.recycle()
            }
        }
        RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")
    }

    @Test fun everyApprovedWorkspaceUsesTheExactFrozenNativeContentPattern(){
        val root=File("../design/app-unification-20261019")
        val model=org.json.JSONObject(File(root,"proposal.json").readText())
        val list=model.getJSONArray("screens")
        val scenes=(0 until list.length()).map{list.getJSONObject(it)}.filter{it.getString("kind")=="workspace"&&it.getString("app")!="CONTROL"}
        val output=File(System.getProperty("no5.renderOutputDir"),"approved-workspace-patterns").apply{mkdirs()}
        val results=org.json.JSONArray()
        for((lang,language)in listOf("de","en","af").withIndex())for(night in listOf(false,true))for(font in listOf(1f,1.25f)){
            val theme=if(night)"night"else"day"
            UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language);RuntimeEnvironment.setFontScale(font)
            Robolectric.buildActivity(Activity::class.java).setup().visible().windowFocusChanged(true).use{host->
                for(spec in scenes){
                    val data=spec.getJSONArray("rows")
                    val rows=(0 until data.length()).map{i->val row=data.getJSONObject(i)
                        No5WorkspaceView.Row(row.getJSONArray("label").getString(lang),row.optJSONArray("detail")?.getString(lang),
                            row.optJSONArray("value")?.getString(lang),rawLabel=true,rawDetail=true,rawValue=true,
                            selected=row.optBoolean("selected"),control=row.optString("control"),key="fixture-$i",action={})}
                    val dataActions=spec.optJSONArray("actions")?:org.json.JSONArray()
                    val actions=(0 until dataActions.length()).map{i->val action=dataActions.getJSONObject(i)
                        No5WorkspaceView.Action(action.getJSONArray("label").getString(lang),enabled=action.optBoolean("enabled",true),selected=action.optBoolean("selected"),key="action-$i",action={})}
                    val view=No5WorkspaceView(host.get(),night,spec.getJSONArray("title").getString(lang),spec.getJSONArray("subtitle").getString(lang),rows,{},showBack=spec.optString("back").let{it.isNotBlank()&&it!="null"},fontScale=font,actions=actions)
                    host.get().setContentView(view)
                    view.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(928,View.MeasureSpec.EXACTLY));view.layout(0,0,2000,928)
                    host.get().window.decorView.isFocusableInTouchMode=true;host.get().window.decorView.requestFocus()
                    val original=if(spec.getString("app")=="HOME")File(model.getString("homeBaselineRoot"),"landing-$language-$theme-font$font-h1200.png") else File(model.getString("baselineRoot"),"${spec.optString("base","AUDIO")}-$theme.png")
                    val input=BitmapFactory.decodeFile(original.absolutePath);assertNotNull(input)
                    val actual=input.copy(Bitmap.Config.ARGB_8888,true);input.recycle()
                    val c=Canvas(actual);c.translate(0f,130f);view.draw(c)
                    val id=spec.getString("id");val golden=BitmapFactory.decodeFile(File(root,"renders/$id-$language-$theme-font$font.png").absolutePath);assertNotNull(golden)
                    // The entire intrinsic content pattern ends at the common MAP dock boundary.
                    // Header/footer and physical hardware overlays are separate frozen components.
                    // The existing full 2000x928 HOME comparison above remains unchanged.
                    val pixels=IntArray(2000*808);val expected=IntArray(pixels.size)
                    actual.getPixels(pixels,0,2000,0,130,2000,808);golden.getPixels(expected,0,2000,0,130,2000,808)
                    var changed=0;for(i in pixels.indices)if(pixels[i]!=expected[i])changed++
                    results.put(org.json.JSONObject().put("id",id).put("language",language).put("night",night).put("font",font).put("differentPixels",changed))
                    if(changed>0&&language=="de"&&!night&&font==1f)File(output,"actual-$id.png").outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}
                    actual.recycle();golden.recycle()
                }
            }
        }
        File(output,"RESULTS.json").writeText(results.toString(2))
        RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")
        val failures=(0 until results.length()).map{results.getJSONObject(it)}.filter{it.getInt("differentPixels")!=0}
        assertTrue("Approved native workspace content mismatches: "+failures.take(12).joinToString(),failures.isEmpty())
    }

}
