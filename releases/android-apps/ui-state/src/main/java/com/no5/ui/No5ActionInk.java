package com.no5.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Calibrated D03 action face. Callers draw text afterwards; feedback never tints ink. */
public final class No5ActionInk {
    private No5ActionInk() {}
    public static void face(Canvas canvas, Paint paint, RectF bounds, boolean night,
            boolean selected, boolean pressed, boolean focused, float unit) {
        int accent=night?0xffed0000:0xffff5b00;
        paint.setStyle(Paint.Style.FILL);
        if(selected||pressed){paint.setColor(night?0xff300000:0xffffebde);canvas.drawRoundRect(bounds,4*unit,4*unit,paint);}
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*unit);
        paint.setColor(selected||focused?accent:night?0xff550000:0xffb5c2c8);
        canvas.drawRoundRect(bounds,4*unit,4*unit,paint);paint.setStyle(Paint.Style.FILL);
    }
    public static void focus(Canvas canvas,Paint paint,RectF bounds,boolean night,float unit){
        RectF outer=new RectF(bounds);outer.inset(-5*unit,-5*unit);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*unit);
        paint.setColor(night?0xffed0000:0xffff5b00);canvas.drawRoundRect(outer,4*unit,4*unit,paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
