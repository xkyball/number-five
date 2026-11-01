package com.no5.home

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.no5.ui.No5Text
import com.no5.ui.No5TouchFeedback

/** HOME is the only language writer. Checked state always comes from the confirmed broker. */
internal class HomeLanguageDialog(context:Context,private val night:Boolean,confirmed:String,
    message:String,private val choose:(String)->Unit):Dialog(context) {
    private val activity=context as? android.app.Activity
    val choices=RadioGroup(context).apply{orientation=RadioGroup.VERTICAL}
    val notice:TextView
    private val bg=if(night)0xff000000.toInt()else 0xfff6f9fa.toInt()
    private val ink=if(night)0xffed0000.toInt()else 0xff07121b.toInt()
    private val muted=if(night)0xffa40000.toInt()else 0xff526b7b.toInt()
    private val line=if(night)0xff550000.toInt()else 0xffb5c2c8.toInt()
    private val accent=if(night)0xffed0000.toInt()else 0xffff5b00.toInt()
    private var closeAction:View?=null
    private val backdrop=com.no5.header.artwork.No5WorkspaceBackdrop(context.assets)
    private fun report(source:String,text:String,p:Paint,available:Float){
        HomeTemplateDiagnostics.observe?.invoke(HomeTemplateTextFit(source,text,text,p.measureText(text),available,p.textSize,context.resources.configuration.fontScale))
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val stage=HomeStage(context,night,widthScaleOnly=true).apply{clipChildren=false}
        stage.place(object:View(context){
            private val p=Paint()
            private var armed=false
            init {
                isClickable=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                No5TouchFeedback.install(this,ColorDrawable(Color.TRANSPARENT),Color.TRANSPARENT,Color.TRANSPARENT)
            }
            private fun inside(e:MotionEvent)=e.x in 440f..1560f && e.y in 240f..860f
            override fun onTouchEvent(e:MotionEvent):Boolean {
                if(e.actionMasked==MotionEvent.ACTION_DOWN){armed=!inside(e);if(!armed)return false}
                if(e.actionMasked==MotionEvent.ACTION_CANCEL||e.actionMasked==MotionEvent.ACTION_POINTER_DOWN||inside(e)&&e.actionMasked!=MotionEvent.ACTION_DOWN)armed=false
                return super.onTouchEvent(e)
            }
            override fun performClick():Boolean {if(!armed)return false;armed=false;super.performClick();dismiss();return true}
            override fun onDraw(c:Canvas){p.color=(bg and 0xffffff)or(241 shl 24);c.drawRect(0f,130f,2000f,1058f,p);backdrop.draw(c,night)}
        },0f,0f,2000,1200)
        val pane=object:FrameLayout(context){
            private val p=Paint(Paint.ANTI_ALIAS_FLAG)
            init{setWillNotDraw(false)}
            override fun onDraw(c:Canvas){
                c.save();c.translate(-440f,-240f)
                p.style=Paint.Style.FILL;p.color=bg;c.drawRoundRect(RectF(440f,240f,1560f,860f),4f,4f,p)
                p.style=Paint.Style.STROKE;p.color=line;p.strokeWidth=1.5f;c.drawRoundRect(RectF(440f,240f,1560f,860f),6f,6f,p);c.restore()
                p.style=Paint.Style.FILL;p.color=ink;p.typeface=resources.getFont(R.font.barlow_condensed_extrabold)
                p.textSize=35f*resources.configuration.fontScale;c.drawText(No5Text.text(context,"Sprache für alle Apps"),28f,58f,p)
                report("Sprache für alle Apps",No5Text.text(context,"Sprache für alle Apps"),p,1064f)
                p.color=line;p.strokeWidth=1.5f;c.drawLine(28f,86f,1092f,86f,p)
            }
            override fun dispatchDraw(c:Canvas){
                super.dispatchDraw(c)
                closeAction?.takeIf{it.isEnabled&&it.hasWindowFocus()&&it.isFocused}?.let{
                    c.save();c.translate(-440f,-240f)
                    com.no5.ui.No5ActionInk.focus(c,p,RectF(1220f,744f,1532f,840f),night,1f);c.restore()
                }
            }
        }
        val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        No5Text.languages().forEachIndexed{index,code->
            choices.addView(object:RadioButton(context){
                private val p=Paint(Paint.ANTI_ALIAS_FLAG)
                init{
                    tag=code;text=No5Text.languageName(code);buttonDrawable=null;isChecked=code==confirmed
                    isFocusable=true;isClickable=true
                    contentDescription=No5Text.text(context,"Sprache für alle Apps")+": "+No5Text.languageName(code)
                    No5TouchFeedback.install(this,ColorDrawable(Color.TRANSPARENT),(accent and 0xffffff)or 0x33000000,accent)
                    setOnClickListener{choose(code)}
                }
                override fun onDraw(c:Canvas){
                    p.style=Paint.Style.STROKE;p.strokeWidth=2.5f;p.color=if(isChecked)accent else line
                    c.save();c.translate(-440f,-346f-index*104f)
                    c.drawCircle(516f,394f+index*104f,16f,p)
                    p.style=Paint.Style.FILL;if(isChecked)c.drawCircle(516f,394f+index*104f,8f,p);c.restore()
                    p.typeface=resources.getFont(R.font.barlow_condensed_semibold);p.textSize=31f*resources.configuration.fontScale;p.color=ink
                    c.drawText(No5Text.languageName(code),150f,58f,p)
                    report(code,No5Text.languageName(code),p,910f)
                    p.color=line;p.strokeWidth=1.5f;c.drawLine(28f,102f,1092f,102f,p)
                }
            },RadioGroup.LayoutParams(1120,104))
        }
        body.addView(choices)
        notice=object:TextView(context){
            private val p=Paint(Paint.ANTI_ALIAS_FLAG)
            init{tag="home-language-notice";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;text=message}
            override fun onDraw(c:Canvas){
                p.typeface=resources.getFont(R.font.barlow_condensed_regular);p.textSize=25f*resources.configuration.fontScale;p.color=ink
                var row="";var y=40f
                for(word in text.toString().split(' ')){
                    val next=if(row.isEmpty())word else "$row $word"
                    if(p.measureText(next)>1064&&row.isNotEmpty()){c.drawText(row,28f,y,p);report(text.toString(),row,p,1064f);y+=p.textSize*1.35f;row=word}else row=next
                }
                if(row.isNotEmpty()){c.drawText(row,28f,y,p);report(text.toString(),row,p,1064f)}
            }
        }
        body.addView(notice,LinearLayout.LayoutParams(1120,86))
        pane.addView(ScrollView(context).apply{addView(body);isFillViewport=false},FrameLayout.LayoutParams(1120,398).apply{topMargin=106})
        val close=object:View(context){
            private val p=Paint(Paint.ANTI_ALIAS_FLAG)
            init{
                tag="home-language-close";contentDescription=No5Text.text(context,"Schließen");isFocusable=true;isClickable=true
                No5TouchFeedback.install(this,ColorDrawable(Color.TRANSPARENT),(accent and 0xffffff)or 0x33000000,accent)
                setOnClickListener{dismiss()}
            }
            override fun onDraw(c:Canvas){
                c.save();c.translate(-1220f,-744f)
                com.no5.ui.No5ActionInk.face(c,p,RectF(1220f,744f,1532f,840f),night,false,
                    isEnabled&&hasWindowFocus()&&isPressed,isEnabled&&hasWindowFocus()&&isFocused,1f);c.restore()
                p.style=Paint.Style.FILL;p.typeface=resources.getFont(R.font.barlow_condensed_semibold)
                p.textSize=27f*resources.configuration.fontScale;p.color=ink
                val label=No5Text.text(context,"Schließen");c.drawText(label,(312-p.measureText(label))/2,58f,p)
                report("Schließen",label,p,288f)
            }
        }
        closeAction=close
        pane.clipChildren=false
        pane.addView(close,FrameLayout.LayoutParams(312,96).apply{leftMargin=780;topMargin=504})
        pane.tag="home-language-dialog"
        stage.place(pane,440f,240f,1120,620)
        setContentView(stage,ViewGroup.LayoutParams(-1,-1))
        window?.apply{setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));setDimAmount(0f)}
        setCanceledOnTouchOutside(true)
    }
    @Suppress("DEPRECATION")
    override fun onStart(){
        super.onStart();window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
        activity?.let{window?.decorView?.systemUiVisibility=it.window.decorView.systemUiVisibility}
    }
    override fun dismiss(){super.dismiss();backdrop.close()}
}
