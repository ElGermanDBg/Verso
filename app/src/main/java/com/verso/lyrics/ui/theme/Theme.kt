package com.verso.lyrics.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val VersoBg = Color(0xFF0A0A0F)
val VersoSurface = Color(0xFF151520)
val VersoSurfaceElevated = Color(0xFF1E1E2E)
val VersoAccent = Color(0xFFFF5C8A)
val VersoGold = Color(0xFFFFD166)
val VersoTextPrimary = Color(0xFFF5F5FA)
val VersoTextSecondary = Color(0xFFA0A0B8)

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
