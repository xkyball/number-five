package com.no5.ui;

import android.content.res.AssetManager;
import android.graphics.*;
import java.io.InputStream;
import java.io.IOException;

/** Canonical D03 transition at the existing y=130 header edge. Never used on the main chart. */
public final class No5PanelBackdrop implements AutoCloseable {
    private final AssetManager assets;
    private Bitmap ocean;
    private final RectF target=new RectF(400f,0f,1600f,704f);
    private final Rect source=new Rect();
    private final Paint image=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint sky=new Paint(),horizontal=new Paint(),fade=new Paint();
    public No5PanelBackdrop(AssetManager assets){
        this.assets=assets;sky.setColor(Color.rgb(185,222,246));
        horizontal.setShader(new LinearGradient(0f,0f,2000f,0f,new int[]{Color.TRANSPARENT,Color.TRANSPARENT,Color.BLACK,Color.BLACK,Color.TRANSPARENT,Color.TRANSPARENT},new float[]{0f,.19f,.25f,.75f,.81f,1f},Shader.TileMode.CLAMP));
        horizontal.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        fade.setShader(new LinearGradient(0f,130f,0f,190f,Color.BLACK,Color.TRANSPARENT,Shader.TileMode.CLAMP));
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }
    public void draw(Canvas canvas,boolean night){
        if(night)return;
        if(ocean==null){
            try(InputStream stream=assets.open("no5-header/ocean.png")){ocean=BitmapFactory.decodeStream(stream);}
            catch(IOException failed){throw new IllegalStateException("Missing approved panel backdrop",failed);}
            if(ocean==null)throw new IllegalStateException("Invalid approved panel backdrop");
            float scale=Math.max(target.width()/ocean.getWidth(),target.height()/ocean.getHeight());
            float sw=target.width()/scale,sh=target.height()/scale;
            source.set(Math.round((ocean.getWidth()-sw)/2),Math.round((ocean.getHeight()-sh)/2),Math.round((ocean.getWidth()+sw)/2),Math.round((ocean.getHeight()+sh)/2));
        }
        int saved=canvas.save();canvas.clipRect(0f,130f,2000f,190f);
        int layer=canvas.saveLayer(0f,130f,2000f,190f,null);
        canvas.drawRect(0f,130f,2000f,190f,sky);canvas.drawBitmap(ocean,source,target,image);
        canvas.drawRect(0f,130f,2000f,190f,horizontal);canvas.drawRect(0f,130f,2000f,190f,fade);
        canvas.restoreToCount(layer);canvas.restoreToCount(saved);
    }
    @Override public void close(){if(ocean!=null)ocean.recycle();ocean=null;}
}
