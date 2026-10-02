@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
package com.roam.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp

val Terracotta = Color(0xFFA64430)
val Forest = Color(0xFF173F36)
val Cream = Color(0xFFFAF8F4)
val Sand = Color(0xFFF0E9DE)
val Ink = Color(0xFF242922)
val Quiet = Color(0xFF677067)
val Serif = FontFamily(Font(R.font.fraunces, variationSettings = FontVariation.Settings(FontVariation.weight(400))))
val Sans = FontFamily(
    Font(R.font.dm_sans, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.dm_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.dm_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.dm_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@Composable
fun RoamTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFF1B39E), onPrimary = Color(0xFF421A12), secondary = Color(0xFFB2D1B9),
        background = Color(0xFF191D19), surface = Color(0xFF191D19), onSurface = Color(0xFFF3EEE5),
        onBackground = Color(0xFFF3EEE5), surfaceVariant = Color(0xFF30382F), onSurfaceVariant = Color(0xFFC0C8BD),
        outline = Color(0xFF899383), outlineVariant = Color(0xFF454D43),
    ) else lightColorScheme(
        primary = Terracotta, onPrimary = Color.White, secondary = Forest, onSecondary = Color.White,
        background = Cream, onBackground = Ink, surface = Cream, onSurface = Ink,
        surfaceVariant = Sand, onSurfaceVariant = Quiet, outline = Color(0xFF778071), outlineVariant = Color(0xFFDFE1D7),
        secondaryContainer = Color(0xFFE1E9DC), onSecondaryContainer = Forest,
    )
    MaterialTheme(colorScheme = colors, typography = Typography(
        displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 48.sp, lineHeight = 52.sp),
        headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 40.sp),
        headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 33.sp),
        headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 23.sp, lineHeight = 28.sp),
        titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
        bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 15.sp, letterSpacing = 1.3.sp),
    ), content = content)
}

