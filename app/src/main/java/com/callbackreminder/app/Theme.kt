package com.callbackreminder.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BEA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EAFF),
    onPrimaryContainer = Color(0xFF0B2A8F),
    secondary = Color(0xFF0FA68E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7F5EE),
    onSecondaryContainer = Color(0xFF05453A),
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF14171F),
    surface = Color.White,
    onSurface = Color(0xFF14171F),
    surfaceVariant = Color(0xFFECEEF5),
    onSurfaceVariant = Color(0xFF5B6272),
    error = Color(0xFFD93A4A),
    errorContainer = Color(0xFFFFE1E4),
    onErrorContainer = Color(0xFF7A0F1A),
    outline = Color(0xFFC9CDDA),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF8BA6FF),
    onPrimary = Color(0xFF0A1F6B),
    primaryContainer = Color(0xFF22346F),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF4FD6BE),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0B4A3E),
    onSecondaryContainer = Color(0xFFC8F4EA),
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFEDEFF6),
    surface = Color(0xFF181B24),
    onSurface = Color(0xFFEDEFF6),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA6ADBF),
    error = Color(0xFFFF8A94),
    errorContainer = Color(0xFF5A1620),
    onErrorContainer = Color(0xFFFFD9DD),
    outline = Color(0xFF3A4052),
)

@Composable
fun CallbackTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
