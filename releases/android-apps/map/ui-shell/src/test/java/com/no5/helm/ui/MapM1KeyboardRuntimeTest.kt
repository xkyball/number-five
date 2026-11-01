package com.no5.helm.ui

import android.graphics.*
import android.view.View
import android.widget.EditText
import com.no5.header.artwork.No5WorkspaceView
import com.no5.ui.UiLanguage
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapM1KeyboardRuntimeTest {
 private val repo=generateSequence(File(System.getProperty("user.dir"))){it.parentFile}.first{File(it,"design/app-unification-20261019/map-menu-M1/MODEL.json").isFile}
 @After fun reset(){RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
 private fun fixture(id:String,title:String):M1Scene {
  val normal=buildList {
   add(m1Back{});add(M1Control("keyboard-input",m1("Suche / Name","Search / name","Soek / naam"),1300f,234f,660f,value="",change={}))
   for(i in 0..2)add(M1Control("keyboard-row-$i",m1("Gewählte Zeile","Selected row","Gekose reël")+" · ${i+1}",1300f,344f+i*104,660f,action={}))
  }
  val labels=when(id){"cells"->listOf(m1("Zurück","Back","Terug"),m1("Auswahlwerkzeuge","Selection tools","Keusegereedskap"),m1("Aktivieren","Activate","Aktiveer"));"route-editor"->listOf(m1("Zurück","Back","Terug"),m1("Einpassen","Fit","Pas in"),m1("Speichern","Save","Stoor"));else->listOf(m1("Zurück","Previous","Vorige"),"1 / 24",m1("Weiter","Next","Volgende"))}
  val footer=m1Actions(*labels.mapIndexed{i,l->No5WorkspaceView.Action(l,key="keyboard-action-$i",action={})}.toTypedArray())
  return M1Scene(title,normal+footer,keyboard=M1Keyboard("keyboard-input",normal.filter{it.key.startsWith("keyboard-row")},
   M1Control("previous",m1("Zurück","Previous","Vorige"),1300f,674f,260f),M1Control("next",m1("Weiter","Next","Volgende"),1700f,674f,260f),footer,3,{}))
 }
 @Test fun productiveKeyboardReflowMatchesAll36FrozenK1BodiesExactly(){
  val pages=JSONObject(File(repo,"design/app-unification-20261019/map-menu-M1/MODEL.json").readText()).getJSONArray("pages")
  val output=File(repo,"map/verification/m1-20261024/k1-runtime").apply{mkdirs()}
  var mismatches=0
  for(id in listOf("cells","route-editor","classes"))for(language in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f)){
   UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language);RuntimeEnvironment.setFontScale(font)
   val title=(0 until pages.length()).map{pages.getJSONObject(it)}.first{it.getString("id")==id}.getJSONArray("title").getString(listOf("de","en","af").indexOf(language))
   val scene=m1KeyboardScene(fixture(id,title),772f)
   scene.controls.forEach{assertTrue(it.w>=96&&it.h>=96);assertTrue(it.y>=130&&it.y+it.h<=772f)}
   val view=MapM1View(RuntimeEnvironment.getApplication());view.render(scene,night)
   view.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(642,View.MeasureSpec.EXACTLY));view.layout(0,0,2000,642)
   val actual=Bitmap.createBitmap(2000,642,Bitmap.Config.ARGB_8888);view.draw(Canvas(actual))
   val name="$id-$language-${if(night)"night"else"day"}-font${if(font==1f)100 else 125}.png"
   val frozen=BitmapFactory.decodeFile(File(repo,"design/app-unification-20261019/map-menu-M1-K1/assets/$name").path)
   val expected=Bitmap.createBitmap(frozen,0,130,2000,642)
   if(!actual.sameAs(expected)){mismatches++;File(output,name).outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}}
   actual.recycle();expected.recycle();frozen.recycle()
  }
  assertEquals("K1 productive body must remain pixel-identical; references are immutable",0,mismatches)
 }
 @Test fun shrinkingPreservesNativeFieldInstanceTextCursorAndCurrentCallbacks(){
  val view=MapM1View(RuntimeEnvironment.getApplication());var received="";var source=fixture("cells","Cells")
  source=source.copy(controls=source.controls.map{if(it.key=="keyboard-input")it.copy(value="DEMO",change={received=it})else it})
  view.render(source,false);val field=view.findViewWithTag<EditText>("keyboard-input");field.setSelection(2)
  view.render(m1KeyboardScene(source,772f),false)
  assertSame(field,view.findViewWithTag<EditText>("keyboard-input"));assertEquals("DEMO",field.text.toString());assertEquals(2,field.selectionStart)
  field.setText("LAST");assertEquals("LAST",received)
  assertEquals(3,m1KeyboardCapacity(772f));assertEquals(2,m1KeyboardCapacity(772f,true));assertEquals(1,m1KeyboardCapacity(600f))
 }
 @Test fun localClassNamesCoverEveryBundledCatalogEntry(){
  val file=File(repo,"map/chart-engine/src/main/assets/s57objectclasses.csv")
  val pattern=Regex("^\\d+,\"[^\"]*\",([^,]+),")
  val codes=file.readLines().drop(1).mapNotNull{pattern.find(it)?.groupValues?.get(1)}
  assertTrue(codes.size>200);assertEquals(emptyList<String>(),codes.filterNot{MapClassLabels.contains(it)})
 }
}
