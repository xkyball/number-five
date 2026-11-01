@file:Suppress("DEPRECATION")
package com.no5.systemwindowtest

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.util.AtomicFile
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.no5.keyboard.SpecialKeyboardGeometry

/** Diagnostic IME: never records editor text, key presses or candidate contents. */
class NarrowTestIme:InputMethodService() {
    internal var keyboard: NarrowKeyboardView?=null
    private val input=TestImeInput()
    private var lastGeometry=""
    private var numericInput=false
    private var editorIdentity:Triple<String?,Int,Int>?=null
    private var editorAction=EditorInfo.IME_ACTION_UNSPECIFIED
    private var geometryTarget:String?=null
    private var geometryVisible=false
    private var lastPublishedGeometry=""
    override fun onCreate(){setTheme(com.no5.systemwindowtest.R.style.Theme_No5TestIme);super.onCreate()}
    override fun onEvaluateFullscreenMode()=false
    override fun onCreateInputView():View {
        window?.window?.apply {setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);setGravity(Gravity.BOTTOM)}
        return NarrowKeyboardView(this) { key ->
            when(key){
                "⌄" -> requestHideSelf(0)
                "IME" -> getSystemService(InputMethodManager::class.java).showInputMethodPicker()
                "123", "ABC" -> {numericInput=keyboard?.numeric!=true;keyboard?.numeric=numericInput;keyboard?.words=false;keyboard?.invalidate()}
                "WORDS" -> {keyboard?.let{if(!it.sensitive&&!it.numeric){it.words=!it.words;it.invalidate()}}}
                "⇧" -> {keyboard?.upper=keyboard?.upper!=true;keyboard?.invalidate()}
                else -> {input.send(key,currentInputConnection,currentInputEditorInfo);keyboard?.invalidate()}
            }
        }.also{keyboard=it;it.sensitive=input.sensitive;it.numeric=numericInput;it.editorAction=editorAction;it.layoutParams=android.widget.FrameLayout.LayoutParams(-1,NarrowKeyboardView.HEIGHT)}
    }
    override fun onStartInput(attribute:EditorInfo?,restarting:Boolean){
        super.onStartInput(attribute,restarting)
        if(geometryTarget!=attribute?.packageName)SpecialKeyboardGeometry.publish(this,geometryTarget?.takeIf{it=="com.no5.helm"||it=="com.no5.map"},null)
        geometryTarget=attribute?.packageName
        input.finish(currentInputConnection);input.start(attribute)
        val identity=Triple(attribute?.packageName,attribute?.fieldId?:0,attribute?.inputType?:0)
        val keepManualMode=restarting&&identity==editorIdentity
        val inputClass=(attribute?.inputType?:0) and InputType.TYPE_MASK_CLASS
        if(!keepManualMode)numericInput=inputClass in listOf(InputType.TYPE_CLASS_NUMBER,InputType.TYPE_CLASS_PHONE,InputType.TYPE_CLASS_DATETIME)
        editorIdentity=identity
        editorAction=(attribute?.imeOptions?:EditorInfo.IME_ACTION_UNSPECIFIED) and EditorInfo.IME_MASK_ACTION
        keyboard?.apply{sensitive=input.sensitive;numeric=numericInput;this.editorAction=this@NarrowTestIme.editorAction
            if(!keepManualMode){upper=false;words=false};if(sensitive)words=false;invalidate()}
    }
    override fun onFinishInput(){geometryVisible=false;publishGeometry();input.finish(currentInputConnection);super.onFinishInput()}
    override fun onWindowShown(){super.onWindowShown();geometryVisible=true;publishGeometry()}
    override fun onWindowHidden(){geometryVisible=false;publishGeometry();super.onWindowHidden()}
    private fun publishGeometry(){
        // DocumentsUI and other system windows keep their geometry and receive no event.
        val target=geometryTarget?.takeIf{it=="com.no5.helm"||it=="com.no5.map"}?:return
        val v=keyboard
        val bounds=if(geometryVisible&&v!=null&&v.width>0&&v.height>0){
            val screen=IntArray(2);v.getLocationOnScreen(screen);val r=v.middleRect()
            Rect((screen[0]+r.left).toInt(),screen[1],(screen[0]+r.right).toInt(),screen[1]+v.height)
        }else null
        val signature="$target:$geometryVisible:$bounds"
        if(signature!=lastPublishedGeometry){lastPublishedGeometry=signature;SpecialKeyboardGeometry.publish(this,target,bounds)}
    }
    override fun onUpdateSelection(oldSelStart:Int,oldSelEnd:Int,newSelStart:Int,newSelEnd:Int,candidatesStart:Int,candidatesEnd:Int){
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        if(candidatesEnd<0||newSelStart!=candidatesEnd||newSelEnd!=candidatesEnd)input.finish(currentInputConnection)
    }
    override fun onComputeInsets(outInsets:Insets){
        super.onComputeInsets(outInsets)
        val v=keyboard?:return
        val location=IntArray(2);v.getLocationInWindow(location)
        val screen=IntArray(2);v.getLocationOnScreen(screen)
        val region=v.middleRect()
        outInsets.contentTopInsets=location[1]
        outInsets.visibleTopInsets=location[1]
        outInsets.touchableInsets=Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(Rect(region.left.toInt()+location[0],location[1],region.right.toInt()+location[0],location[1]+v.height))
        publishGeometry()
        val g=JSONObject().put("scope","OWN_NO5_TEST_IME_WINDOW").put("view_screen_origin_px",JSONArray(screen.toList()))
            .put("view_px",JSONArray(listOf(v.width,v.height))).put("middle_screen_rect_px",JSONArray(listOf(region.left+screen[0],screen[1].toFloat(),region.right+screen[0],(screen[1]+v.height).toFloat())))
            .put("touchable_window_rect_px",JSONArray(listOf(region.left.toInt()+location[0],location[1],region.right.toInt()+location[0],location[1]+v.height)))
            .put("content_top_inset_px",outInsets.contentTopInsets).put("visible_top_inset_px",outInsets.visibleTopInsets)
            .put("target_package",currentInputEditorInfo?.packageName?:JSONObject.NULL).put("sensitive_field",input.sensitive)
            .put("layout","BOTTOM_DOCKED_IME_1303PX_MIDDLE; system navigation inset may keep bottom above physical edge")
        val key=g.toString()
        if(key!=lastGeometry){lastGeometry=key;g.put("utc_ms",System.currentTimeMillis()).put("elapsed_realtime_ms",SystemClock.elapsedRealtime())
            val f=AtomicFile(File(filesDir,"test-ime-geometry.json"));val s=f.startWrite();try{s.write(g.toString().toByteArray());f.finishWrite(s)}catch(e:Exception){f.failWrite(s)}
        }
    }
}

