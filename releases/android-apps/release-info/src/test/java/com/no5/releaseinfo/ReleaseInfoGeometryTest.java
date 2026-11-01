package com.no5.releaseinfo;

import android.app.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import com.no5.ui.UiLanguage;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.File;
import java.io.FileOutputStream;
import static org.junit.Assert.*;

/** Canonical native D03 sheet bounds and fixed fonts; separate-window OEM/IME stays instrumentation scope. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk={29,35},manifest=Config.NONE,qualifiers="w1000dp-h600dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReleaseInfoGeometryTest {
    private static void layout(View view){view.measure(View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1200,View.MeasureSpec.EXACTLY));view.layout(0,0,2000,1200);}
    @Test public void longInfoKeepsTheHeaderAndFooterFreeAndOnlyTheBodyScrolls()throws Exception{
        String initial=UiLanguage.current(RuntimeEnvironment.getApplication());float previous=RuntimeEnvironment.getFontScale();
        try{
            for(String language:new String[]{"de","en","af"})for(boolean night:new boolean[]{false,true})for(float font:new float[]{1f,1.25f}){
                RuntimeEnvironment.setFontScale(font);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),language);
                try(org.robolectric.android.controller.ActivityController<Activity> host=Robolectric.buildActivity(Activity.class).setup().visible().windowFocusChanged(true)){
                    Activity activity=host.get();StringBuilder raw=new StringBuilder();for(int i=0;i<100;i++)raw.append("OBSERVED_RAW_").append(i).append("\n");
                    AlertDialog dialog=ReleaseInfo.showDetails(activity,raw.toString(),night);
                    try{
                        ViewGroup sheet=dialog.getWindow().getDecorView().findViewWithTag("release-info-sheet");assertNotNull(sheet);
                        ((ViewGroup)sheet.getParent()).removeView(sheet);activity.setContentView(sheet);host.visible().windowFocusChanged(true);layout(sheet);
                        TextView title=sheet.findViewWithTag("release-info-title"),body=sheet.findViewWithTag("release-info-body");
                        ScrollView scroll=sheet.findViewWithTag("release-info-scroll");Button close=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                        assertEquals(468,title.getLeft());assertEquals(1064,title.getWidth());assertEquals(86,title.getHeight());assertEquals(58,title.getBaseline());
                        assertTrue(title.getTop()>=130);assertTrue(close.getBottom()<=1058);
                        assertEquals(468,scroll.getLeft());assertEquals(1064,scroll.getWidth());assertEquals(title.getTop()+106,scroll.getTop());
                        assertEquals(468,close.getLeft());assertEquals(1044,close.getWidth());assertEquals(96,close.getHeight());
                        assertEquals(35f*font,title.getTextSize(),0f);assertEquals(29f*font,body.getTextSize(),0f);assertEquals(27f*font,close.getTextSize(),0f);
                        assertEquals(night?0xffed0000:0xff07121b,body.getCurrentTextColor());assertTrue(body.getText().toString().endsWith(raw.toString()));
                        int top=title.getTop(),action=close.getTop();scroll.scrollTo(0,100000);layout(sheet);assertEquals(top,title.getTop());assertEquals(action,close.getTop());assertTrue(scroll.getScrollY()>0);
                        File folder=new File(System.getProperty("no5.renderOutputDir"),"native-info-api"+RuntimeEnvironment.getApiLevel());folder.mkdirs();Bitmap image=Bitmap.createBitmap(2000,1200,Bitmap.Config.ARGB_8888);
                        try{sheet.draw(new Canvas(image));try(FileOutputStream stream=new FileOutputStream(new File(folder,language+"-"+night+"-"+font+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,stream));}}finally{image.recycle();}
                    }finally{activity.setContentView(new FrameLayout(activity));dialog.dismiss();}
                }
            }
        }finally{RuntimeEnvironment.setFontScale(previous);UiLanguage.acceptConfirmed(RuntimeEnvironment.getApplication(),initial);}
    }
}
