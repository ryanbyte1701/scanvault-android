package com.scanvault.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * A dark palette the user can choose. [accent] is the primary color; [background] and [surface]
 * tint the dark backgrounds. "Pure black" is meant for OLED screens.
 */
enum class DarkPalette(val label: String, val accent: Long, val background: Long, val surface: Long) {
    OCEAN("Ocean blue", 0xFF9CCBF0, 0xFF0F1419, 0xFF1A2128),
    PURE_BLACK("Pure black", 0xFFB8C4CC, 0xFF000000, 0xFF101010),
    SLATE("Slate", 0xFFB4C9D3, 0xFF15181B, 0xFF202428),
    TEAL("Teal", 0xFF80D5C3, 0xFF0D1614, 0xFF17221F),
    FOREST("Forest green", 0xFFA5D6A7, 0xFF101510, 0xFF1A211A),
    PLUM("Plum", 0xFFD7B5F0, 0xFF16121A, 0xFF221C28),
    ROSE("Rose", 0xFFF4B4C4, 0xFF1A1214, 0xFF271C1F),
    AMBER("Amber", 0xFFFFCC80, 0xFF17140E, 0xFF231F17),
    RED_NIGHT("Night red", 0xFFFF8A80, 0xFF140A0A, 0xFF1F1212),
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val darkPalette: DarkPalette = DarkPalette.OCEAN,
    /** Android 12+: take light-theme colors from the wallpaper. */
    val wallpaperColorsInLight: Boolean = false,
)

/** Persists appearance settings in plain prefs (nothing sensitive) and exposes them as a flow. */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    private fun read() = ThemeSettings(
        mode = prefs.getString("mode", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        darkPalette = prefs.getString("dark_palette", null)
            ?.let { runCatching { DarkPalette.valueOf(it) }.getOrNull() } ?: DarkPalette.OCEAN,
        wallpaperColorsInLight = prefs.getBoolean("wallpaper_light", false),
    )

    fun update(transform: (ThemeSettings) -> ThemeSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString("mode", next.mode.name)
            .putString("dark_palette", next.darkPalette.name)
            .putBoolean("wallpaper_light", next.wallpaperColorsInLight)
            .apply()
        _settings.value = next
    }
}
