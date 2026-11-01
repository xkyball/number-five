package com.no5.helm.ui

import android.graphics.*
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.no5.header.artwork.*
import com.no5.helm.chart.*
import com.no5.helm.library.*
import com.no5.preparation.ui.*
import com.no5.ui.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.security.MessageDigest

/** Real productive work views with deterministic data. No physical device or native renderer claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapM1RenderMatrixTest {
 @get:Rule val ui=createEmptyComposeRule()
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 @After fun reset(){No5WorkspaceDiagnostics.observe=null;RuntimeEnvironment.setFontScale(1f);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
 /** Canonical illustration data from the approved M1. Geometry and controls come from the productive owner scene.
  * This checks complete native template pixels; actual projection/availability is exercised separately. */
 private fun canonical(scene:M1Scene,id:String):M1Scene {
  var checkIndex=0
  val controls=scene.controls.map{v->var c=v.copy(enabled=true,action={})
   if(id=="cells"&&v.check){val index=checkIndex++;c=c.copy(selected=index==1,active=index==2)}
   if(id=="route-editor"&&v.key=="no5-field-route-name")c=c.copy(value=m1("Beispielroute","Example route","Voorbeeldroete"))
   if(id=="style"&&v.key=="no5-row-chart-style-land-relief")c=c.copy(label=m1("Relief: nicht unterstützt","Relief: not supported","Reliëf: nie ondersteun nie"),enabled=false)
   c
  }
  val painting: M1Painter.()->Unit = when(id){
   "cells"->{ {
    grid(32f,234f,1240f,520f,m1("SCHEMATISCHE KOORDINATENANSICHT","SCHEMATIC COORDINATE VIEW","SKEMATIESE KOÖRDINAATAANSIG"))
    val boxes=listOf(RectF(190f,340f,525f,590f),RectF(440f,430f,800f,690f),RectF(880f,280f,1170f,520f))
    val states=listOf(m1("Gespeichert","Stored","Gestoor"),m1("Entwurf gewählt","Draft selected","Konsep gekies"),m1("Aktiv","Active","Aktief"))
    boxes.forEachIndexed{i,r->rect(r.left,r.top,r.width(),r.height(),if(i==1)accent else muted,true);text("DEMO-${'A'+i}",r.left+16,r.top+45,30f,r.width()-32,true);text(states[i],r.left+16,r.top+91,24f,r.width()-32)}
    text(m1("Zellenausdehnungen ≠ exakte Abdeckung","Cell extents ≠ exact coverage","Selomvange ≠ presiese dekking"),50f,793f,25f,1200f,color=muted)
    for(i in 0..2){val y=344f+i*104;text("DEMO-${'A'+i}",1410f,y+47,30f,510f,true);text(states[i],1410f,y+84,24f,510f);line(1300f,y+100,1960f,y+100)}
    text("1 / 8",1580f,731f,27f,100f)
   } }
   "route-editor"->{ {
    grid(32f,234f,1240f,520f,m1("SCHEMATISCHE KOORDINATENANSICHT","SCHEMATIC COORDINATE VIEW","SKEMATIESE KOÖRDINAATAANSIG"))
    val points=listOf(PointF(220f,610f),PointF(550f,420f),PointF(840f,510f),PointF(1100f,330f))
    for(i in 1 until points.size)line(points[i-1].x,points[i-1].y,points[i].x,points[i].y,accent)
    points.forEachIndexed{i,q->p.color=accent;canvas.drawCircle(q.x,q.y,10f,p);text("${i+1}",q.x+18,q.y-15,30f,120f,true)}
    text("1 / 3",1580f,731f,27f,100f)
    text(m1("Koordinaten bleiben ohne Seekarte bearbeitbar.","Coordinates remain editable without a chart.","Koördinate bly sonder 'n seekaart bewerkbaar."),50f,793f,25f,1200f,color=muted)
   } }
   "classes"->{ {
    val codes=listOf("BOYLAT","LIGHTS","WRECKS","DEPARE")
    val names=listOf(m1("Laterale Tonne","Lateral buoy","Laterale boei"),m1("Lichter","Lights","Ligte"),m1("Wracks","Wrecks","Wrakke"),m1("Tiefengebiet","Depth area","Dieptegebied"))
    for(i in codes.indices){val y=344f+i*104;text(codes[i],150f,y+57,32f,250f,true);text(names[i],450f,y+57,32f,950f);text(if(i==2)m1("Ausgeblendet","Hidden","Versteek")else m1("Sichtbar","Visible","Sigbaar"),1550f,y+57,27f,400f);line(32f,y+100,1960f,y+100)}
    text("1 / 24",900f,884f,28f,200f)
   } }
   else->scene.paint
  }
  return scene.copy(controls=controls,paint=painting,notice=null,keyboard=null)
 }
 @Test fun productiveWorkViewsFitAllApprovedLanguagesThemesFontsAndStates(){
  val repo=generateSequence(File(System.getProperty("user.dir")?:".")){it.parentFile}.first{File(it,"map/ui-shell").isDirectory}
  val output=File(repo,"map/verification/m1-20261024/product-views").apply{mkdirs()};val manifest=JSONArray()
  val parity=JSONArray()
  var page by mutableStateOf("cells");var phase by mutableStateOf("ready");var night by mutableStateOf(false);var epoch by mutableIntStateOf(0)
  val fits=mutableListOf<No5WorkspaceTextFit>();No5WorkspaceDiagnostics.observe={fits+=it}
  val cells=(0..7).map{ChartCell("DEMO-${'A'+it}",1,0,"demo.000","20261024",emptyMap(),selectionExtent=if(it==7)null else ChartSelectionExtent(42.0+it*.2,42.5+it*.2,-72.0,-71.0))}
  val route=RouteDocument("draft","Beispielroute",FrozenList.copyOf((0..6).map{RoutePoint(42.0+it*.2,-72.0+it*.3,name="${'A'+it}")}))
  Robolectric.buildActivity(ComponentActivity::class.java).apply{get().setTheme(android.R.style.Theme_Material_NoActionBar)}.setup().visible().windowFocusChanged(true).use{h->
   h.get().setContent{key(epoch){No5ControlTheme(night){Box(Modifier.fillMaxWidth().height(if(page=="style")600.dp else 404.dp)){
    val notice=when(phase){"error"->m1("Übernahme fehlgeschlagen. Entwurf bleibt erhalten.","Apply failed. Draft is retained.","Toepassing het misluk. Konsep bly behoue.");"waiting"->m1("Wird bestätigt …","Waiting for confirmation…","Wag vir bevestiging…");else->null}
    when(page){
     "cells"->MapRegionWorkspace(ChartPackage("p","Demo",0,cells),chartRegions(if(phase=="empty")emptyList()else cells),setOf("DEMO-B"),setOf("DEMO-C"),"",{},phase=="waiting",false,phase!="blocked",false,100,false,false,{}, {},if(phase=="blocked")m1("Dienst nicht verfügbar","Service unavailable","Diens onbeskikbaar")else"",notice,{}, {},null,{}, {},emptySet())
     "route-editor"->MapRoutesWorkspace(RoutesState(draft=if(phase=="empty")route.copy(points=frozenListOf())else route,dirty=true,saveSupported=phase!="blocked",message=notice,saveRequest=if(phase=="waiting")RouteSaveRequest(1,route)else null),night,{}, {},phase!="blocked",{}, {}, {})
     "style"->MapStyleWorkspace(ChartPresentation("glacier"),ChartPresentation(),phase=="waiting"||phase=="blocked",notice,{}, {}, {}, {})
     else->MapIndividualClasses(ChartObjectFilterState(true,ChartObjectFilters(category=if(phase=="blocked")S52DisplayCategory.STANDARD else S52DisplayCategory.CUSTOM,hiddenClasses=setOf("WRECKS"))),if(phase=="empty")emptyMap()else linkedMapOf("BOYLAT" to "Lateral buoy","LIGHTS" to "Lights","WRECKS" to "Wrecks","DEPARE" to "Depth area"),phase=="waiting",notice,night,{}, {}, {})
    }
   }}}}
   for(font in listOf(1f,1.25f))for(language in listOf("de","en","af"))for(n in listOf(false,true))for(id in listOf("cells","route-editor","style","classes"))for(state in listOf("ready","empty","blocked","error","waiting")){
    ui.runOnIdle{RuntimeEnvironment.setFontScale(font);UiLanguage.acceptConfirmed(h.get(),language);night=n;page=id;phase=state;epoch++};ui.waitForIdle()
    ui.runOnIdle{
     fits.clear();val view=all(h.get().window.decorView).single{it.tag=="map-m1-workspace"}
     assertEquals(2000,view.width);assertEquals(808,view.height)
     val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(image))
     all(view).filter{it!==view&&it.tag!=null}.forEach{c->assertTrue("Target too small ${c.tag}",c.width>=96&&c.height>=96);assertTrue("Target outside body ${c.tag}",c.top>=0&&c.bottom<=808)}
     fits.filterNot{it.dynamic}.forEach{assertTrue("Fixed label clips $id/$state/$language/$font: $it",it.width<=it.available+.01f)}
     if(state=="ready"){
      val reference=BitmapFactory.decodeFile(File(repo,"design/app-unification-20261019/map-menu-M1/assets/$id-ready-$language-${if(n)"night"else"day"}-font${if(font==1f)100 else 125}.png").path)
      val regions=mutableListOf(Rect(32,132,1700,229),Rect(1708,130,1963,231))
      if(id=="cells")regions+=listOf(Rect(1299,233,1962,331),Rect(352,823,1658,922))
      if(id=="route-editor")regions+=Rect(352,823,1658,922)
      if(id=="classes")regions+=listOf(Rect(31,233,1274,331),Rect(1299,233,1962,331))
      if(id=="style")regions+=listOf(Rect(713,551,1316,650),Rect(1329,551,1962,650),Rect(352,823,1658,922))
      if(id=="style")regions+=listOf(Rect(713,233,1962,525),Rect(31,345,684,443),Rect(31,457,684,555),Rect(31,569,684,667))
      for(region in regions){
       val expected=Bitmap.createBitmap(reference,region.left,region.top,region.width(),region.height())
       val actual=Bitmap.createBitmap(image,region.left,region.top-130,region.width(),region.height())
       assertTrue("M1 approved static region differs: $id $language $font $n $region",actual.sameAs(expected))
       expected.recycle();actual.recycle()
      }
      val field=MapM1View::class.java.getDeclaredField("scene").apply{isAccessible=true}
      val fixed=MapM1View(h.get());fixed.render(canonical(field.get(view) as M1Scene,id),n)
      fixed.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(808,View.MeasureSpec.EXACTLY));fixed.layout(0,0,2000,808)
      val replay=Bitmap.createBitmap(2000,808,Bitmap.Config.ARGB_8888);fixed.draw(Canvas(replay))
      val expectedBody=Bitmap.createBitmap(reference,0,130,2000,808)
      val parityDir=File(output.parentFile,"template-parity").apply{mkdirs()}
      File(parityDir,"$id-$language-${if(n)"night"else"day"}-font${if(font==1f)100 else 125}.png").outputStream().use{replay.compress(Bitmap.CompressFormat.PNG,100,it)}
      assertTrue("Full M1 native template differs: $id $language $font $n",replay.sameAs(expectedBody))
      expectedBody.recycle();replay.recycle();reference.recycle();parity.put("$id-$language-$n-$font")
     }
     val name="$id-$state-$language-${if(n)"night"else"day"}-font${if(font==1f)100 else 125}.png"
     val file=File(output,name);file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
     manifest.put(JSONObject().put("path",name).put("sha256",MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}))
    }
   }
  }
  File(output,"MANIFEST.json").writeText(JSONObject().put("status","PASS").put("scope","240 productive native body captures: actual controllers' UI adapters, fixed data, API35; no physical IME/device acceptance").put("fullTemplateM1Parity",parity).put("templateScope","Productive scene geometry and native controls with approved canonical illustration data; not a chart projection or availability-policy proof").put("frames",manifest).toString(2))
 }
}
