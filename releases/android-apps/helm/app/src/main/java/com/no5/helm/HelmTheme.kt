package com.no5.helm

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/** The shared window palette follows the theme, never a telemetry packet. */
@Composable internal fun rememberHelmColorScheme(night:Boolean) = remember(night) {
    if(night)darkColorScheme(primary=Color(0xFFDC160C),surface=Color.Black,onSurface=Color(0xFFD40000),secondary=Color(0xFFB00000))
    else lightColorScheme(primary=Color(0xFF005DAF),surface=Color(0xFFF6F9FA),onSurface=Color(0xFF071022))

}
