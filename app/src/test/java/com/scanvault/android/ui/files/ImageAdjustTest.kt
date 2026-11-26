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
}
