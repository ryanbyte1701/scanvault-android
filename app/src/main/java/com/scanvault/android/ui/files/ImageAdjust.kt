package com.scanvault.android.ui.files

import androidx.compose.ui.graphics.ColorMatrix

/**
 * Display adjustments for a scan. [brightness] is an offset in -1..1 of full scale,
 * [contrast] a multiplier around mid-gray (1 = unchanged). This is a display-only
 * window/level on the rendered image; the stored file is never modified.
 */
data class ImageAdjust(
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val invert: Boolean = false,
    val rotationQuarterTurns: Int = 0,
) {
    val isIdentity: Boolean get() = brightness == 0f && contrast == 1f && !invert

    /** Row-major 4x5 matrix in the 0..255 color space that Android's ColorMatrix uses. */
    fun matrixValues(): FloatArray {
        val c = contrast
        var scale = c
        var offset = (1f - c) * 128f + brightness * 255f
        if (invert) {
            scale = -scale
            offset = 255f - offset
        }
        return floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun colorMatrix(): ColorMatrix = ColorMatrix(matrixValues())

    fun rotated(): ImageAdjust = copy(rotationQuarterTurns = (rotationQuarterTurns + 1) % 4)

    companion object {
        const val MIN_CONTRAST = 0.2f
        const val MAX_CONTRAST = 4f

        /** Applies a window/level drag: horizontal changes contrast, vertical changes brightness. */
        fun ImageAdjust.dragged(dxFraction: Float, dyFraction: Float): ImageAdjust = copy(
            contrast = (contrast * (1f + dxFraction * 2f)).coerceIn(MIN_CONTRAST, MAX_CONTRAST),
            brightness = (brightness - dyFraction).coerceIn(-1f, 1f),
        )
    }
}