internal class TestImeInput {
    var sensitive=false;private set
    private var composing=""
    fun start(info:EditorInfo?){composing="";val type=info?.inputType?:0;val variation=type and InputType.TYPE_MASK_VARIATION
        sensitive=type and InputType.TYPE_MASK_CLASS==InputType.TYPE_CLASS_NUMBER && variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
            type and InputType.TYPE_MASK_CLASS==InputType.TYPE_CLASS_TEXT && variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    }
    fun finish(connection:InputConnection?){composing="";connection?.finishComposingText()}
    fun send(key:String,connection:InputConnection?,info:EditorInfo?){
        val c=connection?:return
        when {
            key.startsWith("Wort:") -> if(!sensitive){c.commitText(key.removePrefix("Wort:")+" ",1);composing=""}
            key=="⌫" -> if(composing.isNotEmpty()){composing=composing.dropLast(1);c.setComposingText(composing,1)}else c.deleteSurroundingTextInCodePoints(1,0)
            key=="Leer" -> {finish(c);c.commitText(" ",1)}
            key=="↵" -> {finish(c);val action=(info?.imeOptions?:EditorInfo.IME_ACTION_NONE) and EditorInfo.IME_MASK_ACTION;if(action !in listOf(EditorInfo.IME_ACTION_NONE,EditorInfo.IME_ACTION_UNSPECIFIED))c.performEditorAction(action)else c.commitText("\n",1)}
            key.length==1 -> if(sensitive){c.commitText(key,1)}else if(key[0].isLetter()){composing+=key;c.setComposingText(composing,1)}else{finish(c);c.commitText(key,1)}
        }
    }
}

