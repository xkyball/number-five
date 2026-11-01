package com.no5.helm.ui

import android.graphics.*
import android.view.View
import org.json.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/** Separate K1 design fixture. Does not activate a new keyboard layout in the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapM1KeyboardProposalTest {
 @org.junit.After fun resetLanguage(){RuntimeEnvironment.setFontScale(1f);com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),"de")}
 @org.junit.Ignore("Approved K1 references are frozen; never regenerate during product tests")
 @Test fun renderFixedDockKeyboardProposal(){
  val repo=generateSequence(File(System.getProperty("user.dir"))){it.parentFile}.first{File(it,"design/app-unification-20261019/map-menu-M1/MODEL.json").isFile}
  val original=File(repo,"design/app-unification-20261019/map-menu-M1")
  val out=File(repo,"design/app-unification-20261019/map-menu-M1-K1");File(out,"assets").mkdirs()
  val pages=JSONObject(File(original,"MODEL.json").readText()).getJSONArray("pages")
  val manifest=JSONArray()
  for(id in listOf("cells","route-editor","classes"))for(language in listOf("de","en","af"))for(night in listOf(false,true))for(font in listOf(1f,1.25f)){
   com.no5.ui.UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language);RuntimeEnvironment.setFontScale(font)
   val context=RuntimeEnvironment.getApplication();val title=(0 until pages.length()).map{pages.getJSONObject(it)}.first{it.getString("id")==id}.getJSONArray("title").getString(listOf("de","en","af").indexOf(language))
   val controls=buildList{
    add(m1Back{});add(M1Control("keyboard-input",m1("Suche / Name","Search / name","Soek / naam"),40f,234f,1130f,value="",change={}))
    add(M1Control("keyboard-prev",m1("Zurück","Previous","Vorige"),1200f,234f,370f,action={}));add(M1Control("keyboard-next",m1("Weiter","Next","Volgende"),1590f,234f,370f,action={}))
    for(i in 0..2)add(M1Control("keyboard-row-$i",m1("Gewählte Zeile","Selected row","Gekose reël")+" · ${i+1}",40f,344f+i*104,1920f,action={}))
    val labels=when(id){"cells"->listOf(m1("Zurück","Back","Terug"),m1("Auswahlwerkzeuge","Selection tools","Keusegereedskap"),m1("Aktivieren","Activate","Aktiveer"));"route-editor"->listOf(m1("Zurück","Back","Terug"),m1("Einpassen","Fit","Pas in"),m1("Speichern","Save","Stoor"));"style"->listOf(m1("Zurück","Back","Terug"),m1("Anwenden","Apply","Pas toe"));else->listOf(m1("Zurück","Previous","Vorige"),"1 / 24",m1("Weiter","Next","Volgende"))}
    val w=(1303f-(labels.size-1)*16)/labels.size
    labels.forEachIndexed{i,label->add(M1Control("keyboard-action-$i",label,353+i*(w+16),660f,w,action={}))}
   }
   for(c in controls){assertTrue(c.w>=96&&c.h>=96);assertTrue(c.y>=130&&c.y+c.h<=772)}
   for(i in controls.indices)for(j in i+1 until controls.size){val a=controls[i];val b=controls[j];assertFalse("Overlapping K1 controls",a.x<b.x+b.w&&a.x+a.w>b.x&&a.y<b.y+b.h&&a.y+a.h>b.y)}
   val view=MapM1View(context);view.render(M1Scene(title,controls,clipControls=true),night);view.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(642,View.MeasureSpec.EXACTLY));view.layout(0,0,2000,642)
   val theme=if(night)"night"else"day";val size=if(font==1f)100 else 125
   val b=BitmapFactory.decodeFile(File(original,"assets/$id-ready-$language-$theme-font$size.png").absolutePath).copy(Bitmap.Config.ARGB_8888,true)
   val canvas=Canvas(b);canvas.save();canvas.translate(0f,130f);view.draw(canvas);canvas.restore()
   val p=Paint().apply{color=if(night)Color.rgb(25,0,0)else Color.rgb(221,226,229)};canvas.drawRect(0f,772f,2000f,1200f,p)
   val painter=M1Painter(canvas,context,night,font);painter.text(m1("TASTATURBEREICH · PLATZHALTER","KEYBOARD AREA · PLACEHOLDER","SLEUTELBORDAREA · PLEKHOUER"),370f,850f,29f,1300f,true)
   painter.text(m1("Dock bleibt bei y938; die Tastatur verdeckt es vorübergehend.","Dock stays at y938; the keyboard temporarily covers it.","Dok bly by y938; die sleutelbord bedek dit tydelik."),370f,910f,27f,1300f)
   p.color=Color.BLACK;canvas.drawOval(RectF(-156f,948.4f,254f,1366.4f),p);canvas.drawOval(RectF(1735f,948.4f,2151f,1366.4f),p)
   val name="$id-$language-$theme-font$size.png";val file=File(out,"assets/$name");file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
   manifest.put(JSONObject().put("path","assets/$name").put("sha256",MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}))
  }
  File(out,"MANIFEST.json").writeText(JSONObject().put("revision","M1-K1").put("status","PENDING_APPROVAL").put("scope","Only work-page layout above actual IME; dock remains fixed at y938, covered by the keyboard placeholder").put("realImeTest","NOT_RUN").put("frames",manifest).toString(2))
 }
}
