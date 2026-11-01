package com.no5.ui

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One D03 field row for both standalone forms and inset detail-sheet forms.
 * The owner's raw draft and native editing/selection remain the sole value source. */
@Composable fun No5FormFieldRow(field:No5FormField,night:Boolean,scale:Float,
    designWidth:Float=944f,labelX:Float=0f,fieldX:Float=582f) {
    val context=LocalContext.current;val density=LocalDensity.current;val fontScale=density.fontScale
    val bold=remember(context){Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_semibold.ttf")}
    val ink=if(night)0xffed0000.toInt()else 0xff07121b.toInt()
    val line=if(night)0xff550000.toInt()else 0xffb5c2c8.toInt()
    val p=remember{Paint(Paint.ANTI_ALIAS_FLAG)}
    val label=No5Text.text(context,field.label)
    var location by remember{mutableStateOf(Offset.Zero)}
    Box(Modifier.fillMaxWidth().height((104*scale).dp)) {
        Canvas(Modifier.fillMaxSize().onGloballyPositioned{val next=it.positionInRoot();if(location!=next)location=next}){
            val n=drawContext.canvas.nativeCanvas;val u=size.width/designWidth;n.save();n.scale(u,u)
            val origin=Offset(location.x/u,location.y/u)
            p.style=Paint.Style.FILL;p.color=ink;p.typeface=bold;p.textSize=28f*fontScale
            n.drawText(label,labelX,53f,p)
            No5DialogDiagnostics.observe?.invoke(No5DialogTextFit(field.key+"-label",label,p.textSize,fontScale,530f,
                RectF(origin.x+labelX,origin.y+53+p.ascent(),origin.x+labelX+p.measureText(label),origin.y+53+p.descent()),p.color))
            p.style=Paint.Style.STROKE;p.color=line;p.strokeWidth=1.5f;n.translate(-origin.x,-origin.y)
            n.drawRoundRect(RectF(origin.x+fieldX,origin.y,origin.x+fieldX+360f,origin.y+96f),4f,4f,p);n.restore()
        }
        No5DraftTextField(field.value,field.onChange,field.key,field.enabled,field.keyboard,night,scale,
            Modifier.offset(((fieldX+20)*scale).dp).size((320*scale).dp,(96*scale).dp).semantics{contentDescription=label},
            description=label)

    }
}

/** A single editable owner value with exact neutral ink and native caret/selection while focused. */
@Composable fun No5DraftTextField(value:String,onChange:(String)->Unit,tag:String,enabled:Boolean,
    keyboard:androidx.compose.foundation.text.KeyboardOptions,night:Boolean,scale:Float,modifier:Modifier,
    designWidth:Float=320f,fontPx:Float=28f,baselineY:Float=56f,description:String="") {
    val context=LocalContext.current;val density=LocalDensity.current;val fontScale=density.fontScale
    val regular=remember(context){Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_regular.ttf")}
    val face=remember(regular){FontFamily(regular)}
    val ink=if(night)0xffed0000.toInt()else 0xff07121b.toInt()
    val accent=if(night)Color(0xffed0000)else Color(0xffff5b00)
    val p=remember{Paint(Paint.ANTI_ALIAS_FLAG)}
    var baseline by remember{mutableFloatStateOf(0f)}
    var focused by remember{mutableStateOf(false)}
    val label=description
    CompositionLocalProvider(androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
        androidx.compose.foundation.text.selection.TextSelectionColors(accent,accent.copy(alpha=.33f))){
        BasicTextField(value,onChange,enabled=enabled,singleLine=true,
            keyboardOptions=keyboard,cursorBrush=SolidColor(accent),
            textStyle=TextStyle(fontFamily=face,fontSize=(fontPx*scale).sp,letterSpacing=0.sp,color=Color(ink)),
            onTextLayout={val next=it.firstBaseline;if(baseline!=next)baseline=next},
            modifier=modifier.testTag(tag)
                .onFocusChanged{focused=it.hasFocus}.semantics{contentDescription=label},
            decorationBox={input->Box(Modifier.fillMaxSize()){
                // Neutral ink matches the shared native Canvas labels exactly. The real
                // inner field stays composed for accessibility, caret and selection.
                if(!focused||value.isEmpty())Canvas(Modifier.fillMaxSize()){
                    val n=drawContext.canvas.nativeCanvas;val u=size.width/designWidth;n.save();n.scale(u,u)
                    p.style=Paint.Style.FILL;p.color=ink;p.typeface=regular;p.textSize=fontPx*fontScale
                    n.clipRect(0f,0f,designWidth,96f);n.drawText(value.ifEmpty{"—"},0f,baselineY,p);n.restore()
                }
                Box(Modifier.graphicsLayer{translationY=baselineY*scale*density.density-baseline;alpha=if(focused)1f else 0f}){input()}
            }})
    }
}
