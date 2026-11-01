package com.no5.helm.ui
import android.graphics.*
import android.view.View
import com.no5.helm.chart.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import org.json.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="de-w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorldUnderlayProposalTest {
 @Test fun renderPendingW1WithoutChangingProductOrHistoricalReferences() {
  val repo=generateSequence(File(System.getProperty("user.dir"))){it.parentFile}.first{File(it,"map/preparation/world-underlay-W1/SOURCE.json").isFile}
  val out=File(repo,"map/preparation/world-underlay-W1");val images=File(out,"images").apply{mkdirs()}
  val frozen=File(out,"APPROVED.json").exists()
  val region=ChartCameraBounds.from(ChartNavigationBounds(1,listOf(ChartCellExtent("full-catalog",-50.0,-10.0,-30.0,70.0))))!!
  val frames=JSONArray()
  for(night in listOf(false,true))for(angle in listOf(0,30,90)) {
   val view=WorldUnderlayPrototype(RuntimeEnvironment.getApplication())
   val style=if(night)"redwater-focus-default"else"lagoon-sculpt-r04"
   val xml=File(repo,"map/chart-engine/src/main/assets/presentation/$style/presentation.xml")
   val doc=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
   val colors=doc.getElementsByTagName("color")
   for(i in 0 until colors.length){val e=colors.item(i) as org.w3c.dom.Element
    val c=Color.rgb(e.getAttribute("r").toInt(),e.getAttribute("g").toInt(),e.getAttribute("b").toInt())
    when(e.getAttribute("name")){"LANDA"->view.landColor=c;"DEPDW"->view.waterColor=c}}
   view.camera=region.overview(ChartViewport(width=2000,height=1070,rotationRadians=Math.toRadians(angle.toDouble())))
   view.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1070,View.MeasureSpec.EXACTLY));view.layout(0,0,2000,1070)
   val bitmap=Bitmap.createBitmap(2000,1070,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
   val land=IntArray(2000*1070);bitmap.getPixels(land,0,2000,0,0,2000,1070)
   assertTrue(land.any{it==view.landColor});assertTrue(land.any{it==view.waterColor})
   val name="${if(night)"red-night"else"day"}-$angle.png";val file=File(images,name)
   if(frozen){
    val expected=BitmapFactory.decodeFile(file.absolutePath);assertNotNull(expected)
    val pixels=IntArray(2000*1070);expected.getPixels(pixels,0,2000,0,0,2000,1070)
    assertArrayEquals("Frozen W1 pixels: $name",pixels,land);expected.recycle()
   }else file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
   bitmap.recycle()
   frames.put(JSONObject().put("file","images/$name").put("sha256",MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}))
  }
  if(!frozen)File(out,"MANIFEST.json").writeText(JSONObject().put("status","PENDING_APPROVAL").put("revision","W1")
   .put("scope","Native Android land-background design only; no nautical composition or device-performance acceptance")
   .put("productEnabled",false).put("frames",frames).toString(2)+"\n")
 }
}
