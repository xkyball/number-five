package com.no5.helm.ui

import android.content.Context
import android.graphics.*
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.no5.header.artwork.No5WorkspaceView
import com.no5.ui.No5Text
import com.no5.ui.No5TouchFeedback
import com.no5.ui.No5Workspace
import kotlin.math.*

/** MAP-only layouts. Design coordinates are the approved stage, independent of render viewport. */
internal fun m1(de:String,en:String,af:String,language:String=com.no5.ui.UiLanguage.current()):String = when(language){"en"->en;"af"->af;else->de}
internal data class M1Control(val key:String,val label:String,val x:Float,val y:Float,val w:Float,val h:Float=96f,
 val enabled:Boolean=true,val selected:Boolean=false,val check:Boolean=false,val value:String?=null,
 val change:((String)->Unit)?=null,val description:String=label,val dynamicLabel:Boolean=false,val plain:Boolean=false,val active:Boolean=false,val displayPrefix:String="",val action:()->Unit={})
internal data class M1Keyboard(val fieldKey:String,val rows:List<M1Control>,val previous:M1Control,val next:M1Control,
 val actions:List<M1Control>,val normalCapacity:Int,val onCapacity:(Int)->Unit)
internal data class M1Scene(val title:String,val controls:List<M1Control>,val paint:M1Painter.()->Unit={},val notice:String?=null,val mapBounds:M1Rect?=null,val onMapTap:((Float,Float)->Unit)?=null,val onMapPan:((Float,Float)->Unit)?=null,val keyboard:M1Keyboard?=null,val clipControls:Boolean=false)
/** Reflow only the workspace above the actual IME. The dock is never a layout input. */
internal fun m1KeyboardCapacity(bottom:Float,notice:Boolean=false):Int =
 floor((bottom-112f-344f-if(notice)52f else 0f)/104f).toInt().coerceAtLeast(0)
