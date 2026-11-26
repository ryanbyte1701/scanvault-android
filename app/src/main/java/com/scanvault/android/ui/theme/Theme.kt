package com.scanvault.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.scanvault.android.data.DarkPalette
import com.scanvault.android.data.ThemeMode
import com.scanvault.android.data.ThemeSettings

private val Light = lightColorScheme(
    primary = Color(0xFF1F5F8B),
    secondary = Color(0xFF4A6572),
    tertiary = Color(0xFF2E7D6B),
)

/** Builds a full dark scheme from the user's chosen palette. */
fun darkSchemeFor(palette: DarkPalette): ColorScheme {
    val accent = Color(palette.accent)
    val bg = Color(palette.background)
    val surface = Color(palette.surface)
    val onAccent = if (accent.luminance() > 0.4f) Color(0xFF0B1A24) else Color.White
    val onBg = Color(0xFFE3E6E8)
    return darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = 0.30f).compositeOver(surface),
        onPrimaryContainer = onBg,
        secondary = accent.copy(alpha = 0.75f).compositeOver(bg),
        secondaryContainer = accent.copy(alpha = 0.18f).compositeOver(surface),
        onSecondaryContainer = onBg,
        tertiary = accent,
        background = bg,
        onBackground = onBg,
        surface = bg,
        onSurface = onBg,
        surfaceVariant = surface,
        onSurfaceVariant = Color(0xFFB8BFC4),
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = Color.White.copy(alpha = 0.05f).compositeOver(surface),
        surfaceContainerHighest = Color.White.copy(alpha = 0.09f).compositeOver(surface),
        outline = Color(0xFF8A9298),
        outlineVariant = Color(0xFF3A4247),
    )
}

@Composable
fun isAppInDarkTheme(settings: ThemeSettings): Boolean = when (settings.mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun ScanVaultTheme(settings: ThemeSettings, content: @Composable () -> Unit) {
    val dark = isAppInDarkTheme(settings)
    val colors = when {
        dark -> darkSchemeFor(settings.darkPalette)
        settings.wallpaperColorsInLight && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(LocalContext.current)
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
