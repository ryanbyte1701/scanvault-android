package com.scanvault.android.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ThemeMode(val wire: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromWire(value: String?) = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/** The same dark-color presets the ScanVault web app offers, so a choice looks alike on both. */
enum class DarkPreset(val label: String, val hex: String) {
    TEAL_NIGHT("Teal night", "#0d1416"),
    GRAPHITE("Graphite", "#161718"),
    PURE_BLACK("Pure black", "#000000"),
    NAVY("Navy", "#0b1426"),
    AUBERGINE("Aubergine", "#1a1022"),
    ESPRESSO("Espresso", "#1a1411"),
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** "#rrggbb" chosen by the user; the dark palette is derived from it (see [DarkColors]). */
    val darkColor: String = DarkPreset.TEAL_NIGHT.hex,
    /** Android 12+: take light-theme colors from the wallpaper. Local to this phone. */
    val wallpaperColorsInLight: Boolean = false,
)

/** Wire format of GET/PUT /api/prefs. */
@Serializable
data class ServerPrefs(
    val theme: String? = null,
    @SerialName("dark_color") val darkColor: String? = null,
)

/**
 * Derives a dark palette from one user-picked color, using the web app's rule: the background keeps
 * the color's hue but caps HSL lightness at 13% and saturation at 45%. Surfaces step up in lightness
 * and the accent is a light, readable tint of the same hue.
 */
data class DarkColors(val background: Long, val surface: Long, val surfaceHigh: Long, val accent: Long) {
    companion object {
        private const val DEFAULT_ACCENT = 0xFF9CCBF0

        fun from(hex: String): DarkColors {
            val rgb = parseHex(hex) ?: parseHex(DarkPreset.TEAL_NIGHT.hex)!!
            val (h, s, l) = rgbToHsl(rgb)
            val bgS = minOf(s, 0.45f)
            val bgL = minOf(l, 0.13f)
            val accent = if (s < 0.08f) DEFAULT_ACCENT else hslToArgb(h, 0.55f, 0.75f)
            return DarkColors(
                background = hslToArgb(h, bgS, bgL),
                surface = hslToArgb(h, bgS, (bgL + 0.05f).coerceAtMost(1f)),
                surfaceHigh = hslToArgb(h, bgS, (bgL + 0.09f).coerceAtMost(1f)),
                accent = accent,
            )
        }

        fun parseHex(hex: String?): Int? {
            val v = hex?.trim()?.removePrefix("#") ?: return null
            if (v.length != 6 || !v.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
            return v.toInt(16)
        }

        fun toHex(argb: Long): String = "#%06x".format(argb and 0xFFFFFF)

        fun rgbToHsl(rgb: Int): Triple<Float, Float, Float> {
            val r = ((rgb shr 16) and 0xFF) / 255f
            val g = ((rgb shr 8) and 0xFF) / 255f
            val b = (rgb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val l = (max + min) / 2f
            val d = max - min
            if (d == 0f) return Triple(0f, 0f, l)
            val s = d / (1f - abs(2f * l - 1f))
            val h = when (max) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * (((b - r) / d) + 2f)
                else -> 60f * (((r - g) / d) + 4f)
            }.let { if (it < 0) it + 360f else it }
            return Triple(h, s, l)
        }

        fun hslToArgb(h: Float, s: Float, l: Float): Long {
            val c = (1f - abs(2f * l - 1f)) * s
            val x = c * (1f - abs((h / 60f) % 2f - 1f))
            val m = l - c / 2f
            val (r, g, b) = when {
                h < 60f -> Triple(c, x, 0f)
                h < 120f -> Triple(x, c, 0f)
                h < 180f -> Triple(0f, c, x)
                h < 240f -> Triple(0f, x, c)
                h < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            fun ch(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255).toLong()
            return (0xFFL shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
    }
}

/**
 * Keeps appearance settings locally (so the sign-in screen is themed too) and mirrors theme and
 * dark color to the server's per-user prefs, so they follow the user between web and Android.
 */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ThemeSettings> = _settings.asStateFlow()

    /** Called after a local change so the app container can push it to the server. */
    var onChanged: ((ThemeSettings) -> Unit)? = null

    private fun read() = ThemeSettings(
        mode = prefs.getString("mode", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        darkColor = prefs.getString("dark_color", null)?.takeIf { DarkColors.parseHex(it) != null }
            ?: DarkPreset.TEAL_NIGHT.hex,
        wallpaperColorsInLight = prefs.getBoolean("wallpaper_light", false),
    )

    private fun save(next: ThemeSettings) {
        prefs.edit()
            .putString("mode", next.mode.name)
            .putString("dark_color", next.darkColor)
            .putBoolean("wallpaper_light", next.wallpaperColorsInLight)
            .apply()
        _settings.value = next
    }

    fun update(transform: (ThemeSettings) -> ThemeSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        save(next)
        onChanged?.invoke(next)
    }

    /** Applies prefs fetched from the server without echoing them back. */
    fun applyServer(server: ServerPrefs) {
        val current = _settings.value
        save(current.copy(
            mode = server.theme?.let { ThemeMode.fromWire(it) } ?: current.mode,
            darkColor = server.darkColor?.takeIf { DarkColors.parseHex(it) != null }?.lowercase() ?: current.darkColor,
        ))
    }
}