internal fun m1KeyboardScene(scene:M1Scene,bottom:Float):M1Scene {
 val k=scene.keyboard?:return scene
 if(bottom>=938f)return scene
 val capacity=m1KeyboardCapacity(bottom,!scene.notice.isNullOrBlank())
 val field=scene.controls.firstOrNull{it.key==k.fieldKey}?:return scene
 val footerY=bottom-112f
 val controls=buildList{
  scene.controls.firstOrNull{it.key=="no5-workspace-back"}?.let{add(it)}
  add(field.copy(x=40f,y=234f,w=1130f))
  add(k.previous.copy(key="keyboard-previous",x=1200f,y=234f,w=370f))
  add(k.next.copy(key="keyboard-next",x=1590f,y=234f,w=370f))
  k.rows.take(capacity).forEachIndexed{i,row->add(row.copy(x=40f,y=344f+i*104f,w=1920f))}
  k.actions.forEach{add(it.copy(y=footerY))}
 }
 return scene.copy(controls=controls,paint={
  if(capacity==0)text(m1("Tastatur schließen, um Einträge zu sehen","Close keyboard to see entries","Sluit sleutelbord om inskrywings te sien"),40f,footerY-20,27f,1920f)
 },mapBounds=null,onMapTap=null,onMapPan=null,keyboard=null,clipControls=true)
}
internal class M1Painter(val canvas:Canvas,val context:Context,val night:Boolean,val font:Float){
 val ink=Color.parseColor(if(night)"#ed0000"else"#07121b");val muted=Color.parseColor(if(night)"#a40000"else"#526b7b")
 val line=Color.parseColor(if(night)"#550000"else"#b5c2c8");val bg=Color.parseColor(if(night)"#000000"else"#f6f9fa")
 val accent=Color.parseColor(if(night)"#ed0000"else"#ff5b00")
 val regular=Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_regular.ttf")
 val bold=Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_semibold.ttf")
 val p=Paint(Paint.ANTI_ALIAS_FLAG)
 fun rect(x:Float,y:Float,w:Float,h:Float,color:Int=bg,stroke:Boolean=false){p.color=color;p.style=if(stroke)Paint.Style.STROKE else Paint.Style.FILL;p.strokeWidth=2f;canvas.drawRect(x,y,x+w,y+h,p);p.style=Paint.Style.FILL}
 fun line(x:Float,y:Float,x2:Float,y2:Float,color:Int=line){p.color=color;p.strokeWidth=2f;canvas.drawLine(x,y,x2,y2,p)}
 fun text(s:String,x:Float,y:Float,size:Float=30f,w:Float=1280f,strong:Boolean=false,color:Int=ink,dynamic:Boolean=false){
  if(w<=0f)return
  p.typeface=if(strong)bold else regular;p.textSize=size*font;p.color=color
  val shown=if(p.measureText(s)<=w)s else s.take(p.breakText(s,true,(w-p.measureText("…")).coerceAtLeast(0f),null))+"…"
  com.no5.header.artwork.No5WorkspaceDiagnostics.observe?.invoke(com.no5.header.artwork.No5WorkspaceTextFit(s,s,shown,p.measureText(s),w,p.textSize,font,dynamic))
  canvas.drawText(shown,x,y,p)
 }
 fun grid(x:Float,y:Float,w:Float,h:Float,label:String){
  rect(x,y,w,h,if(night)Color.rgb(8,0,0)else Color.rgb(234,241,243))
  for(i in 0..5){line(x+i*w/5,y,x+i*w/5,y+h);line(x,y+i*h/5,x+w,y+i*h/5)}
  text(label,x+18,y+34,22f,w-36,true)
 }
}
internal class MapM1View(context:Context):FrameLayout(context){
 private var scene=M1Scene("",emptyList());private var night=false
 private val widgets=linkedMapOf<String,View>();private var scale=1f
 init{setWillNotDraw(false);clipChildren=true;tag="map-m1-workspace"}
 fun render(next:M1Scene,redNight:Boolean){
  scene=next;night=redNight;clipChildren=next.clipControls
  val desired=next.controls.map{it.key}.toSet()
  widgets.keys.filter{it !in desired}.toList().forEach{key->val old=widgets.remove(key);if(old is Input&&old.hasFocus())hideOwnKeyboard();removeView(old)}
  next.controls.forEach{spec->
   val old=widgets[spec.key]
   val edit=spec.change!=null
   val child=if(old!=null&&(old is Input)==edit)old else {
    old?.let{removeView(it)}
    (if(edit)Input(spec)else Action(spec)).also{widgets[spec.key]=it;addView(it)}
   }
   when(child){is Input->child.bind(spec);is Action->child.bind(spec)}
  }
  contentDescription=next.title+listOfNotNull(next.notice).joinToString(". ")
  requestLayout();invalidate()
 }
 private fun hideOwnKeyboard(){(context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(windowToken,0)}
 override fun onDetachedFromWindow(){if(findFocus() is Input)hideOwnKeyboard();super.onDetachedFromWindow()}
 private var downX=0f;private var downY=0f;private var lastX=0f;private var lastY=0f;private var moving=false;private var mapGesture=false
 override fun onTouchEvent(e:android.view.MotionEvent):Boolean {
  val x=e.x/scale;val y=e.y/scale+130
  when(e.actionMasked){
   android.view.MotionEvent.ACTION_DOWN->{mapGesture=scene.mapBounds?.contains(x,y)==true;downX=x;downY=y;lastX=x;lastY=y;moving=false;return mapGesture}
   android.view.MotionEvent.ACTION_POINTER_DOWN->{mapGesture=false;return false}
   android.view.MotionEvent.ACTION_MOVE->if(mapGesture){
    if(hypot(x-downX,y-downY)>android.view.ViewConfiguration.get(context).scaledTouchSlop/scale)moving=true
    if(moving)scene.onMapPan?.invoke(x-lastX,y-lastY);lastX=x;lastY=y;return true
   }
   android.view.MotionEvent.ACTION_UP->if(mapGesture){mapGesture=false;if(!moving){performClick();scene.onMapTap?.invoke(x,y)};return true}
   android.view.MotionEvent.ACTION_CANCEL->{mapGesture=false;return false}
  };return super.onTouchEvent(e)
 }
 override fun performClick():Boolean {super.performClick();return true}
 override fun onMeasure(w:Int,h:Int){
  val width=MeasureSpec.getSize(w);scale=width/2000f
  setMeasuredDimension(width,MeasureSpec.getSize(h))
  scene.controls.forEach{a->(widgets[a.key] as? Input)?.scaleChanged();widgets[a.key]?.measure(MeasureSpec.makeMeasureSpec((a.w*scale).roundToInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((a.h*scale).roundToInt(),MeasureSpec.EXACTLY))}
 }
 override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){scene.controls.forEach{a->widgets[a.key]?.layout((a.x*scale).roundToInt(),((a.y-130)*scale).roundToInt(),((a.x+a.w)*scale).roundToInt(),((a.y+a.h-130)*scale).roundToInt())}}
 override fun onDraw(c:Canvas){
  c.save();c.scale(scale,scale);c.translate(0f,-130f)
  val painter=M1Painter(c,context,night,resources.configuration.fontScale)
  with(painter){rect(0f,130f,2000f,808f);text(scene.title,40f,205f,38f,1500f,true);scene.paint(this)
   scene.notice?.takeIf{it.isNotBlank()}?.let{val y=min(770f,height/scale+130-164f);rect(32f,y,1936f,47f);text(it,45f,y+34f,25f,1910f,color=muted,dynamic=true)}}
  c.restore()
 }
 private inner class Action(var spec:M1Control):View(context){
  init{isFocusable=true;No5TouchFeedback.install(this,android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),Color.TRANSPARENT,Color.TRANSPARENT);setOnClickListener{spec.action()};bind(spec)}
  fun bind(a:M1Control){spec=a;tag=a.key;isEnabled=a.enabled;isSelected=a.selected;contentDescription=a.description;isClickable=true;invalidate()}
  override fun drawableStateChanged(){super.drawableStateChanged();invalidate()}
  override fun onDraw(c:Canvas){c.save();if(!scene.clipControls)c.translate(spec.x*scale-left,(spec.y-130)*scale-top);c.scale(scale,scale)
   with(M1Painter(c,context,night,resources.configuration.fontScale)){
    if(spec.plain){ /* Transparent hit area: parent paints its complete data label. */ }else if(spec.check){rect(30f,30f,32f,32f,if(spec.active)accent else line,true);if(spec.selected){line(35f,45f,45f,55f,accent);line(45f,55f,60f,37f,accent)};if(spec.w>100f)text(spec.label,110f,59f,27f,spec.w-140f,true,if(isEnabled)ink else muted,dynamic=spec.dynamicLabel)}else{
     if(spec.selected||isPressed&&isEnabled)rect(0f,0f,spec.w,spec.h,Color.parseColor(if(night)"#300000"else"#ffebde"))
     rect(0f,0f,spec.w,spec.h,if(spec.selected)accent else line,true)
     text(spec.label,18f,59f,27f,spec.w-36,true,if(isEnabled)ink else muted,dynamic=spec.dynamicLabel)
    }
    if(isFocused&&hasWindowFocus())rect(5f,5f,spec.w-10,spec.h-10,accent,true)
   };c.restore()}
  override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.className=if(spec.check)"android.widget.CheckBox"else"android.widget.Button";info.isCheckable=spec.check;info.isChecked=spec.selected}
 }
 private inner class Input(var spec:M1Control):EditText(context){
  private var binding=false
  init {setSingleLine(true);background=null;gravity=android.view.Gravity.CENTER_VERTICAL;includeFontPadding=false
   addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,f:Int){};override fun afterTextChanged(s:Editable?){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){if(!binding)spec.change?.invoke(s?.toString().orEmpty())}});bind(spec)}
  fun bind(a:M1Control){spec=a;tag=a.key;isEnabled=a.enabled;contentDescription=a.description;hint=if(scene.clipControls)a.label else null
   val painter=M1Painter(Canvas(),context,night,resources.configuration.fontScale);typeface=painter.bold
   setTextColor(painter.ink);setHintTextColor(painter.muted);setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,27f*resources.configuration.fontScale*scale)
   setPadding((18*scale).toInt(),0,(18*scale).toInt(),0)
   if(text.toString()!=a.value.orEmpty()){binding=true;setText(a.value.orEmpty());setSelection(text.length);binding=false}
  }
  fun scaleChanged(){setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,27f*resources.configuration.fontScale*scale);setPadding((18*scale).toInt(),0,(18*scale).toInt(),0)}
  override fun onDraw(c:Canvas){val pp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=M1Painter(c,context,night,1f).let{if(isFocused&&hasWindowFocus())it.accent else it.line};style=Paint.Style.STROKE;strokeWidth=2*scale};c.drawRect(0f,0f,width.toFloat(),height.toFloat(),pp)
   if(!scene.clipControls&&!hasFocus()){
    c.save();c.scale(scale,scale);val value=text.toString()
    with(M1Painter(c,context,night,resources.configuration.fontScale)){text(if(value.isEmpty())spec.label else spec.displayPrefix+value,18f,59f,27f,spec.w-36f,true,dynamic=value.isNotEmpty())};c.restore()
   }else super.onDraw(c)
  }
 }
}
@Composable internal fun MapM1Surface(scene:M1Scene,night:Boolean,modifier:Modifier=Modifier){
 com.no5.ui.LocalNo5Language.current // Rebind translated labels after a central language change.
 BoxWithConstraints(modifier.fillMaxSize()){
  val bottom=(130f+maxHeight.value/(maxWidth.value/2000f)).coerceAtMost(938f)
  val k=scene.keyboard
  val capacity=if(bottom<938f)m1KeyboardCapacity(bottom,!scene.notice.isNullOrBlank()).coerceAtMost(k?.normalCapacity?:5)else k?.normalCapacity?:5
  SideEffect{k?.onCapacity?.invoke(capacity.coerceAtLeast(1))}
  val adapted=m1KeyboardScene(scene,bottom)
  AndroidView(factory={MapM1View(it)},update={it.render(adapted,night)},modifier=Modifier.fillMaxSize())
 }
}
internal fun m1Back(action:()->Unit)=M1Control("no5-workspace-back",m1("Zurück","Back","Terug"),1710f,132f,250f,action=action)
internal fun m1Actions(vararg actions:No5WorkspaceView.Action):List<M1Control>{
 val w=(1303f-(actions.size-1)*16)/actions.size
 return actions.mapIndexed{i,a->M1Control("no5-action-${a.key}",No5Text.ui(a.label),353+i*(w+16),824f,w,enabled=a.enabled,selected=a.selected,action=a.action)}
}
internal fun mapPageCapacity(height:Float,hasActions:Boolean=true):Int = floor(((min(938f,height+130)-if(hasActions)112 else 18)-266)/104).toInt().coerceIn(1,5)
@Composable internal fun MapPagedWorkspace(title:String,subtitle:String,rows:List<No5WorkspaceView.Row>,night:Boolean,onBack:()->Unit,
 modifier:Modifier=Modifier,actions:List<No5WorkspaceView.Action> = emptyList(),search:String?=null,onSearch:((String)->Unit)?=null,pageIndex:Int?=null,onPageChange:((Int)->Unit)?=null){
 var page by rememberSaveable{mutableIntStateOf(0)}
 BoxWithConstraints(modifier.fillMaxSize()){
  val height=maxHeight.value/(maxWidth.value/2000f)
  val count=mapPageCapacity(height,true)-(if(onSearch!=null)1 else 0)
  val capacity=count.coerceAtLeast(1);val pages=max(1,(rows.size+capacity-1)/capacity);val current=(pageIndex?:page).coerceIn(0,pages-1)
  val shown=buildList{if(onSearch!=null)add(No5WorkspaceView.Row(m1("Suchen","Search","Soek"),value=search.orEmpty(),rawValue=true,key="m1-search",control="field",onValueChange=onSearch));addAll(rows.drop(current*capacity).take(capacity))}
  val pager=if(pages>1)listOf(No5WorkspaceView.Action(m1("Zurück","Previous","Vorige"),enabled=current>0,key="m1-page-prev",action={if(onPageChange!=null)onPageChange(current-1)else page=current-1}),No5WorkspaceView.Action("${current+1} / $pages",enabled=false,key="m1-page-index",action={}),No5WorkspaceView.Action(m1("Weiter","Next","Volgende"),enabled=current+1<pages,key="m1-page-next",action={if(onPageChange!=null)onPageChange(current+1)else page=current+1}))else emptyList()
  No5Workspace(title,subtitle,shown,night,onBack,Modifier.fillMaxSize(),actions=actions+pager,showBack=true)
 }
}
