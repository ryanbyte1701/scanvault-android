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
    fun matrixValues(): FloatArray =
        displayMatrix(contrast, (1f - contrast) * 128f + brightness * 255f, invert)

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

/** True DICOM window/level in modality units (HU for CT): [center] is the level, [width] the window. */
data class WindowLevel(val center: Double, val width: Double) {
    /**
     * A display-space (0..255) scale and offset that approximates this window on an image the
     * server rendered with [rendered], so drags preview instantly before the real render arrives.
     */
    fun previewFrom(rendered: WindowLevel): Pair<Float, Float> {
        val w0 = rendered.width
        val w1 = width.coerceAtLeast(MIN_WIDTH)
        val scale = w0 / w1
        val offset = 255.0 * (rendered.center - center) / w1 + 127.5 * (w1 - w0) / w1
        return scale.toFloat() to offset.toFloat()
    }

    /**
     * One-finger drag in W/L mode: right widens the window (less contrast), up lowers the level
     * (brighter). Fractions are of the screen size; [range] is the pixel value range of the image.
     */
    fun dragged(dxFraction: Float, dyFraction: Float, range: Double): WindowLevel = WindowLevel(
        center = center + dyFraction * range * 0.5,
        width = (width + dxFraction * range).coerceAtLeast(MIN_WIDTH),
    )

    companion object {
        const val MIN_WIDTH = 1.0
    }
}

/** Named windows for CT, as level/window, matching the ScanVault web viewer. */
enum class CtPreset(val label: String, val level: Double, val window: Double) {
    BRAIN("Brain", 40.0, 80.0),
    SUBDURAL("Subdural", 75.0, 215.0),
    STROKE("Stroke", 40.0, 40.0),
    SOFT_TISSUE("Soft tissue", 50.0, 400.0),
    MEDIASTINUM("Mediastinum", 50.0, 350.0),
    LIVER("Liver", 60.0, 160.0),
    LUNG("Lung", -600.0, 1500.0),
    BONE("Bone", 400.0, 1800.0),
}

/** Builds a 4x5 color matrix for a display-space scale/offset, optionally inverted. */
fun displayMatrix(scale: Float, offset: Float, invert: Boolean): FloatArray {
    var s = scale
    var o = offset
    if (invert) { s = -s; o = 255f - o }
    return floatArrayOf(
        s, 0f, 0f, 0f, o,
        0f, s, 0f, 0f, o,
        0f, 0f, s, 0f, o,
        0f, 0f, 0f, 1f, 0f,
    )
}
