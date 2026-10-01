package dev.kalimote.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF5B8CFF)
val Danger = Color(0xFFFF5A5F)
val Ok = Color(0xFF36C47A)
val Warn = Color(0xFFF2B84B)

private val Dark = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFEEF0F4),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFEEF0F4),
    surfaceContainer = Color(0xFF1A1D24),
    surfaceContainerHigh = Color(0xFF242833),
    surfaceContainerHighest = Color(0xFF2E3340),
    surfaceVariant = Color(0xFF242833),
    onSurfaceVariant = Color(0xFF8B92A3),
    error = Danger,
)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFEEF0F4),
    onBackground = Color(0xFF151821),
    surface = Color(0xFFEEF0F4),
    onSurface = Color(0xFF151821),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFE4E7EE),
    surfaceContainerHighest = Color(0xFFD5D9E2),
    surfaceVariant = Color(0xFFE4E7EE),
    onSurfaceVariant = Color(0xFF5D6475),
    error = Danger,
)

@Composable
fun KalimoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
