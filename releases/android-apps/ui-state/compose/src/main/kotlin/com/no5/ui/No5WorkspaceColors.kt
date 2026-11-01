package com.no5.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Complete D03 tokens: nested controls may never inherit a different app's palette. */
fun no5WorkspaceColorScheme(night:Boolean,base:ColorScheme):ColorScheme {
    val bg=if(night)Color.Black else Color(0xfff6f9fa)
    val ink=if(night)Color(0xffed0000)else Color(0xff07121b)
    val muted=if(night)Color(0xffa40000)else Color(0xff526b7b)
    val line=if(night)Color(0xff550000)else Color(0xffb5c2c8)
    val accent=if(night)Color(0xffed0000)else Color(0xffff5b00)
    val chosen=if(night)Color(0xff300000)else Color(0xffffebde)
    return base.copy(primary=accent,onPrimary=bg,primaryContainer=chosen,onPrimaryContainer=ink,
        inversePrimary=accent,secondary=muted,onSecondary=bg,secondaryContainer=chosen,onSecondaryContainer=ink,
        tertiary=accent,onTertiary=bg,tertiaryContainer=chosen,onTertiaryContainer=ink,
        background=bg,onBackground=ink,surface=bg,onSurface=ink,surfaceVariant=bg,onSurfaceVariant=muted,
        surfaceTint=bg,inverseSurface=bg,inverseOnSurface=ink,error=accent,onError=bg,errorContainer=chosen,
        onErrorContainer=ink,outline=line,outlineVariant=line,scrim=Color.Black,
        surfaceBright=bg,surfaceDim=bg,surfaceContainer=bg,surfaceContainerHigh=bg,
        surfaceContainerHighest=bg,surfaceContainerLow=bg,surfaceContainerLowest=bg)
}
