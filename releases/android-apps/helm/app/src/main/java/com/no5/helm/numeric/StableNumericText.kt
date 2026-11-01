package com.no5.helm.numeric

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** Retains the current Compose font/style. A whole number is one unbreakable, measured inline run. */
@Composable
fun StableNumericText(value: String,slots: NumericSlots,modifier: Modifier=Modifier,
    style: TextStyle=LocalTextStyle.current,following: NumericSlots=slots) {
    val measurer=rememberTextMeasurer()
    val actual=LocalTextStyle.current.merge(style)
    val fontSize=if(actual.fontSize.isSp)actual.fontSize else 16.sp
    val pitch=remember(actual,measurer) { ('0'..'9').maxOf { measurer.measure(it.toString(),actual).size.width }.toFloat() }
    val density=androidx.compose.ui.platform.LocalDensity.current
    val cells=remember(value,slots,following){NumericCells.place(value,slots,following)}
    val groups=remember(cells) {
        val groups=mutableListOf<List<NumericCell>>();val digits=mutableListOf<NumericCell>()
        for(cell in cells) if(cell.fixed)digits.add(cell) else {
            if(digits.isNotEmpty()){groups.add(digits.toList());digits.clear()}
            groups.add(listOf(cell))
        }
        if(digits.isNotEmpty())groups.add(digits.toList())
        groups
    }
    BoxWithConstraints(modifier.clearAndSetSemantics { text=AnnotatedString(value) }) {
        val inline=HashMap<String,InlineTextContent>()
        val maxPx=with(density){maxWidth.toPx()}
        val annotated=buildAnnotatedString {
            groups.forEachIndexed { i,group ->
                if(!group.first().fixed)append(group.first().text) else {
                    val factors=group.sumOf{it.widthFactor.toDouble()}.toFloat()
                    val scale=minOf(1f,maxPx/(pitch*factors).coerceAtLeast(1f))
                    val cellPitch=with(density){(pitch*scale).toSp()}
                    val id="number$i"
                    appendInlineContent(id,group.filter{it.visible}.joinToString(""){it.text})
                    inline[id]=InlineTextContent(Placeholder(cellPitch*factors,fontSize*scale,PlaceholderVerticalAlign.TextCenter)) {
                        Row(Modifier.fillMaxSize(),verticalAlignment=Alignment.CenterVertically) {
                            for(cell in group) Box(Modifier.width(with(density){(pitch*scale*cell.widthFactor).toDp()}).fillMaxHeight(),contentAlignment=Alignment.Center) {
                                val glyphWidth=measurer.measure(cell.text,actual).size.width.coerceAtLeast(1)
                                val glyphFit=minOf(1f,pitch*cell.widthFactor/glyphWidth)
                                Text(if(cell.visible)cell.text else "",style=actual.copy(fontSize=fontSize*scale*glyphFit),maxLines=1,softWrap=false,overflow=TextOverflow.Visible)
                            }
                        }
                    }
                }
            }
        }
        Text(annotated,style=actual,inlineContent=inline)
    }
}
