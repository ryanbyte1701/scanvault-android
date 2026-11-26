package com.scanvault.android.ui.files

import com.scanvault.android.ui.files.ImageAdjust.Companion.dragged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAdjustTest {
    /** Applies the red row of the matrix to a gray value, like the GPU would (clamped to 0..255). */
    private fun apply(a: ImageAdjust, v: Float): Float {
        val m = a.matrixValues()
        return (m[0] * v + m[4]).coerceIn(0f, 255f)
    }

    @Test fun defaultIsIdentity() {
        val a = ImageAdjust()
        assertTrue(a.isIdentity)
        assertEquals(0f, apply(a, 0f), 0.01f)
        assertEquals(200f, apply(a, 200f), 0.01f)
    }

    @Test fun contrastPivotsAroundMidGray() {
        val a = ImageAdjust(contrast = 2f)
        assertEquals(128f, apply(a, 128f), 0.01f)
        assertEquals(168f, apply(a, 148f), 0.01f)
        assertEquals(88f, apply(a, 108f), 0.01f)
    }

    @Test fun brightnessShifts() {
        assertEquals(100f + 0.2f * 255f, apply(ImageAdjust(brightness = 0.2f), 100f), 0.01f)
    }

    @Test fun invertFlips() {
        val a = ImageAdjust(invert = true)
        assertEquals(255f, apply(a, 0f), 0.01f)
        assertEquals(55f, apply(a, 200f), 0.01f)
    }

    @Test fun dragChangesContrastAndBrightnessWithinBounds() {
        val up = ImageAdjust().dragged(0f, -0.1f)
        assertEquals(0.1f, up.brightness, 0.001f)
        val wider = ImageAdjust().dragged(0.25f, 0f)
        assertEquals(1.5f, wider.contrast, 0.001f)
        val extreme = ImageAdjust().dragged(100f, -100f)
        assertEquals(ImageAdjust.MAX_CONTRAST, extreme.contrast, 0.001f)
        assertEquals(1f, extreme.brightness, 0.001f)
    }

    @Test fun windowPreviewMatchesTrueWindowAtEdges() {
        // Rendered at L40/W80 (brain). A pixel at HU 80 is 255 there. Previewing L80/W80 should map
        // that same displayed 255 to HU 80 -> 127.5 in the new window.
        val rendered = WindowLevel(40.0, 80.0)
        val (s, o) = WindowLevel(80.0, 80.0).previewFrom(rendered)
        assertEquals(127.5f, s * 255f + o, 0.01f)
        // Widening to W160 around the same level halves the contrast around mid-gray.
        val (s2, o2) = WindowLevel(40.0, 160.0).previewFrom(rendered)
        assertEquals(0.5f, s2, 0.0001f)
        assertEquals(127.5f, s2 * 127.5f + o2, 0.01f)
    }

    @Test fun windowDragDirections() {
        val wl = WindowLevel(40.0, 400.0)
        val up = wl.dragged(0f, -0.1f, 2000.0)
        assertTrue("dragging up lowers the level (brighter)", up.center < wl.center)
        val right = wl.dragged(0.1f, 0f, 2000.0)
        assertTrue(right.width > wl.width)
        assertEquals(WindowLevel.MIN_WIDTH, wl.dragged(-10f, 0f, 2000.0).width, 0.0)
    }
}
