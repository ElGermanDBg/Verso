package com.verso.lyrics.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val VersoBg = Color(0xFF07070B)
val VersoSurface = Color(0xFF12121B)
val VersoSurfaceElevated = Color(0xFF1B1B28)
val VersoAccent = Color(0xFFFF4D82)
val VersoAccentSecondary = Color(0xFFFF8A5B)
val VersoGold = Color(0xFFFFD166)
val VersoCyan = Color(0xFF38BDF8)
val VersoGreen = Color(0xFF4ADE80)
val VersoTextPrimary = Color(0xFFF5F5FA)
val VersoTextSecondary = Color(0xFF9E9EB8)
val VersoBorder = Color(0x1AFFFFFF)

private val VersoDarkColors = darkColorScheme(
    primary = VersoAccent,
    secondary = VersoGold,
    background = VersoBg,
    surface = VersoSurface,
    surfaceVariant = VersoSurfaceElevated,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = VersoTextPrimary,
    onSurface = VersoTextPrimary,
    onSurfaceVariant = VersoTextSecondary
)

@Composable
fun VersoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VersoDarkColors,
        content = content
    )
}
