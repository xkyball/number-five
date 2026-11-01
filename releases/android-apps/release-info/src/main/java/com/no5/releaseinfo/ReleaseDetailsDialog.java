package com.no5.releaseinfo;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.*;
import android.os.Build;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import com.no5.ui.No5PanelBackdrop;
import com.no5.ui.No5TouchFeedback;
import com.no5.ui.No5ActionInk;

/** The D03 detail geometry in native Views, including the Java-only Control application.
 * InfoLayer retains ownership of content, language, RSSI and lifecycle subscriptions. */
final class ReleaseDetailsDialog extends AlertDialog {
    final TextView heading,body;
    final ScrollView scroll;
    private final BaselineButton close;
    private final Sheet sheet;
    ReleaseDetailsDialog(Activity activity,boolean night,TextView heading,TextView body,ScrollView scroll){
        super(activity);this.heading=heading;this.body=body;this.scroll=scroll;
        close=new BaselineButton(activity);close.setTag("release-info-close");close.setAllCaps(false);
        close.setMinimumWidth(0);close.setMinimumHeight(0);close.setMinWidth(0);close.setMinHeight(0);
        close.setPadding(0,0,0,0);close.setLetterSpacing(0f);close.setStateListAnimator(null);close.setElevation(0f);close.setIncludeFontPadding(false);close.setGravity(Gravity.CENTER);
        close.setOnClickListener(new View.OnClickListener(){public void onClick(View ignored){dismiss();}});
        sheet=new Sheet(activity,night);setView(sheet,0,0,0,0);
        setCanceledOnTouchOutside(false);
    }
    @Override public Button getButton(int which){return which==BUTTON_POSITIVE?close:super.getButton(which);}
    void configureWindow(){
        Window window=getWindow();if(window==null)return;
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(0f);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.getDecorView().setPadding(0,0,0,0);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if(Build.VERSION.SDK_INT>=30){window.setDecorFitsSystemWindows(false);WindowInsetsController insets=window.getInsetsController();if(insets!=null){insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);insets.hide(WindowInsets.Type.systemBars());}}
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);
    }
    private static final class BaselineButton extends Button {
        float unit=1f;
        BaselineButton(Activity activity){super(activity);}
        @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();if(getParent() instanceof View)((View)getParent()).invalidate();}
        @Override protected void onDraw(Canvas canvas){int saved=canvas.save();canvas.translate(0f,58f*unit-getBaseline());super.onDraw(canvas);canvas.restoreToCount(saved);}
    }
    private final class Sheet extends FrameLayout {
        private final boolean night,clouds;
        private final int surface,ink,line,accent;
        private final Typeface heavy,regular,bold;
        private final No5PanelBackdrop backdrop;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF panel=new RectF(),button=new RectF();
        private float unit=1f;
        private boolean downOutside;
        private float downX,downY;
        Sheet(Activity activity,boolean night){
            super(activity);this.night=night;clouds=!"com.no5.lumina.control".equals(activity.getPackageName());
            surface=night?0xff000000:0xfff6f9fa;ink=night?0xffed0000:0xff07121b;line=night?0xff550000:0xffb5c2c8;accent=night?0xffed0000:0xffff5b00;
            heavy=font(activity,"barlow_condensed_extrabold.ttf",Typeface.DEFAULT_BOLD);
            regular=font(activity,"barlow_condensed_regular.ttf",Typeface.DEFAULT);
            bold=font(activity,"barlow_condensed_semibold.ttf",Typeface.DEFAULT_BOLD);
            backdrop=new No5PanelBackdrop(activity.getAssets());
            setWillNotDraw(false);setClipChildren(false);setTag("release-info-sheet");setClickable(true);
            heading.setTypeface(heavy);heading.setTextColor(ink);heading.setIncludeFontPadding(false);heading.setBackgroundColor(Color.TRANSPARENT);heading.setAccessibilityHeading(true);
            body.setTypeface(regular);body.setTextColor(ink);body.setIncludeFontPadding(false);body.setBackgroundColor(Color.TRANSPARENT);body.setTextIsSelectable(true);com.no5.ui.No5TextSelection.install(body,accent);
            close.setTypeface(bold);close.setTextColor(ink);
            No5TouchFeedback.install(close,new android.graphics.drawable.Drawable(){
                private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
                @Override public void draw(Canvas canvas){RectF bounds=new RectF(getBounds());p.setStyle(Paint.Style.FILL);p.setColor(surface);canvas.drawRoundRect(bounds,4*unit,4*unit,p);RectF logical=new RectF(bounds);logical.offset(close.getLeft(),close.getTop());canvas.save();canvas.translate(-close.getLeft(),-close.getTop());No5ActionInk.face(canvas,p,logical,night,false,close.isEnabled()&&close.hasWindowFocus()&&close.isPressed(),close.isEnabled()&&close.hasWindowFocus()&&close.isFocused(),unit);canvas.restore();}
                @Override public void setAlpha(int alpha){}
                @Override public void setColorFilter(ColorFilter filter){}
                @Override public int getOpacity(){return PixelFormat.OPAQUE;}
            },Color.TRANSPARENT,Color.TRANSPARENT);
            addView(heading);addView(scroll);addView(close);
        }
        private Typeface font(Activity activity,String name,Typeface fallback){try{return Typeface.createFromAsset(activity.getAssets(),"no5-header/"+name);}catch(RuntimeException missing){return fallback;}}
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec),height=MeasureSpec.getSize(heightSpec);setMeasuredDimension(width,height);
            unit=width/2000f;close.unit=unit;float font=getResources().getConfiguration().fontScale;
            heading.setTextSize(TypedValue.COMPLEX_UNIT_PX,35f*unit*font);body.setTextSize(TypedValue.COMPLEX_UNIT_PX,29f*unit*font);close.setTextSize(TypedValue.COMPLEX_UNIT_PX,27f*unit*font);
            heading.setPadding(0,Math.max(0,Math.round(58*unit+heading.getPaint().ascent())),0,0);
            body.setPadding(0,Math.max(0,Math.round(34*unit+body.getPaint().ascent())),0,0);body.setLineSpacing(0f,1.35f);
            body.measure(MeasureSpec.makeMeasureSpec(Math.round(1064*unit),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            float bottom=Math.min(1058*unit,height);
            float panelHeight=Math.min(Math.max(410*unit,246*unit+body.getMeasuredHeight()),Math.max(222*unit,bottom-178*unit));
            float top=Math.min(240*unit,Math.max(0,bottom-panelHeight-24*unit));
            panel.set(440*unit,top,1560*unit,top+panelHeight);
            heading.measure(MeasureSpec.makeMeasureSpec(Math.round(1064*unit),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.round(86*unit),MeasureSpec.EXACTLY));
            scroll.measure(MeasureSpec.makeMeasureSpec(Math.round(1064*unit),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(0,Math.round(panelHeight-222*unit)),MeasureSpec.EXACTLY));
            close.measure(MeasureSpec.makeMeasureSpec(Math.round(312*unit),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.round(96*unit),MeasureSpec.EXACTLY));
        }
        private void place(View view,float x,float y){int left=Math.round(x),top=Math.round(y);view.layout(left,top,left+view.getMeasuredWidth(),top+view.getMeasuredHeight());}
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            place(heading,panel.left+28*unit,panel.top);place(scroll,panel.left+28*unit,panel.top+106*unit);
            place(close,panel.right-340*unit,panel.bottom-116*unit);button.set(close.getLeft(),close.getTop(),close.getRight(),close.getBottom());
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);paint.setStyle(Paint.Style.FILL);paint.setColor((surface&0xffffff)|0xf1000000);canvas.drawRect(0,130*unit,getWidth(),Math.min(1058*unit,getHeight()),paint);
            if(clouds){int saved=canvas.save();canvas.scale(unit,unit);backdrop.draw(canvas,night);canvas.restoreToCount(saved);}
            paint.setColor(surface);canvas.drawRoundRect(panel,6*unit,6*unit,paint);paint.setStyle(Paint.Style.STROKE);paint.setColor(line);paint.setStrokeWidth(1.5f*unit);canvas.drawRoundRect(panel,6*unit,6*unit,paint);
            canvas.drawLine(panel.left+28*unit,panel.top+86*unit,panel.right-28*unit,panel.top+86*unit,paint);
            paint.setStyle(Paint.Style.FILL);
        }
        @Override protected void dispatchDraw(Canvas canvas){super.dispatchDraw(canvas);if(close.isEnabled()&&close.hasWindowFocus()&&close.isFocused())No5ActionInk.focus(canvas,paint,button,night,unit);}
        @Override public boolean onTouchEvent(android.view.MotionEvent event){
            int action=event.getActionMasked();if(action==android.view.MotionEvent.ACTION_DOWN){downOutside=!panel.contains(event.getX(),event.getY());downX=event.getX();downY=event.getY();}
            if(event.getPointerCount()!=1||action==android.view.MotionEvent.ACTION_CANCEL||Math.hypot(event.getX()-downX,event.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop())downOutside=false;
            if(action==android.view.MotionEvent.ACTION_UP){boolean dismiss=downOutside&&!panel.contains(event.getX(),event.getY());downOutside=false;if(dismiss)ReleaseDetailsDialog.this.dismiss();}
            return true;
        }
        @Override protected void onDetachedFromWindow(){backdrop.close();super.onDetachedFromWindow();}
    }
}
