package com.no5.preparation.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp

private val Barlow = FontFamily(
    Font(R.font.barlow_condensed_regular, FontWeight.Normal),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold),
)
@Composable fun No5Theme(redNight: Boolean, content: @Composable () -> Unit) {
    val ink = if (redNight) Color(0xFFDA8076) else Color(0xFF071B35)
    val panel = if (redNight) Color(0xFF190D0E) else Color(0xFFF5FAFC)
    val colors = if (redNight) darkColorScheme(
        primary = Color(0xFFEF886D), onPrimary = Color(0xFF190D0E), background = Color(0xFF100708), onBackground = ink,
        surface = panel, onSurface = ink, surfaceVariant = Color(0xFF301719), onSurfaceVariant = ink,
        secondary = ink, secondaryContainer = Color(0xFF5A2724), onSecondaryContainer = ink, surfaceTint = panel, outlineVariant = Color(0xFF693430), outline = Color(0xFF92483F), error = Color(0xFFF39C86), onError = panel,
        surfaceContainer = panel, surfaceContainerHigh = panel, surfaceContainerHighest = panel,
    ) else lightColorScheme(
        primary = Color(0xFFD94A00), onPrimary = Color.White, background = Color(0xFF091D30), onBackground = Color.White,
        surface = panel, onSurface = ink, surfaceVariant = Color(0xFFE1EDF2), onSurfaceVariant = Color(0xFF315B72),
        secondary = ink, secondaryContainer = Color(0xFFFFD9BF), onSecondaryContainer = ink, surfaceTint = panel, outlineVariant = Color(0xFFADC6D1), outline = Color(0xFF83A8BA), error = Color(0xFFAB281A),
        surfaceContainer = panel, surfaceContainerHigh = panel, surfaceContainerHighest = panel,
    )
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp),
        headlineMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp),
        headlineSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp),
        titleLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
        titleMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        bodyLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 20.sp),
        bodyMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 18.sp),
        bodySmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Normal, fontSize = 16.sp),
        labelLarge = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
        labelMedium = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        labelSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    ), content = { CompositionLocalProvider(com.no5.ui.LocalNo5ActionFeedbackColor provides colors.primary, content = content) })
}

/** Approved panel/control tokens. Shell header and rail deliberately keep No5Theme. */
@Composable fun No5ControlTheme(redNight: Boolean, content: @Composable () -> Unit) {
    val ink = if (redNight) Color(0xFFD40000) else Color(0xFF07121B)
    val panel = if (redNight) Color(0xFF090000) else Color(0xFFF6F8F7)
    val secondaryInk = if (redNight) Color(0xFFB00000) else Color(0xFF344B59)
    val line = if (redNight) Color(0xFF700000) else Color(0xFF90A0A7)
    val accent = if (redNight) Color(0xFFFF1A0A) else Color(0xFFFF5B00)
    val face = if (redNight) Color(0xFF140000) else Color.White
    val colors = (if (redNight) darkColorScheme() else lightColorScheme()).copy(
        primary = accent, onPrimary = if (redNight) ink else Color.White,
        background = panel, onBackground = ink, surface = panel, onSurface = ink,
        surfaceVariant = face, onSurfaceVariant = secondaryInk, surfaceTint = panel,
        secondary = secondaryInk, secondaryContainer = if (redNight) line else ink,
        onSecondaryContainer = if (redNight) accent else Color.White,
        outline = line, outlineVariant = line, error = accent,
        surfaceContainer = panel, surfaceContainerHigh = panel, surfaceContainerHighest = panel,
        surfaceContainerLowest = face,
    )
    fun text(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = size + 3) =
        TextStyle(fontFamily = Barlow, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp)
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineLarge = text(20, FontWeight.ExtraBold), headlineMedium = text(16, FontWeight.ExtraBold),
        headlineSmall = text(16, FontWeight.ExtraBold), titleLarge = text(16, FontWeight.SemiBold),
        titleMedium = text(14, FontWeight.SemiBold), bodyLarge = text(14), bodyMedium = text(12), bodySmall = text(11),
        labelLarge = text(14, FontWeight.SemiBold, 16), labelMedium = text(12, FontWeight.SemiBold),
        labelSmall = text(11, FontWeight.SemiBold),
    ), content = { CompositionLocalProvider(com.no5.ui.LocalNo5ActionFeedbackColor provides colors.primary, content = content) })
}

/** Approved D03 form tokens; scoped to setup bodies, never to the chart or its dock. */
@Composable fun No5WorkspaceTheme(redNight:Boolean,scale:Float,content:@Composable ()->Unit) {
    val ink=if(redNight)Color(0xffed0000)else Color(0xff07121b)
    val bg=if(redNight)Color.Black else Color(0xfff6f9fa)
    val muted=if(redNight)Color(0xffa40000)else Color(0xff526b7b)
    val line=if(redNight)Color(0xff550000)else Color(0xffb5c2c8)
    val accent=if(redNight)Color(0xffed0000)else Color(0xffff5b00)
    val colors=com.no5.ui.no5WorkspaceColorScheme(redNight,MaterialTheme.colorScheme)
    fun text(size:Float,weight:FontWeight=FontWeight.Normal)=TextStyle(fontFamily=Barlow,fontWeight=weight,
        fontSize=(size*scale).sp,lineHeight=(size*1.35f*scale).sp,letterSpacing=0.sp)
    MaterialTheme(colorScheme=colors,typography=Typography(
        headlineLarge=text(50f,FontWeight.ExtraBold),headlineMedium=text(40f,FontWeight.ExtraBold),headlineSmall=text(35f,FontWeight.ExtraBold),
        titleLarge=text(34f,FontWeight.SemiBold),titleMedium=text(30f,FontWeight.SemiBold),titleSmall=text(27f,FontWeight.SemiBold),
        bodyLarge=text(30f),bodyMedium=text(30f),bodySmall=text(25f),
        labelLarge=text(27f,FontWeight.SemiBold),labelMedium=text(25f,FontWeight.SemiBold),labelSmall=text(23f)),
        content={CompositionLocalProvider(com.no5.ui.LocalNo5ActionFeedbackColor provides accent,content=content)})
}
