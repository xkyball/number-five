package com.no5.helm

import com.no5.ui.No5Text
import com.no5.helm.numeric.*
import android.util.LruCache
import android.content.Context
import android.graphics.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.asAndroidPath
import org.json.JSONArray
import org.json.JSONObject
import com.no5.domain.DepthHistorySnapshot
import com.no5.domain.DisplayUnits
import com.no5.domain.HelmState
import com.no5.domain.SpeedExtremaSnapshot
import com.no5.helm.ocean.BackgroundMode
import com.no5.helm.ocean.OceanBackgroundRenderer
import com.no5.helm.ocean.OceanFrame
import kotlin.math.*

/** Native Canvas rendering of extracted, CSS-resolved source geometry. No HTML engine. */
class HelmScene(private val context: Context, val night: Boolean,
    private val backgroundRenderer: OceanBackgroundRenderer = OceanBackgroundRenderer(context).also {
        if (!night) it.prepare(BackgroundMode.IMAGE)
    }) {
    private val headerBackdrop = com.no5.header.artwork.No5HeaderBackdrop(context.assets)
    private val root = JSONObject(context.assets.open("helm/scene_${if (night) "night" else "day"}.json").bufferedReader().use { it.readText() })
    private val layers = root.getJSONArray("layers")
    private val texts = root.getJSONArray("texts")
    private val gradients = root.optJSONObject("gradients") ?: JSONObject()
    private val clips = root.optJSONObject("clips") ?: JSONObject()
    private val speedDepthInstrument = SpeedDepthInstrument(context,night)
    private val pathCache = HashMap<String, Path>()
    private val transformCache = HashMap<String, Matrix>()
    private val gradientCache = HashMap<String, Shader>()
    private data class NodePaintKey(val style:JSONObject,val kind:String,val opacity:Float)
    private val nodePaintCache = HashMap<NodePaintKey,Paint>()
    private val typefaces = mapOf(
        "hero" to context.resources.getFont(R.font.barlow_condensed_extrabold),
        "label" to context.resources.getFont(R.font.barlow_condensed_semibold),
        "unit" to context.resources.getFont(R.font.barlow_condensed_regular)
    )
    private data class TextPaintKey(val size:Float,val family:String,val color:Int,val stretch:Float)
    private data class MeasureKey(val value:String,val face:Typeface?,val size:Float,val stretch:Float)
    private data class NumericStyleKey(val size:Float,val family:String,val stretch:Float)
    private val numericStyles=LruCache<NumericStyleKey,NativeNumericText.NumericStyle>(128)
    private fun numericStyle(size:Float,family:String,stretch:Float,paint:Paint):NativeNumericText.NumericStyle {
        val key=NumericStyleKey(size,family,stretch)
        return numericStyles.get(key)?:NativeNumericText.style(paint).also{numericStyles.put(key,it)}
    }
    private val textPaints=LruCache<TextPaintKey,Paint>(128)
    private data class NumberKey(val node:JSONObject,val field:String,val fallback:Float)
    private data class ColorKey(val source:String,val fallback:Int)
    private val numbers=LruCache<NumberKey,Float>(2048)
    private val colors=LruCache<ColorKey,Int>(128)
    private val widths=LruCache<MeasureKey,Float>(512)
    private val glyphBounds=LruCache<MeasureKey,HelmGlyphMetrics>(512)
    internal fun textCacheStats()=listOf(textPaints.size(),widths.size(),glyphBounds.size(),
        textPaints.missCount(),widths.missCount(),glyphBounds.missCount())
    private fun measuredWidth(value:String,paint:Paint):Float {
        val key=MeasureKey(value,paint.typeface,paint.textSize,paint.textScaleX)
        return widths.get(key) ?: paint.measureText(value).also { if(value.length<=256)widths.put(key,it) }
    }
    private val rpmReadout = NativeRpmReadout(typefaces.getValue("hero"))
    private val ink = Color.parseColor(if (night) "#d40000" else "#071022")
    private val secondary = Color.parseColor(if (night) "#b00000" else "#122653")
    private val accent = Color.parseColor(if (night) "#ff1a0a" else "#005daf")
    private val background = Color.parseColor(if (night) "#000000" else "#f6f9fa")

    private val frameColors=NativeHelmLayout.framePalette(night).map(Color::parseColor)
    private val fuelFramePaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader=LinearGradient(0f,702f,0f,775f,frameColors.take(3).toIntArray(),
            floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
    } }
    private val navFramePaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader=LinearGradient(0f,NativeHelmLayout.FOOTER_TOP,0f,NativeHelmLayout.HEIGHT,
            frameColors.take(3).toIntArray(),floatArrayOf(0f,.48f,1f),Shader.TileMode.CLAMP)
    } }
    private val frameAccentPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color=frameColors[3];style=Paint.Style.STROKE;strokeWidth=3f
        strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    } }

    fun draw(canvas: Canvas, values: HelmRenderValues,
        displayState:HelmState=HelmState.unavailable(),
        depthHistory:DepthHistorySnapshot=DepthHistorySnapshot.empty(),
        units:DisplayUnits=DisplayUnits(), backgroundMode:BackgroundMode=BackgroundMode.IMAGE,
        reducedMotion:Boolean=false, oceanFrame:OceanFrame=OceanFrame()) {
        drawBackground(canvas, backgroundMode, reducedMotion, oceanFrame)
        drawForeground(canvas, values, displayState, depthHistory, units)
    }

    /** Retained independently from telemetry so an instrument frame never redraws the shader. */
    fun drawBackground(canvas: Canvas, backgroundMode: BackgroundMode,
        reducedMotion: Boolean, oceanFrame: OceanFrame, externalOcean: Boolean = false) {
        canvas.drawColor(background)
        if (!night && backgroundMode != BackgroundMode.NONE) drawOcean(canvas, backgroundMode, reducedMotion, oceanFrame, externalOcean)
    }

    /** Transparent instrument pass. The background layer owns both the stage and its media. */
    fun drawForeground(canvas: Canvas, values: HelmRenderValues,
        displayState: HelmState = HelmState.unavailable(),
        depthHistory: DepthHistorySnapshot = DepthHistorySnapshot.empty(),
        units: DisplayUnits = DisplayUnits(),speedExtrema:SpeedExtremaSnapshot=SpeedExtremaSnapshot()) {
        drawStatic(canvas, units, values)
        drawSlow(canvas, values)
        drawBand(canvas,SpeedDepthInstrumentGeometry.reading(displayState,units,values.quality))
        drawMotion(canvas, values, displayState, depthHistory, units, speedExtrema)
        drawStaticOverlay(canvas,units)
    }

    private val bands by lazy { ApprovedHelmBands(this) }
    /** The approved instrument crop is translated once, preserving every native pixel. */
    private inline fun instruments(canvas:Canvas,draw:()->Unit) {
        canvas.save();canvas.clipRect(0f,130f,2000f,710f)
        try { draw() } finally { canvas.restore() }
    }
    fun drawStatic(canvas:Canvas,units:DisplayUnits=DisplayUnits(),sourceValues:HelmRenderValues?=null) {
        instruments(canvas) {
            compiled.draw(canvas,HelmRenderValues(),Domain.STATIC)
            staticTexts.forEach { it.draw(canvas,sourceValues?:HelmRenderValues()) }
            speedDepthInstrument.drawStatic(canvas,units)
            drawHeadingReference(canvas);drawEngineLabels(canvas)
        }
        headerBackdrop.draw(canvas, night)
        drawHeaderStatic(canvas);bands.static(canvas,units);drawNavigation(canvas,No5Text.ui("HELM"))
    }
    fun drawStaticOverlay(canvas:Canvas,units:DisplayUnits) { instruments(canvas) {
        compiled.draw(canvas,HelmRenderValues(),Domain.STATIC_OVERLAY);speedDepthInstrument.drawStaticOverlay(canvas,units)
    } }
    fun drawSlow(canvas:Canvas,values:HelmRenderValues) {
        instruments(canvas) {
            compiled.draw(canvas,values,Domain.SLOW);slowTexts.forEach { it.draw(canvas,values) };drawEngineQualityLabels(canvas,values)
        }
        bands.slow(canvas,values);drawHeaderValues(canvas,values)
    }
    fun drawMotion(canvas:Canvas,values:HelmRenderValues,state:HelmState,
        history:DepthHistorySnapshot,units:DisplayUnits,extrema:SpeedExtremaSnapshot) {
        val reading=SpeedDepthInstrumentGeometry.reading(state,units,values.quality)
        drawMotionValues(canvas,values,reading)
        drawInstrumentTelemetry(canvas,reading,units,extrema,state.quality.simulated)
        drawHistory(canvas,history,reading.depthStatus,history.endEpochMs)
    }
    fun drawMotionValues(canvas:Canvas,values:HelmRenderValues,reading:SpeedDepthInstrumentGeometry.Reading) {
        instruments(canvas) {
            compiled.draw(canvas,values,Domain.MOTION);motionTexts.forEach { it.draw(canvas,values) }
            speedDepthInstrument.drawSpeed(canvas,reading);drawHeadingMotion(canvas,values);drawEngineValues(canvas,values)
        }
        bands.motion(canvas,values)
    }
    fun drawInstrumentTelemetry(canvas:Canvas,reading:SpeedDepthInstrumentGeometry.Reading,units:DisplayUnits,
        extrema:SpeedExtremaSnapshot,simulated:Boolean) { instruments(canvas) { speedDepthInstrument.drawTelemetry(canvas,reading,units,extrema,simulated) } }
    fun drawHistory(canvas:Canvas,history:DepthHistorySnapshot,status:com.no5.domain.TelemetryStatus,now:Long) {
        instruments(canvas) { speedDepthInstrument.drawHistoryLayer(canvas,history,status,now) }
    }
    fun drawBand(canvas:Canvas,reading:SpeedDepthInstrumentGeometry.Reading) { instruments(canvas) { speedDepthInstrument.drawBand(canvas,reading) } }
    private val motionTextIds=setOf("secondary-port-trim","secondary-stbd-trim","deck-cog")
    private val staticTexts by lazy { htmlTexts.filter { it.id !in DYNAMIC_TEXT_IDS && it.id !in motionTextIds }.map(::compileText) }
    private val slowTexts by lazy { htmlTexts.filter { it.id in DYNAMIC_TEXT_IDS && it.id !in motionTextIds }.map(::compileText) }
    private val motionTexts by lazy { htmlTexts.filter { it.id in motionTextIds }.map(::compileText) }

    fun drawStaged(canvas: Canvas, page: String) {
        canvas.drawColor(background)
        drawChrome(canvas, page, HelmRenderValues(), No5Text.ui("UNAVAILABLE"))
        text(canvas, page, 1000f, 330f, 72f, "hero", ink, "center")
        text(canvas, "NATIVE PORT STAGED", 1000f, 450f, 34f, "label", secondary, "center")
        val detail = when(page) {
            "AUDIO" -> "Domain guards and gateway interface included. Native audio controls are not implemented."
            "SONAR" -> "1024-bin / 2048-ping domain model included. Native echogram screen is not implemented."
            "DASHBOARD" -> "Chart engine and dashboard are not selected or implemented."
            else -> "Production gateway setup is not implemented. This app is a local simulation."
        }
        text(canvas, detail, 1000f, 525f, 28f, "label", secondary, "center", maxWidth=1740f)
        text(canvas, No5Text.ui("Tap HELM to return to the instruments."), 1000f, 640f, 28f, "label", accent, "center")
    }

    /** Shared AUDIO/SONAR safety rail and canonical navigation. Never paints the body. */
    fun drawChrome(canvas:Canvas,page:String,values:HelmRenderValues,status:String=No5Text.ui("UNAVAILABLE")) {
        canvas.save()
        canvas.clipRect(0f,0f,NativeHelmLayout.WIDTH,NativeHelmLayout.HEADER_BOTTOM)
        drawSharedHeader(canvas,page,values,status)
        canvas.restore()
        drawNavigation(canvas,page)
    }

    internal fun drawChromeBackground(canvas:Canvas) = headerBackdrop.drawBackground(canvas,night)

    internal fun drawChromeRetainedHeader(canvas:Canvas,values:HelmRenderValues,status:String) {
        canvas.save();canvas.clipRect(0f,0f,NativeHelmLayout.WIDTH,NativeHelmLayout.HEADER_BOTTOM)
        headerBackdrop.drawBackground(canvas,night)
        drawSharedHeader(canvas,"AUDIO",values,status,withBackdrop=false)
        canvas.restore()
    }

    internal fun drawChromeRetainedFooter(canvas:Canvas) = drawNavigation(canvas,"AUDIO")

    internal fun drawChromeOutline(canvas:Canvas) = headerBackdrop.drawOutline(canvas,night)

    internal fun drawChromeForeground(canvas:Canvas,page:String,values:HelmRenderValues,status:String) {
        canvas.save();canvas.clipRect(0f,0f,NativeHelmLayout.WIDTH,NativeHelmLayout.HEADER_BOTTOM)
        headerBackdrop.drawOutline(canvas,night)
        drawSharedHeader(canvas,page,values,status,withBackdrop=false)
        canvas.restore()
        drawNavigation(canvas,page)
    }

    private fun drawSharedHeader(c:Canvas,page:String,v:HelmRenderValues,status:String,withBackdrop:Boolean=true){
        val structural=Color.parseColor(if(night)"#700000" else "#526b82")
        if(withBackdrop)headerBackdrop.draw(c, night)
        val seam=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=structural;style=Paint.Style.STROKE;strokeWidth=2f}
        text(c,"NO 5",35f,62f,64f,"hero",ink,scaleX=1.7f)
        text(c,"STELLENBOSCH",38f,85f,22f,"label",secondary,scaleX=1.28f)
        fun safety(x:Float,label:String,value:String,unit:String,field:String,width:Float=120f,marker:Int?=null){
            if(marker!=null)c.drawRect(x,13f,x+5f,29f,Paint().apply{color=marker})
            text(c,No5Text.ui(label),x+if(marker==null)0f else 13f,28f,20f,"label",secondary)
            if(unit=="RPM")rpmReadout.draw(c,value,x+width*1.16f,77f,ink,
                TabularRpmLayout.Anchor.RIGHT,NativeRpmReadout.Size.HEADER)
            else text(c,value,x+width*1.16f,77f,44f,"hero",ink,"right",1.16f,width*1.16f,numericSlots=NumericFormats.speed)
            text(c,unit,x+if(unit=="kn")142f else 154f,78f,21f,"label",secondary)
            text(c,v.text["header-quality-$field"]?:"${No5Text.ui(if(v.demo) "SIMULATED" else "SOURCE")} · ${No5Text.ui(v.quality)}",x,101f,16f,"label",secondary,maxWidth=210f)
        }
        safety(540f,"PORT RPM",v.text["rpm-port"]?:"—","RPM","portRpm",marker=Color.parseColor(if(night)"#d40000"else"#D62A20"))
        safety(780f,"STBD RPM",v.text["rpm-stbd"]?:"—","RPM","starboardRpm",marker=Color.parseColor(if(night)"#d40000"else"#008E4F"))
        safety(1020f,"SOG",v.text["hud-sog"]?:"—",v.text["hud-sog-unit"]?:"kn","speed",110f)
        val statusText=if(status=="SIMULATED")No5Text.ui("SIMULATED DATA") else No5Text.ui(status.ifBlank{"UNAVAILABLE"})
        text(c,statusText,1225f,48f,18f,"label",secondary,maxWidth=290f)
        text(c,v.text["header-source-label"] ?: No5Text.ui("SOURCE STATUS"),1225f,70f,18f,"label",secondary,maxWidth=290f)

    }

    private fun drawOcean(canvas: Canvas, mode: BackgroundMode, reducedMotion: Boolean, frame: OceanFrame, externalOcean: Boolean = false) {
        canvas.save()
        canvas.clipRect(0f,130f,2000f,710f)
        clip(canvas, "media-aperture")
        canvas.drawColor(Color.parseColor("#b9def6"))
        if (!externalOcean) backgroundRenderer.draw(canvas, mode, night, reducedMotion, frame)
        canvas.restore()
    }

    internal enum class Domain { STATIC, SLOW, MOTION, STATIC_OVERLAY }
    internal fun interface DrawOp { fun draw(canvas:Canvas, values:HelmRenderValues) }
    internal data class CompiledOp(val domain:Domain, val op:DrawOp)
    internal class CompiledHelmScene(val ops:List<CompiledOp>) {
        val overlayOps=ops.filter { it.domain==Domain.STATIC_OVERLAY }
        val staticDrawOps=ops.filter { it.domain==Domain.STATIC }
        val slowOps=ops.filter { it.domain==Domain.SLOW }
        val motionOps=ops.filter { it.domain==Domain.MOTION }
        fun draw(c:Canvas,v:HelmRenderValues) { ops.forEach { it.op.draw(c,v) } }
        fun draw(c:Canvas,v:HelmRenderValues,domain:Domain) {
            val selected=when(domain){Domain.STATIC->staticDrawOps;Domain.SLOW->slowOps;Domain.MOTION->motionOps;Domain.STATIC_OVERLAY->overlayOps}
            selected.forEach { it.op.draw(c,v) }
        }
    }
    // Eager compilation below runs only after all scene-owned paints and geometry are initialized.
    private val compiled by lazy {
        val ops=ArrayList<CompiledOp>()
        for(i in 0 until layers.length()) {
            val layer=layers.getJSONObject(i)
            if(layer.optString("id") in setOf("frame-nav","nav"))continue
            val matrix=transformMatrix(layer.optString("transform"))
            val nodes=layer.optJSONArray("nodes") ?: continue
            for(j in 0 until nodes.length())ops+=compileNode(nodes.getJSONObject(j),matrix)
        }
        CompiledHelmScene(ops.toList())
    }
    private fun compileNode(node:JSONObject,parent:Matrix=Matrix(),alpha:Float=1f):List<CompiledOp> {
        val style=node.optJSONObject("style")?:JSONObject()
        if(style.optString("visibility")=="hidden" || style.optString("display")=="none")return emptyList()
        val attrs=node.optJSONObject("attrs")?:JSONObject()
        val tag=node.optString("tag").lowercase();val id=node.optString("id",attrs.optString("id"))
        if(id in setOf("heading-reference","heading-baseline","hud-course-left","hud-course-right") ||
            tag in setOf("defs","lineargradient","stop","clippath"))return emptyList()
        val opacity=alpha*(style.optString("opacity","1").toFloatOrNull()?:1f)
        val local=Matrix()
        val icon=NativeHelmLayout.iconPlacement(id)
        if(icon!=null){local.setTranslate(icon.x,icon.y);local.preScale(icon.scale,icon.scale)}
        else local.set(transformMatrix(attrs.optString("transform")))
        val matrix=Matrix(parent).apply{preConcat(local)}
        val clipName=attrs.optString("clip-path",style.optString("clip-path"))
        val clipPath=if(clipName.startsWith("url("))compiledClip(clipName.substringAfter("#").substringBefore(")"))else null
        val domain=when(id){"port-trim-track","stbd-trim-track"->Domain.STATIC_OVERLAY;"port-trim-fill","stbd-trim-fill","rpm-active-port","rpm-active-stbd"->Domain.MOTION
            "deck-fuel-fill","deck-fuel-marker"->Domain.SLOW;else->Domain.STATIC}
        val binding=when(id){"port-trim-fill"->1;"stbd-trim-fill"->2;"deck-fuel-fill"->3;"deck-fuel-marker"->4
            "rpm-active-port"->5;"rpm-active-stbd"->6;else->0}
        val x=attrs.f("x");val y=NativeHelmLayout.panelTop(id,attrs.f("y"))
        val width=attrs.f("width");val height=NativeHelmLayout.panelHeight(id,attrs.f("height"))
        val rx=attrs.f("rx");val ry=attrs.f("ry",rx)
        val cx=attrs.f("cx");val cy=attrs.f("cy");val radius=attrs.f("r")
        val path=when(tag){
            "path"->parsePath(NativeHelmLayout.structuralPath(id,attrs.optString("d")))
            "rect"->Path().apply{addRoundRect(RectF(x,y,x+width,y+height),rx,ry,Path.Direction.CW)}
            "circle"->Path().apply{addCircle(cx,cy,radius,Path.Direction.CW)}
            "ellipse"->Path().apply{addOval(RectF(cx-rx,cy-ry,cx+rx,cy+ry),Path.Direction.CW)}
            "line"->Path().apply{moveTo(attrs.f("x1"),attrs.f("y1"));lineTo(attrs.f("x2"),attrs.f("y2"))}
            "polygon","polyline"->{val n=numberList(attrs.optString("points"));Path().apply{
                if(n.size>=2){moveTo(n[0],n[1]);for(i in 2 until n.size-1 step 2)lineTo(n[i],n[i+1]);if(tag=="polygon")close()}}}
            else->null
        }
        val fill=paint(style,"fill",opacity);val stroke=paint(style,"stroke",opacity)
        val measure=if(binding>=5)PathMeasure(path,false)else null
        val dynamicPath=if(binding!=0)Path()else null
        val own:DrawOp?=if(path!=null) DrawOp { c,v ->
            if(binding!=4 || v.fuelAvailable) {
                val drawn=if(binding==0)path else dynamicPath!!.apply {
                    rewind()
                    when(binding){
                        1->addRoundRect(RectF(x,y,x+292*v.portTrim/100f,y+height),rx,ry,Path.Direction.CW)
                        2->{val w=292*v.stbdTrim/100f;addRoundRect(RectF(1864-w,y,1864f,y+height),rx,ry,Path.Direction.CW)}
                        3->addRoundRect(RectF(x,y,x+455*v.fuel/100f,y+height),rx,ry,Path.Direction.CW)
                        4->addCircle(432+455*v.fuel/100f,cy,radius,Path.Direction.CW)
                        else->measure!!.getSegment(0f,measure.length*((if(binding==5)v.portRpm else v.stbdRpm)/7000f).coerceIn(0f,1f),this,true)
                    }
                }
                if(binding<5)fill?.let{c.drawPath(drawn,it)}
                stroke?.let{c.drawPath(drawn,it)}
            }
        } else if(tag=="text") {
            val content=node.optString("text")
            val size=style.optString("font-size","24").removeSuffix("px").toFloatOrNull()?:24f
            val family=if(style.optString("font-weight")=="800")"hero" else "label"
            val anchor=when(style.optString("text-anchor")){"middle"->"center";"end"->"right";else->"left"}
            val position=NativeHelmLayout.rpmLabelAnchor(id,attrs.f("x"),attrs.f("y"))
            val col=color(style.optString("fill"),secondary)
            DrawOp { c,_ -> text(c,No5Text.ui(content),position.x,position.baseline,size,family,col,anchor,opacity=opacity) }
        } else null
        val result=ArrayList<CompiledOp>()
        own?.let { op->result+=CompiledOp(domain,DrawOp{c,v->c.save();c.concat(matrix);clipPath?.let(c::clipPath);op.draw(c,v);c.restore()}) }
        val children=node.optJSONArray("children")
        if(children!=null)for(i in 0 until children.length()) {
            val childOps=compileNode(children.getJSONObject(i),matrix,opacity)
            // Parent clips stay in the parent's coordinate system, before descendant transforms.
            if(clipPath==null)result+=childOps else {
                val inverse=Matrix();check(matrix.invert(inverse))
                childOps.forEach { child ->
                result+=CompiledOp(child.domain,DrawOp{c,v->c.save();c.concat(matrix);c.clipPath(clipPath)
                    c.concat(inverse);child.op.draw(c,v);c.restore()})
                }
            }
        }
        return result
    }

    private fun paint(s:JSONObject,kind:String,alpha:Float):Paint?{
        val value=s.optString(kind,if(kind=="fill")"black" else "none")
        if(value=="none"||value.isEmpty())return null
        return nodePaintCache.getOrPut(NodePaintKey(s,kind,alpha)){Paint(Paint.ANTI_ALIAS_FLAG).apply{
            style=if(kind=="fill")Paint.Style.FILL else Paint.Style.STROKE
            color=color(value,secondary);this.alpha=(255*alpha*(s.optString("$kind-opacity","1").toFloatOrNull()?:1f)).roundToInt().coerceIn(0,255)
            if(value.startsWith("url("))shader=gradient(value.substringAfter("#").substringBefore(")"))
            strokeWidth=s.optString("stroke-width","2").removeSuffix("px").toFloatOrNull()?:2f
            strokeCap=when(s.optString("stroke-linecap")){"butt"->Paint.Cap.BUTT;"square"->Paint.Cap.SQUARE;else->Paint.Cap.ROUND}
            strokeJoin=when(s.optString("stroke-linejoin")){"bevel"->Paint.Join.BEVEL;"miter"->Paint.Join.MITER;else->Paint.Join.ROUND}
        }}
    }
    private fun gradient(id:String):Shader?{
        gradientCache[id]?.let{return it}
        val g=gradients.optJSONObject(id)?:return null
        val stops=g.optJSONArray("stops")?:return null
        val colors=IntArray(stops.length());val positions=FloatArray(stops.length())
        for(i in 0 until stops.length()){
            val stop=stops.optJSONArray(i)
            if(stop!=null){positions[i]=stop.optDouble(0).toFloat();colors[i]=color(stop.optString(1),ink)}
            else {val o=stops.getJSONObject(i);positions[i]=o.f("offset");colors[i]=color(o.optString("color"),ink)}
        }
        if(colors.size<2)return null
        return LinearGradient(g.f("x1"),g.f("y1"),g.f("x2",1f),g.f("y2"),colors,positions,Shader.TileMode.CLAMP).also{gradientCache[id]=it}
    }
    /** Snapshot of the ORIGINAL authored Bezier aperture; no hand-copied substitute geometry. */
    fun oceanAperture(): Path = Path().also { path ->
        clips.optJSONObject("media-aperture")?.let { collectClip(it,path,Matrix()) }
    }

    private val compiledClips=HashMap<String,Path>()
    private fun compiledClip(id:String):Path=compiledClips.getOrPut(id) {
        Path().also { path->clips.optJSONObject(id)?.let{collectClip(it,path,Matrix())} }
    }
    private fun clip(c:Canvas,id:String){val path=compiledClip(id);if(!path.isEmpty)c.clipPath(path)}
    private fun collectClip(n:JSONObject,out:Path,parent:Matrix){
        val a=n.optJSONObject("attrs")?:JSONObject();val m=Matrix(parent);m.preConcat(transformMatrix(a.optString("transform")))
        val p=when(n.optString("tag").lowercase()){
            "path"->Path(parsePath(a.optString("d")))
            "rect"->Path().apply{addRoundRect(RectF(a.f("x"),a.f("y"),a.f("x")+a.f("width"),a.f("y")+a.f("height")),a.f("rx"),a.f("ry",a.f("rx")),Path.Direction.CW)}
            else->null
        };if(p!=null){p.transform(m);out.addPath(p)}
        val ch=n.optJSONArray("children")?:JSONArray();for(i in 0 until ch.length())collectClip(ch.getJSONObject(i),out,m)
    }

    private data class HtmlText(val id:String,val fallback:String,val family:String,
        val placement:NativeHelmLayout.TextPlacement,val x:Float,val baseline:Float,val color:Int,
        val field:String,val anchor:String,val boxPosition:Boolean,val width:Float,val logicalWidth:Float,
        val consumption:NativeHelmLayout.TextBox?,val evidence:NativeHelmLayout.TextBox?,
        val weatherLabel:NativeHelmLayout.TextBox?,val engineLabel:NativeHelmLayout.TextBox?,
        val valueUnit:NativeHelmLayout.TextBox?,val slots:NumericSlots?)
    private val htmlTexts by lazy { (0 until texts.length()).mapNotNull { prepareHtmlText(texts.getJSONObject(it)) } }
    private fun prepareHtmlText(n:JSONObject):HtmlText? {
        val id=n.optString("id")
        if(n.optString("visibility")=="hidden" || n.optString("display")=="none")return null
        if(id.startsWith("hud-heading-")||id in setOf("hud-sog","hud-sog-label","hud-sog-unit","hud-depth","hud-depth-label","hud-depth-unit","header-brand","header-home","header-tagline","header-time","header-date","header-temp","header-temp-unit","header-wifi-state","header-data-state","rpm-state-port","rpm-state-stbd"))return null
        // Weather units are measured and rendered together with their values below.
        if(id.startsWith("deck-unit-") && "deck-${id.removePrefix("deck-unit-")}" in NativeHelmLayout.weatherUnits)return null
        if(id in setOf("deck-unit-totalFlow", "deck-unit-efficiency", "deck-unit-tripDistance"))return null
        val weight=n.optInt("fontWeight",600);val family=if(weight>=700||n.optString("fontFamily").contains("Hero"))"hero" else if(weight>=600)"label" else "unit"
        val sourceSize=n.f("fontSize",24f)
        val sourceLineHeight=n.f("lineHeight",sourceSize*1.1f).let{if(it<4)it*sourceSize else it}
        val placement=NativeHelmLayout.placeText(id,n.f("y"),sourceSize,sourceLineHeight,n.f("scaleX",1f))
        val size=placement.fontSize;val x=n.f("x")
        val paint=Paint().apply{textSize=size;typeface=typefaces[family]}
        val fm=paint.fontMetrics
        val baseline=placement.top+(placement.lineHeight-(fm.descent-fm.ascent))/2-fm.ascent
        val col=color(n.optString("color"),ink)
        val field=id.substringAfterLast("-")
        val primaryWidth=root.optJSONObject("numericSlots")?.optJSONObject("primaryWidths")?.optDouble(id,Double.NaN)?.toFloat()
        val width=n.f("width",Float.MAX_VALUE)
        return HtmlText(id,n.optString("text"),family,placement,x,baseline,col,field,n.optString("anchor","left"),
            n.optString("positioning")=="box",n.f("width"),
            if(id=="deck-fuelPct"||id=="deck-remainingL")88f else primaryWidth?.takeIf{it.isFinite()}?:width,
            if(id=="deck-totalFlow")NativeHelmLayout.TextBox(1108f,779f,1295f,824f)else NativeHelmLayout.tripValueUnitBox(id),
            NativeHelmLayout.evidenceBox(id),NativeHelmLayout.weatherLabelBox(id),NativeHelmLayout.engineLabelBox(id),
            NativeHelmLayout.valueUnitBox(id),NumericFormats.helm(id))
    }
    /** Select layout/binding at compile time; draw never branches on authored id/style/tag. */
    private fun compileText(t:HtmlText):DrawOp {
        val id=t.id;val size=t.placement.fontSize;val family=t.family;val col=t.color
        val sx=t.placement.scaleX;val baseline=t.baseline
        fun bound(draw:(Canvas,HelmRenderValues,String)->Unit)=DrawOp { c,v->
            val value=v.text[id]?:No5Text.ui(t.fallback)
            if(value.isNotBlank())draw(c,v,value)
        }
        t.consumption?.let { box ->
            val unitId="deck-unit-${id.removePrefix("deck-")}"
            val fallback=if(id=="deck-tripDistance")"NM" else "L/h"
            val unitSize=if(id=="deck-totalFlow")26f else 22f
            val slots=t.slots?:NumericSlots(5,1)
            return bound { c,v,value->boundedValueUnit(c,value,v.text[unitId]?:fallback,box,size,unitSize,
                family,"label",col,secondary,sx,slots) }
        }
        t.evidence?.let { box ->return bound { c,_,value->boundedText(c,value,box,size,family,col,"right",ellipsize=true) } }
        (t.weatherLabel?:t.engineLabel)?.let { box->return bound { c,_,value->boundedText(c,value,box,size,family,col,"center",sx) } }
        t.valueUnit?.let { box ->
            val weatherUnit=NativeHelmLayout.weatherUnits[id]
            val unit=weatherUnit?:mapOf("coolant" to "°C","oil" to "bar","voltage" to "V").getValue(t.field)
            val unitSize=if(weatherUnit!=null)24.6f else size
            val unitFamily=if(weatherUnit!=null)"label" else family
            val unitColor=if(weatherUnit!=null)secondary else col
            val slots=t.slots?:NumericSlots(3,1)
            return bound { c,_,value->boundedValueUnit(c,value.removeSuffix(unit).trim(),unit,box,size,unitSize,
                family,unitFamily,col,unitColor,sx,slots) }
        }
        if(id.startsWith("secondary-") && t.field=="trim") {
            val unit="%";val numberWidth=72f;val unitWidth=measuredWidth(unit,textPaint(size,family,col))
            val left=if(t.anchor=="right")t.x-(numberWidth+8+unitWidth)*sx else t.x
            val slots=NumericSlots(3,stale=true)
            return bound { c,_,value->
                text(c,value.removeSuffix(unit).trim(),left+numberWidth*sx,baseline,size,family,col,"right",sx,numberWidth*sx,numericSlots=slots)
                text(c,unit,left+(numberWidth+8)*sx,baseline,size,family,col,scaleX=sx)
            }
        }
        if(id=="secondary-port-flow" || id=="secondary-stbd-flow") {
            val x=if(id=="secondary-port-flow")283f else 1752f
            return bound { c,_,value->text(c,value,x,baseline,size,family,col,"right",sx,185f,numericSlots=t.slots) }
        }
        val x=t.x+if(t.boxPosition)when(t.anchor){"right"->t.width;"center"->t.width/2;else->0f}else 0f
        val outline=if(!night&&id in setOf("hud-sog","hud-depth"))1.25f else 0f
        val following=when(id){"deck-latitude","deck-longitude"->NumericSlots(2,3,stale=true);"deck-tripDuration"->NumericSlots(2,stale=true);else->t.slots}
        return bound { c,_,value->text(c,value,x,baseline,size,family,col,t.anchor,sx,t.logicalWidth*sx,
            outline=outline,numericSlots=t.slots,numericFollowing=following) }
    }

    private val headingHalo = Color.rgb(241,248,252)
    private val headingPaths by lazy {
        val found = mutableListOf<Path>()
        fun visit(node:JSONObject) {
            if (node.optString("id") in setOf("heading-reference","heading-baseline"))
                found += parsePath(node.getJSONObject("attrs").getString("d"))
            val children=node.optJSONArray("children") ?: JSONArray()
            for(i in 0 until children.length())visit(children.getJSONObject(i))
        }
        for(i in 0 until layers.length()) {
            val nodes=layers.getJSONObject(i).optJSONArray("nodes") ?: JSONArray()
            for(j in 0 until nodes.length())visit(nodes.getJSONObject(j))
        }
        found
    }
    private val headingEdge=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND}
    private fun drawHeadingReference(c:Canvas) {
        val edge=headingEdge
        headingPaths.forEach { path ->
            if(!night){edge.color=headingHalo;edge.strokeWidth=5f;c.drawPath(path,edge)}
            edge.color=secondary;edge.strokeWidth=2f;c.drawPath(path,edge)
        }
    }
    /** Original compass/triangle geometry above every scenery and gauge pass. */
    internal fun drawHeading(c:Canvas,v:HelmRenderValues){ drawHeadingReference(c);drawHeadingMotion(c,v) }
    private fun drawHeadingMotion(c:Canvas,v:HelmRenderValues){
        val edge=headingEdge
        if(!v.headingAvailable){
            text(c,"HDG — · ${if(v.demo) "SIMULATED" else "SOURCE"} · ${v.quality}",1000f,193f,17f,"label",secondary,"center",outline=if(night)0f else 2f,outlineColor=headingHalo)
            return
        }
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=secondary;strokeWidth=1.5f}
        val ticks=HeadingGeometry.ticks(v.heading.toDouble())
        c.save();clip(c,"heading-live-clip")
        ticks.forEach{t->
            if(!night){edge.color=headingHalo;edge.strokeWidth=4.5f;c.drawLine(t.x,t.y,t.x,t.y-if(t.major)21f else 8f,edge)}
            c.drawLine(t.x,t.y,t.x,t.y-if(t.major)21f else 8f,p)
        }
        c.restore()
        ticks.filter{it.label!=null}.forEach{t->
            val size=if(t.cardinal)36f else 24f
            val paint=Paint().apply{textSize=size;typeface=typefaces[if(t.cardinal)"hero" else "label"]}
            val fm=paint.fontMetrics
            val top=t.y-if(t.cardinal)67f else 55f
            val baseline=top+(size*1.2f-(fm.descent-fm.ascent))/2-fm.ascent
            text(c,t.label!!,t.x,baseline,size,if(t.cardinal)"hero" else "label",secondary,"center",1.1f,outline=if(night)0f else 3f,outlineColor=headingHalo,numericSlots=if(t.cardinal)null else NumericSlots(3))
        }
        text(c,"HDG ${if(v.demo) "SIMULATED" else "SOURCE"} · ${v.heading.roundToInt()}° · ${v.quality}",1000f,193f,17f,"label",secondary,"center",outline=if(night)0f else 2f,outlineColor=headingHalo,numericSlots=NumericSlots(3))
    }

    private fun drawEngineLabels(c:Canvas){
        text(c,No5Text.ui("PORT"),64f,181f,29f,"label",secondary,scaleX=1.28f)
        text(c,No5Text.ui("STBD"),1936f,181f,29f,"label",secondary,"right",1.28f)
        text(c,"RPM",39f,325f,29f,"label",secondary,scaleX=1.4f)
        text(c,"RPM",1961f,325f,29f,"label",secondary,"right",1.4f)
    }

    private fun drawEngineValues(c:Canvas,v:HelmRenderValues){
        rpmReadout.draw(c,v.text["rpm-port"]?:"—",38f,285f,ink,TabularRpmLayout.Anchor.LEFT)
        rpmReadout.draw(c,v.text["rpm-stbd"]?:"—",1962f,285f,ink,TabularRpmLayout.Anchor.RIGHT)
    }

    private fun drawHeaderStatic(c:Canvas){
        text(c,"NO 5",35f,62f,64f,"hero",ink,scaleX=1.7f)
        text(c,"STELLENBOSCH",38f,85f,22f,"label",secondary,scaleX=1.28f)
    }

    private fun drawHeaderValues(c:Canvas,v:HelmRenderValues){
        text(c,v.text["header-source-label"] ?: "SOURCE · ${v.quality}",1392f,50f,21f,"label",secondary,maxWidth=236f)
        text(c,v.text["header-source-detail"] ?: v.quality,1392f,75f,14f,"label",secondary,maxWidth=236f)
    }

    private fun drawThemeSwitch(c:Canvas){
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(night)Color.parseColor("#700000") else ink}
        c.drawRoundRect(RectF(if(night)1852f else 1741f,19f,if(night)1963f else 1852f,70f),25.5f,25.5f,p)
        text(c,No5Text.ui("DAY"),1796.5f,54f,22f,"label",if(night)secondary else background,"center")
        text(c,No5Text.ui("NIGHT"),1907.5f,54f,22f,"label",if(night)accent else secondary,"center")
    }

    private fun drawEngineQualityLabels(c:Canvas,v:HelmRenderValues){
        text(c,"${No5Text.ui(if(v.demo) "SIMULATED" else "SOURCE")} · ${No5Text.ui(v.quality)}",39f,341f,14f,"label",secondary)
        text(c,"${No5Text.ui(if(v.demo) "SIMULATED" else "SOURCE")} · ${No5Text.ui(v.quality)}",1961f,341f,14f,"label",secondary,"right")
    }

    private fun drawNavigation(c:Canvas,page:String) = BottomNavigation.draw(c,page,night,this)

    fun text(c:Canvas,value:String,x:Float,y:Float,size:Float,family:String,col:Int,anchor:String="left",scaleX:Float=1f,maxWidth:Float=Float.MAX_VALUE,opacity:Float=1f,outline:Float=0f,outlineColor:Int=ink,numericSlots:NumericSlots?=null,numericFollowing:NumericSlots?=numericSlots){
        val p=textPaint(size,family,col).apply{alpha=(255*opacity).roundToInt().coerceIn(0,255);textAlign=when(anchor){"right"->Paint.Align.RIGHT;"center"->Paint.Align.CENTER;else->Paint.Align.LEFT}}
        if(numericSlots!=null && NumericCells.contains(value)) {
            p.textScaleX=scaleX
            val stableStyle=numericStyle(size,family,scaleX,p)
            if(outline>0){p.style=Paint.Style.STROKE;p.strokeWidth=outline;p.color=outlineColor;NativeNumericText.draw(c,value,x,y,p,numericSlots,maxWidth,numericFollowing?:numericSlots,stableStyle);p.style=Paint.Style.FILL;p.color=col}
            NativeNumericText.draw(c,value,x,y,p,numericSlots,maxWidth,numericFollowing?:numericSlots,stableStyle);return
        }
        val width=measuredWidth(value,p);val fit=if(width>0)min(scaleX,maxWidth/width)else scaleX
        c.save();c.translate(x,y);c.scale(fit.coerceAtLeast(.0001f),1f)
        if(outline>0){p.style=Paint.Style.STROKE;p.strokeWidth=outline;p.color=outlineColor;c.drawText(value,0f,0f,p);p.style=Paint.Style.FILL;p.color=col}
        c.drawText(value,0f,0f,p);c.restore()
    }

    // Scene-owned, bounded caches; all scene drawing is confined to its Canvas/UI owner.
    private fun textPaint(size:Float,family:String,col:Int,scaleX:Float=1f):Paint {
        val key=TextPaintKey(size,family,col,scaleX)
        val paint=textPaints.get(key) ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize=size;typeface=typefaces[family]
        }.also { textPaints.put(key,it) }
        // Callers temporarily change alignment/stroke/stretch; never leak state to another label.
        paint.color=col;paint.textScaleX=scaleX;paint.textAlign=Paint.Align.LEFT
        paint.style=Paint.Style.FILL;paint.strokeWidth=0f
        return paint
    }
    private fun glyphMetrics(value:String,paint:Paint):HelmGlyphMetrics {
        val key=MeasureKey(value,paint.typeface,paint.textSize,paint.textScaleX)
        glyphBounds.get(key)?.let { return it }
        val inkBounds=Rect();paint.getTextBounds(value,0,value.length,inkBounds)
        val fm=paint.fontMetrics
        return HelmGlyphMetrics(minOf(0f,inkBounds.left.toFloat()),minOf(fm.ascent,inkBounds.top.toFloat()),
            maxOf(measuredWidth(value,paint),inkBounds.right.toFloat(),.01f),maxOf(fm.descent,inkBounds.bottom.toFloat()))
            .also { if(value.length<=256)glyphBounds.put(key,it) }
    }
    private fun drawPlaced(c:Canvas,value:String,paint:Paint,placed:HelmTextLayout.PlacedGlyph){
        c.save();c.translate(placed.x,placed.baseline);c.scale(placed.scale,placed.scale)
        c.drawText(value,0f,0f,paint);c.restore()
    }
    private fun withTextBox(c:Canvas,box:NativeHelmLayout.TextBox,draw:()->Unit){
        c.save();c.clipRect(box.left,box.top,box.right,box.bottom);draw();c.restore()
    }
    private fun boundedText(c:Canvas,value:String,box:NativeHelmLayout.TextBox,size:Float,
        family:String,col:Int,anchor:String,scaleX:Float=1f,ellipsize:Boolean=false){
        val paint=textPaint(size,family,col,scaleX)
        var shown=value
        if(ellipsize && glyphMetrics(shown,paint).width>box.width){
            while(shown.isNotEmpty() && glyphMetrics("$shown…",paint).width>box.width)shown=shown.dropLast(1)
            shown+="…"
        }
        val placed=HelmTextLayout.single(box,glyphMetrics(shown,paint),anchor)
        withTextBox(c,box){drawPlaced(c,shown,paint,placed)}
    }
    private fun boundedValueUnit(c:Canvas,value:String,unit:String,box:NativeHelmLayout.TextBox,
        valueSize:Float,unitSize:Float,valueFamily:String,unitFamily:String,valueColor:Int,unitColor:Int,
        scaleX:Float,slots:NumericSlots=NumericSlots(5,1)){
        val valuePaint=textPaint(valueSize,valueFamily,valueColor,scaleX)
        val unitPaint=textPaint(unitSize,unitFamily,unitColor,scaleX)
        val metrics=glyphMetrics("8",valuePaint)
        val stableStyle=numericStyle(valueSize,valueFamily,scaleX,valuePaint)
        val fixedMetrics=metrics.copy(left=0f,right=NativeNumericText.layout(value,stableStyle,slots).width)
        val placed=HelmTextLayout.valueUnit(box,fixedMetrics,glyphMetrics(unit,unitPaint))
        withTextBox(c,box){
            c.save();c.translate(placed.value.x,placed.value.baseline);c.scale(placed.value.scale,placed.value.scale)
            NativeNumericText.draw(c,value,0f,0f,valuePaint,slots,style=stableStyle);c.restore()
            drawPlaced(c,unit,unitPaint,placed.unit)
        }
    }
    private fun parsePath(d:String)=pathCache.getOrPut(d){PathParser().parsePathString(d).toPath().asAndroidPath()}
    private fun applyTransform(c:Canvas,s:String){if(s.isNotBlank())c.concat(transformMatrix(s))}
    private fun transformMatrix(s:String):Matrix=transformCache.getOrPut(s){
        val m=Matrix()
        TRANSFORM_PATTERN.findAll(s).forEach{v->val n=numberList(v.groupValues[2]);val q=Matrix();when(v.groupValues[1]){
            "translate"->q.setTranslate(n.getOrElse(0){0f},n.getOrElse(1){0f})
            "scale"->q.setScale(n.getOrElse(0){1f},n.getOrElse(1){n.getOrElse(0){1f}})
            "rotate"->q.setRotate(n.getOrElse(0){0f},n.getOrElse(1){0f},n.getOrElse(2){0f})
            "matrix"->if(n.size==6)q.setValues(floatArrayOf(n[0],n[2],n[4],n[1],n[3],n[5],0f,0f,1f))
        };m.preConcat(q)};m
    }
    private fun numberList(s:String)=NUMBER_PATTERN.findAll(s).map{it.value.toFloat()}.toList()
    private fun color(s:String,fallback:Int):Int {
        val key=ColorKey(s,fallback)
        return colors.get(key) ?: parseColor(s,fallback).also { if(s.length<=128)colors.put(key,it) }
    }
    private fun parseColor(s:String,fallback:Int):Int=try{
        val normalized=if(s.length==4 && s[0]=='#' && s.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })"#"+s.drop(1).map{"$it$it"}.joinToString("")else s
        if(normalized.startsWith("url("))fallback else Color.parseColor(normalized)
    }catch(_:IllegalArgumentException){fallback}
    init {
        compiled;htmlTexts;headingPaths;staticTexts;slowTexts;motionTexts
        for(id in clips.keys())compiledClip(id)
        // Navigation variants compile at construction as well; no JSON in any draw pass.
        val recording=Picture();val c=recording.beginRecording(2000,1200)
        BottomNavigation.pages.forEach { drawNavigation(c,it) }
        recording.endRecording()
    }
    private companion object {
        val DYNAMIC_TEXT_IDS=setOf("header-source-label","header-source-detail","deck-airTemp","deck-wind","deck-humidity","deck-pressure","deck-evidence-environment","rpm-port","rpm-stbd","hud-sog","hud-depth","hud-sog-unit","hud-depth-unit","secondary-port-flow","secondary-stbd-flow","secondary-port-gear","secondary-stbd-gear","secondary-port-coolant","secondary-stbd-coolant","secondary-port-oil","secondary-stbd-oil","deck-latitude","deck-longitude","secondary-port-trim","secondary-stbd-trim","secondary-port-voltage","secondary-stbd-voltage","deck-fuelPct","deck-remainingL","deck-totalFlow","deck-unit-totalFlow","deck-cog","deck-evidence-position","deck-tripDistance","deck-unit-tripDistance","deck-tripDuration","deck-fuelUsed","deck-efficiency","deck-unit-efficiency","deck-evidence-trip","deck-fuel-evidence","deck-flow-quality")
        val TRANSFORM_PATTERN=Regex("([a-zA-Z]+)\\(([^)]*)\\)")
        val NUMBER_PATTERN=Regex("[-+]?(?:\\d*\\.)?\\d+(?:[eE][-+]?\\d+)?")
    }
    // Scene JSON and completed navigation clones are private, fixed after construction.
    private fun JSONObject.f(key:String,default:Float=0f):Float {
        val cacheKey=NumberKey(this,key,default)
        return numbers.get(cacheKey) ?: (optString(key).removeSuffix("px").toFloatOrNull()?:default)
            .also { numbers.put(cacheKey,it) }
    }
}

data class HelmRenderValues(
    val portRpm:Float=3400f,val stbdRpm:Float=3420f,val portTrim:Float=72f,val stbdTrim:Float=68f,
    val heading:Float=0f,val fuel:Float=68f,val running:Boolean=false,val quality:String=No5Text.ui("UNAVAILABLE"),val text:Map<String,String> = emptyMap(),val headingAvailable:Boolean=true,val demo:Boolean=true,
    val fuelAvailable:Boolean=true,
)