internal data class TestKey(val label:String,val value:String,val rect:RectF)
internal class NarrowKeyboardView(context:Context,private val send:(String)->Unit):View(context) {
    var numeric=false
    var upper=false
    var sensitive=false
    var words=false
    var editorAction=EditorInfo.IME_ACTION_UNSPECIFIED
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed:TestKey?=null
    companion object{const val HEIGHT=480}
    fun middleRect():RectF {val pos=IntArray(2);getLocationOnScreen(pos);return RectF((353-pos[0]).toFloat().coerceIn(0f,width.toFloat()),0f,(1656-pos[0]).toFloat().coerceIn(0f,width.toFloat()),height.toFloat())}
    fun keys():List<TestKey>{
        val middle=middleRect();val result=mutableListOf<TestKey>()
        fun line(y:Float,labels:List<Pair<String,String>>,weights:List<Float> = List(labels.size){1f}) {
            var x=middle.left;val unit=middle.width()/weights.sum()
            labels.forEachIndexed{i,(label,value)->val w=unit*weights[i];result.add(TestKey(label,value,RectF(x+3,y+3,x+w-3,y+111)));x+=w}
        }
        val rows=if(numeric)listOf("1234567890","-/:;()€&@\"",".,?!'_=+%")else listOf("qwertzuiop","asdfghjklö","yxcvbnmäü")
        rows.forEachIndexed{i,row->line(24f+i*114,if(i==0&&words&&!sensitive&&!numeric)
            listOf("Hallo" to "Wort:Hallo","Boot" to "Wort:Boot","Test" to "Wort:Test")
            else row.map{val letter=if(upper)it.uppercase()else it.toString();letter to letter})}
        val enter=when(editorAction){EditorInfo.IME_ACTION_NEXT->"Weiter";EditorInfo.IME_ACTION_DONE->"Fertig";EditorInfo.IME_ACTION_SEARCH->"Suchen";EditorInfo.IME_ACTION_SEND->"Senden";else->"↵"}
        line(366f,listOf((if(numeric)"ABC"else"123") to (if(numeric)"ABC"else"123"),"⇧" to "⇧",
            "Leerzeichen" to "Leer","⌫" to "⌫",enter to "↵",
            (if(sensitive||numeric)"—"else if(words)"Buchst."else"Wörter") to (if(sensitive||numeric)"DISABLED"else"WORDS"),
            "IME" to "IME","⌄" to "⌄"),listOf(1f,1f,3f,1f,1f,1f,1f,1f))
        return result
    }
    override fun onMeasure(w:Int,h:Int){setMeasuredDimension(MeasureSpec.getSize(w),resolveSize(HEIGHT,h))}
    override fun onDraw(c:Canvas){
        val middle=middleRect();paint.color=Color.rgb(14,40,52);paint.style=Paint.Style.FILL;c.drawRect(middle,paint)
        paint.color=Color.WHITE;paint.textSize=18f;c.drawText(if(sensitive)"NO5 · Geschütztes Feld"else"NO5 · Spezialtastatur",middle.left+12,19f,paint)
        keys().forEach {k->paint.color=if(k==pressed)Color.rgb(121,211,229)else Color.rgb(231,243,247);c.drawRoundRect(k.rect,6f,6f,paint);paint.color=Color.rgb(10,34,44);paint.textSize=if(k.label.length>10)23f else 34f;c.drawText(k.label,k.rect.centerX()-paint.measureText(k.label)/2,k.rect.centerY()-(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2,paint)}
    }
    override fun performClick():Boolean{super.performClick();return true}
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(e.pointerCount>1){pressed=null;invalidate();return true}
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{pressed=keys().firstOrNull{it.rect.contains(e.x,e.y)};invalidate();return pressed!=null}
            MotionEvent.ACTION_UP->{val p=pressed;pressed=null;if(p!=null&&p.rect.contains(e.x,e.y)){performClick();send(p.value)};invalidate();return p!=null}
            MotionEvent.ACTION_CANCEL->{pressed=null;invalidate()}
        };return pressed!=null
    }
}
