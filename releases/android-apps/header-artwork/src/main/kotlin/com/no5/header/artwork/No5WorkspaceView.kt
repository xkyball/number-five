package com.no5.header.artwork

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.no5.header.artwork.No5WorkspaceBackdrop
import com.no5.ui.No5Text
import com.no5.ui.No5TouchFeedback
import com.no5.ui.No5ActionInk

data class No5WorkspaceTextFit(val source:String,val text:String,val drawn:String,val width:Float,
    val available:Float,val fontPx:Float,val fontScale:Float,val dynamic:Boolean=false)
object No5WorkspaceDiagnostics { var observe:((No5WorkspaceTextFit)->Unit)?=null }

/** D03 workspace rows, using actual actions/data. Header, footer and main HOME remain separate. */
class No5WorkspaceView(context:Context,private val night:Boolean,private val title:String,private val subtitle:String,
    rows:List<Row>,back:()->Unit,showBack:Boolean=true,
    private val fontScale:Float=context.resources.configuration.fontScale,
    actions:List<Action> = emptyList(),rawSubtitle:Boolean=false) : FrameLayout(context) {
    data class Slider(val value:Double,val start:Double,val end:Double,val step:Double,val change:(Double)->Unit)
    data class Row(val label:String,val detail:String?=null,val value:String?=null,
        val enabled:Boolean=true,val rawLabel:Boolean=false,val rawDetail:Boolean=false,val rawValue:Boolean=false,
        val selected:Boolean=false,val key:String=label,val action:(()->Unit)?=null,
        val control:String="",val onValueChange:((String)->Unit)?=null,val slider:Slider?=null)
    data class Action(val label:String,val enabled:Boolean=true,val selected:Boolean=false,val key:String=label,val description:String?=null,val action:()->Unit)
    private val background=if(night)0xff000000.toInt()else 0xfff6f9fa.toInt()
    private val ink=if(night)0xffed0000.toInt()else 0xff07121b.toInt()
    private val muted=if(night)0xffa40000.toInt()else 0xff526b7b.toInt()
    private val line=if(night)0xff550000.toInt()else 0xffb5c2c8.toInt()
    private val accent=if(night)0xffed0000.toInt()else 0xffff5b00.toInt()
    private val heavy=android.graphics.Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_extrabold.ttf")
    private val regular=android.graphics.Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_regular.ttf")
    private val bold=android.graphics.Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_semibold.ttf")
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdrop=No5WorkspaceBackdrop(context.assets)
    private val heading=No5Text.text(context,title)
    private var captionRaw=rawSubtitle
    private var subtitleSource=subtitle
    private var caption=if(rawSubtitle)subtitle else No5Text.text(context,subtitle)
    private var currentRows=rows
    private var currentActions=actions
    private val content=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val actionRow=FrameLayout(context)
    private var scrollObserver:((Int,Boolean)->Unit)?=null
    private var scrolling=false
    private var pendingScroll:Int?=null
    private val scrollSettled=Runnable{scrolling=false;scrollObserver?.invoke(scroll.scrollY,false)}
    private val scroll:ScrollView=object:ScrollView(context){
        init{isFillViewport=false;clipToPadding=true}
        override fun onScrollChanged(l:Int,t:Int,oldl:Int,oldt:Int){
            super.onScrollChanged(l,t,oldl,oldt)
            if(t!=oldt&&isAttachedToWindow){scrolling=true;scrollObserver?.invoke(t,true);removeCallbacks(scrollSettled);postDelayed(scrollSettled,150)}
        }
        override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean{
            if(isAttachedToWindow&&event.actionMasked==android.view.MotionEvent.ACTION_DOWN)scrollObserver?.invoke(scrollY,scrolling)
            return super.dispatchTouchEvent(event)
        }
    }
    val scrollOffset get()=scroll.scrollY
    fun restoreScroll(y:Int){pendingScroll=y.coerceAtLeast(0);requestLayout()}
    fun observeScroll(observer:((Int,Boolean)->Unit)?){scrollObserver=observer}
    fun renderSubtitle(value:String,raw:Boolean=false){
        captionRaw=raw
        val next=if(raw)value else No5Text.text(context,value)
        if(caption!=next){caption=next;subtitleSource=value;contentDescription=heading+". "+caption;invalidate()}
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        super.onLayout(changed,l,t,r,b)
        if(scroll.height>0)pendingScroll?.let{pendingScroll=null;scroll.scrollTo(0,it)}
    }
    override fun onMeasure(widthSpec:Int,heightSpec:Int) {
        val available=View.MeasureSpec.getSize(heightSpec)
        val bodyLimit=if(currentActions.isEmpty())688 else 526
        val bodyHeight=minOf(bodyLimit,(available-148-if(currentActions.isEmpty())0 else 102).coerceAtLeast(104))
        if(scroll.layoutParams.height!=bodyHeight)scroll.layoutParams=scroll.layoutParams.apply{height=bodyHeight}
        val top=minOf(694,(available-116).coerceAtLeast(234))
        if((actionRow.layoutParams as LayoutParams).topMargin!=top)
            actionRow.layoutParams=(actionRow.layoutParams as LayoutParams).apply{topMargin=top}
        super.onMeasure(widthSpec,heightSpec)
    }

    init {
        tag="no5-workspace";contentDescription=heading+". "+caption
        setWillNotDraw(false);isClickable=true;clipChildren=false;actionRow.clipChildren=false
        isFocusableInTouchMode=true;descendantFocusability=FOCUS_BEFORE_DESCENDANTS
        if(showBack)addView(NativeAction(Action("‹ Zurück",action=back)).apply{tag="no5-workspace-back"},LayoutParams(238,96).apply{leftMargin=58;topMargin=16})
        rows.forEach{row->content.addView(WorkspaceRow(row),LinearLayout.LayoutParams(1280,104))}
        scroll.addView(content)
        addView(scroll,LayoutParams(1280,688).apply{leftMargin=360;topMargin=148})
        addView(actionRow,LayoutParams(1280,96).apply{leftMargin=360;topMargin=694})
        renderActions(actions)
    }
    fun renderActions(actions:List<Action>) {
        val rebuild=currentActions.map{it.key}!=actions.map{it.key} || actionRow.childCount!=actions.size
        currentActions=actions
        if(rebuild){
            actionRow.removeAllViews()
            if(actions.isNotEmpty()){
                val width=(1280-20*(actions.size-1))/actions.size
                actions.forEachIndexed{i,action->actionRow.addView(NativeAction(action),LayoutParams(width,96).apply{leftMargin=i*(width+20)})}
            }
        }else actions.forEachIndexed{i,action->(actionRow.getChildAt(i) as NativeAction).bind(action)}
        actionRow.visibility=if(actions.isEmpty())GONE else VISIBLE
        if(rebuild)requestLayout()
    }
    fun renderRows(rows:List<Row>) {
        // Retain a field whose stable key survives a filtered catalogue update.
        // Rebuilding the entire list would drop its cursor, focus and draft on each keystroke.
        require(rows.map{it.key}.distinct().size==rows.size) { "Workspace row keys must be unique" }
        val retained=(0 until content.childCount).associate { index ->
            val child=content.getChildAt(index) as WorkspaceRow
            child.rowKey to child
        }
        val keys=rows.mapTo(hashSetOf()){it.key}
        for(index in content.childCount-1 downTo 0)
            if((content.getChildAt(index) as WorkspaceRow).rowKey !in keys)content.removeViewAt(index)
        rows.forEachIndexed { index,row ->
            val child=retained[row.key]?:WorkspaceRow(row)
            if(child.parent==null)content.addView(child,index,LinearLayout.LayoutParams(1280,104))
            else if(content.indexOfChild(child)!=index){content.removeView(child);content.addView(child,index)}
            child.bind(row)
        }
        currentRows=rows
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        paint.color=background;paint.style=Paint.Style.FILL
        canvas.save();canvas.translate(0f,-130f)
        canvas.drawRoundRect(RectF(0f,130f,width.toFloat(),height+130f),4f,4f,paint);canvas.restore()
        canvas.save();canvas.translate(0f,-130f);backdrop.draw(canvas,night);canvas.restore()
        paint.typeface=heavy;paint.color=ink
        paint.textSize=40f*fontScale;canvas.drawText(heading,360f,78f,paint)
        report(title,heading,paint,1280f)
        paint.typeface=regular;paint.color=muted
        paint.textSize=24f*fontScale
        val shown=if(captionRaw&&paint.measureText(caption)>1280f){val count=paint.breakText(caption,true,1280f-paint.measureText("…"),null);caption.take(count)+"…"}else caption
        canvas.drawText(shown,360f,116f,paint)
        report(subtitleSource,caption,paint,1280f,shown,captionRaw)
    }
    private fun report(source:String,text:String,p:Paint,available:Float,drawn:String=text,dynamic:Boolean=false){
        No5WorkspaceDiagnostics.observe?.invoke(No5WorkspaceTextFit(source,text,drawn,p.measureText(text),available,p.textSize,fontScale,dynamic))
    }
    override fun onDetachedFromWindow(){scroll.removeCallbacks(scrollSettled);scrolling=false;scrollObserver?.invoke(scroll.scrollY,false);backdrop.close();super.onDetachedFromWindow()}

    override fun dispatchDraw(canvas:Canvas){
        super.dispatchDraw(canvas)
        val focus=findFocus() as? NativeAction
        if(focus!=null&&focus.isEnabled&&focus.hasWindowFocus()){
            val bounds=focus.designBounds()
            // Use the approved stage coordinate origin for native rounded-rect rasterization.
            canvas.save();canvas.translate(0f,-130f)
            No5ActionInk.focus(canvas,paint,bounds,night,1f);canvas.restore()
        }
    }

    private inner class NativeAction(private var action:Action):View(context){
        private var text=No5Text.text(context,action.label)
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        init {tag="no5-action-${action.key}";contentDescription=No5Text.text(context,action.description?:action.label);isFocusable=true;isClickable=true
            No5TouchFeedback.install(this,android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),
                Color.TRANSPARENT,Color.TRANSPARENT)
            setOnClickListener{this.action.action()};bind(action)}
        fun bind(next:Action){action=next;text=No5Text.text(context,next.label);contentDescription=No5Text.text(context,next.description?:next.label)
            isEnabled=next.enabled;isSelected=next.selected;invalidate()}
        fun designBounds():RectF {
            val stage=android.graphics.Rect(0,0,width,96)
            offsetDescendantRectToMyCoords(this,stage);stage.offset(0,130)
            if(parent===actionRow&&actionRow.childCount==3){
                val parentStage=android.graphics.Rect(0,0,actionRow.width,96)
                offsetDescendantRectToMyCoords(actionRow,parentStage);parentStage.offset(0,130)
                val w=(actionRow.width-40f)/3f;val x=parentStage.left+actionRow.indexOfChild(this)*(w+20f)
                return RectF(x,parentStage.top.toFloat(),x+w,parentStage.top+96f)
            }
            return RectF(stage)
        }
        override fun onDraw(c:Canvas){
            val stage=android.graphics.Rect(0,0,width,96)
            offsetDescendantRectToMyCoords(this,stage);stage.offset(0,130)
            val bounds=designBounds()
            c.save();c.translate(-stage.left.toFloat(),-stage.top.toFloat())
            No5ActionInk.face(c,p,bounds,night,action.selected,
                isEnabled&&hasWindowFocus()&&isPressed,isEnabled&&hasWindowFocus()&&isFocused,1f)
            c.restore()
            p.style=Paint.Style.FILL;p.typeface=bold
            p.textSize=27f*fontScale;p.color=if(isEnabled)ink else muted
            c.drawText(text,bounds.centerX()-stage.left-p.measureText(text)/2,58f,p)
            report(action.label,text,p,width-20f)
        }
        override fun drawableStateChanged(){super.drawableStateChanged();invalidate();this@No5WorkspaceView.invalidate()}
        override fun onWindowFocusChanged(hasWindowFocus:Boolean){super.onWindowFocusChanged(hasWindowFocus);invalidate();this@No5WorkspaceView.invalidate()}
        override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.Button"}
    }
    private inner class WorkspaceRow(private var row:Row):FrameLayout(context){
        val rowKey get()=row.key
        private var field:android.widget.EditText?=null
        private var range:ValueSlider?=null
        private var bindingField=false
        private var label=if(row.rawLabel)row.label else No5Text.text(context,row.label)
        private var detail=row.detail?.let{if(row.rawDetail)it else No5Text.text(context,it)}
        private var value=row.value?.let{if(row.rawValue)it else No5Text.text(context,it)}?:if(row.action!=null)"›"else""
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            setWillNotDraw(false)
            tag="no5-row-${row.key}"
            contentDescription=listOfNotNull(label,detail,value.takeIf{it.isNotBlank()}).joinToString(". ")
            No5TouchFeedback.install(this,android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),
                (accent and 0x00ffffff)or 0x33000000,accent)
            setOnClickListener{row.action?.invoke()};bind(row)
        }
        fun bind(next:Row) {
            row=next
            label=if(row.rawLabel)row.label else No5Text.text(context,row.label)
            detail=row.detail?.let{if(row.rawDetail)it else No5Text.text(context,it)}
            value=row.value?.let{if(row.rawValue)it else No5Text.text(context,it)}?:if(row.action!=null)"›"else""
            isSelected=row.selected;isEnabled=row.enabled
            isFocusable=row.action!=null;isClickable=row.action!=null
            if(row.control=="field"&&field==null){
                field=object:android.widget.EditText(context){
                    // Neutral ink shares the row painter with the frozen design. Once focused,
                    // EditText remains responsible for native selection, caret and editing.
                    override fun onDraw(canvas:Canvas){if(hasFocus()){val saved=canvas.save();canvas.translate(0f,49f-baseline);super.onDraw(canvas);canvas.restoreToCount(saved)}}
                    override fun onFocusChanged(gainFocus:Boolean,direction:Int,previouslyFocusedRect:android.graphics.Rect?){super.onFocusChanged(gainFocus,direction,previouslyFocusedRect);this@WorkspaceRow.invalidate()}
                }.apply {
                    tag="no5-field-${row.key}";setSingleLine(true);background=null
                    typeface=regular;setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,28f*fontScale)
                    setTextColor(if(row.selected)accent else muted);setHintTextColor(muted);com.no5.ui.No5TextSelection.install(this,accent);includeFontPadding=false
                    gravity=android.view.Gravity.RIGHT or android.view.Gravity.CENTER_VERTICAL;setPadding(0,0,0,0)
                    addTextChangedListener(object:android.text.TextWatcher{
                        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                            if(!bindingField)this@WorkspaceRow.row.onValueChange?.invoke(s?.toString().orEmpty())
                        }
                        override fun afterTextChanged(s:android.text.Editable?){}
                    })
                };addView(field,LayoutParams(400,96).apply{leftMargin=860})
            }
            if(row.slider!=null&&range==null){range=ValueSlider();addView(range,LayoutParams(360,96).apply{leftMargin=920})}
            range?.let{it.visibility=if(row.slider!=null)VISIBLE else GONE;it.bind()}
            field?.let{input->
                input.visibility=if(row.control=="field")VISIBLE else GONE;input.isEnabled=row.enabled
                input.setTextColor(if(row.selected)accent else muted)
                input.contentDescription=label
                val nextValue=row.value.orEmpty()
                if(input.text.toString()!=nextValue){bindingField=true;input.setText(nextValue);input.setSelection(nextValue.length);bindingField=false}
            }
            contentDescription=listOfNotNull(label,detail,value.takeIf{it.isNotBlank()}).joinToString(". ")
            invalidate()
        }
        override fun onDraw(c:Canvas){
            p.typeface=bold;p.textSize=30f*fontScale
            p.color=if(isEnabled)ink else muted
            // App-provided names are data. Keep their complete accessibility label even
            // when a pathological installed name exceeds the fixed row's available ink.
            val shown=if(row.rawLabel&&p.measureText(label)>840f){
                val count=p.breakText(label,true,840f-p.measureText("…"),null);label.take(count)+"…"
            }else label
            c.drawText(shown,20f,43f,p)
            report(row.label,label,p,840f,shown,row.rawLabel)
            detail?.let{
                p.typeface=regular;p.textSize=23f*fontScale;p.color=muted
                val available=width-180f
                val shown=if(row.rawDetail&&p.measureText(it)>available){
                    val count=p.breakText(it,true,available-p.measureText("…"),null);it.take(count)+"…"
                }else it
                c.drawText(shown,20f,78f,p)
                report(row.detail!!,it,p,available,shown,dynamic=row.rawDetail)
            }
            p.typeface=regular;p.textSize=28f*fontScale;p.color=if(row.selected)accent else muted
            if(row.control!="field"||field?.hasFocus()!=true){
                val displayed=if(row.control=="field")field?.text?.toString()?:value else value
                c.drawText(displayed,width-20f-p.measureText(displayed),49f,p)
                report(row.value?:"›",displayed,p,400f)
            }
            val origin=android.graphics.Rect(0,0,width,height)
            this@No5WorkspaceView.offsetDescendantRectToMyCoords(this,origin);origin.offset(0,130)
            c.save();c.translate(-origin.left.toFloat(),-origin.top.toFloat())
            fun rect(x1:Float,y1:Float,x2:Float,y2:Float)=RectF(origin.left+x1,origin.top+y1,origin.left+x2,origin.top+y2)
            fun circle(x:Float,y:Float,r:Float){c.drawCircle(origin.left+x,origin.top+y,r,p)}
            fun stroke(x1:Float,y1:Float,x2:Float,y2:Float){c.drawLine(origin.left+x1,origin.top+y1,origin.left+x2,origin.top+y2,p)}
            when(row.control){
                "check","toggle"->{
                    val cx=912f;val cy=48f;p.style=Paint.Style.STROKE;p.strokeWidth=2.5f;p.color=if(row.selected)accent else line
                    if(row.control=="toggle")circle(cx,cy,16f)else c.drawRoundRect(rect(cx-16,cy-16,cx+16,cy+16),3f,3f,p)
                    p.style=Paint.Style.FILL
                    if(row.selected){if(row.control=="toggle")circle(cx,cy,8f)else{p.strokeWidth=3.5f;stroke(cx-9,cy,cx-2,cy+8);stroke(cx-2,cy+8,cx+10,cy-9)}}
                }
                "slider"->{p.color=line;p.strokeWidth=3f;stroke(width-340f,72f,width-20f,72f);p.color=muted;val fraction=row.slider?.let{((it.value-it.start)/(it.end-it.start)).coerceIn(0.0,1.0).toFloat()}?:.5f;p.color=if(range?.isPressed==true)accent else muted;circle(width-340f+320f*fraction,72f,8f)}
                "field"->{p.style=Paint.Style.STROKE;p.color=line;p.strokeWidth=1.5f;c.drawRoundRect(rect(860f,6f,1260f,90f),4f,4f,p);p.style=Paint.Style.FILL}
            }
            c.restore()
            p.color=line;p.strokeWidth=1.5f;c.drawLine(0f,95f,width.toFloat(),95f,p)
        }
        /** Native direct slider; the row label still opens the explicit Auto/Manual controls. */
        private inner class ValueSlider:View(context){
            private var pointer=-1
            private var cancelled=false
            init{isFocusable=true;isClickable=true;tag="no5-slider-${row.key}"}
            fun bind(){contentDescription=label;isEnabled=row.enabled;if(!isEnabled)cancel();invalidate()}
            private fun cancel(){isPressed=false;pointer=-1;cancelled=true;parent?.requestDisallowInterceptTouchEvent(false);this@WorkspaceRow.invalidate()}
            private fun setValue(value:Double):Boolean{
                val slider=row.slider?:return false
                if(!isEnabled||!value.isFinite())return false
                val stepped=(slider.start+kotlin.math.round((value.coerceIn(slider.start,slider.end)-slider.start)/slider.step)*slider.step).coerceIn(slider.start,slider.end)
                slider.change(stepped);return true
            }
            override fun onTouchEvent(event:android.view.MotionEvent):Boolean{
                when(event.actionMasked){
                    android.view.MotionEvent.ACTION_DOWN->{if(!isEnabled||event.pointerCount!=1)return false;pointer=event.getPointerId(0);cancelled=false;isPressed=true;parent?.requestDisallowInterceptTouchEvent(true)}
                    android.view.MotionEvent.ACTION_POINTER_DOWN,android.view.MotionEvent.ACTION_CANCEL->{cancel();return true}
                }
                if(!cancelled&&pointer>=0){
                    val index=event.findPointerIndex(pointer)
                    if(index<0||event.pointerCount!=1||event.getY(index)<0||event.getY(index)>=height){cancel();return true}
                    row.slider?.let{setValue(it.start+((event.getX(index)-20)/320f).coerceIn(0f,1f)*(it.end-it.start))}
                }
                if(event.actionMasked==android.view.MotionEvent.ACTION_UP)cancel()
                this@WorkspaceRow.invalidate();return true
            }
            override fun onDetachedFromWindow(){cancel();super.onDetachedFromWindow()}
            override fun onWindowFocusChanged(hasWindowFocus:Boolean){if(!hasWindowFocus)cancel();super.onWindowFocusChanged(hasWindowFocus)}
            override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){
                super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.SeekBar"
                row.slider?.let{info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,it.start.toFloat(),it.end.toFloat(),it.value.toFloat())}
                if(isEnabled){info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)}
            }
            override fun performAccessibilityAction(action:Int,args:android.os.Bundle?):Boolean{
                val slider=row.slider?:return super.performAccessibilityAction(action,args)
                return when(action){
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id->if(args?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)==true)setValue(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE).toDouble())else false
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->setValue(slider.value+slider.step)
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->setValue(slider.value-slider.step)
                    else->super.performAccessibilityAction(action,args)
                }
            }
        }
        override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);if(row.action!=null){info.className=if(row.control in listOf("check","toggle"))"android.widget.CheckBox"else"android.widget.Button";info.isCheckable=row.control in listOf("check","toggle");info.isChecked=row.selected}}
    }
}
