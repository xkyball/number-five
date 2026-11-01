package com.no5.ui

import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.no5.header.artwork.No5WorkspaceView

/** D03 1000-pixel form grammar. Fields are caller-owned drafts; closing never applies them. */
data class No5FormField(val label:String,val value:String,val key:String,val enabled:Boolean=true,
    val keyboard:KeyboardOptions=KeyboardOptions.Default,val onChange:(String)->Unit)

@Composable fun No5FormDialog(title:String,fields:List<No5FormField>,night:Boolean,onDismiss:()->Unit,
    actions:List<No5WorkspaceView.Action>,tag:String,notice:String?=null) {
    val context=LocalContext.current
    val bold=remember(context){Typeface.createFromAsset(context.assets,"no5-header/barlow_condensed_semibold.ttf")}
    val face=remember(bold){FontFamily(bold)}
    val ink=if(night)Color(0xffed0000)else Color(0xff07121b)
    No5DetailsDialog(title,night,onDismiss,actions=actions,tag=tag,form=true){
        BoxWithConstraints(Modifier.fillMaxWidth()){
            val scale=maxWidth.value/944f
            Column{
                fields.forEach{field->key(field.key){
                    No5FormFieldRow(field,night,scale)
                }}
                notice?.let{Text(it,Modifier.padding(vertical=(12*scale).dp).testTag("$tag-notice"),fontFamily=face,
                    fontSize=(24*scale).sp,lineHeight=(30*scale).sp,color=ink)}
            }
        }
    }
}
