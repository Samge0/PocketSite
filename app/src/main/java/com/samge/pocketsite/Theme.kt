package com.samge.pocketsite

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---- palette: deep-navy demo aesthetic, mint accent ----
val Bg0 = Color(0xFF070D18)
val Bg1 = Color(0xFF0C1526)
val Card0 = Color(0xFF111C30)
val Card1 = Color(0xFF15223A)
val Stroke = Color(0xFF20304C)
val Mint = Color(0xFF5EEAD4)
val MintDeep = Color(0xFF14B8A6)
val Sky = Color(0xFF7DD3FC)
val Amber = Color(0xFFFBBF24)
val Rose = Color(0xFFFB7185)
val TextHi = Color(0xFFEAF2FC)
val TextMid = Color(0xFF93A7C8)
val TextLow = Color(0xFF5C7095)

private val DarkScheme =
    darkColorScheme(
        primary = Mint,
        onPrimary = Color(0xFF04211B),
        secondary = Sky,
        onSecondary = Color(0xFF06202E),
        tertiary = Amber,
        background = Bg0,
        onBackground = TextHi,
        surface = Card0,
        onSurface = TextHi,
        surfaceVariant = Card1,
        onSurfaceVariant = TextMid,
        outline = Stroke,
        error = Rose,
    )

private val AppType =
    Typography(
        headlineMedium =
            TextStyle(
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                letterSpacing = (-0.4).sp,
            ),
        titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp),
    )

@Composable
fun PocketSiteTheme(content: @Composable () -> Unit) {
    // dark-first: this is an offline demo tool, dark looks the part on stage
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkScheme, typography = AppType, content = content)
}
