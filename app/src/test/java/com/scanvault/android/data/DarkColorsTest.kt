package com.scanvault.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DarkColorsTest {
    private fun hsl(argb: Long) = DarkColors.rgbToHsl((argb and 0xFFFFFF).toInt())

    @Test fun presetsKeepTheirOwnColorAsBackground() {
        // All web presets are already within the 13% / 45% caps, so they pass through unchanged.
        DarkPreset.entries.forEach { p ->
            assertEquals(p.label, p.hex, DarkColors.toHex(DarkColors.from(p.hex).background))
        }
    }

    @Test fun brightColorsAreCappedLikeTheWebApp() {
        val bg = DarkColors.from("#ff0000").background
        val (h, s, l) = hsl(bg)
        assertEquals(0f, h, 1f)
        assertTrue("saturation $s", s <= 0.46f)
        assertTrue("lightness $l", l <= 0.135f)
    }

    @Test fun surfacesAreLighterThanBackground() {
        val c = DarkColors.from("#0b1426")
        assertTrue(hsl(c.surface).third > hsl(c.background).third)
        assertTrue(hsl(c.surfaceHigh).third > hsl(c.surface).third)
    }

    @Test fun invalidHexFallsBackToDefault() {
        assertEquals(DarkColors.from(DarkPreset.TEAL_NIGHT.hex), DarkColors.from("not a color"))
    }

    @Test fun hslRoundTrips() {
        for (hex in listOf("#1a1022", "#336699", "#808080", "#000000")) {
            val (h, s, l) = DarkColors.rgbToHsl(DarkColors.parseHex(hex)!!)
            assertEquals(hex, DarkColors.toHex(DarkColors.hslToArgb(h, s, l)))
        }
    }

    @Test fun themeModeWireValues() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromWire("dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromWire("bogus"))
    }
}
