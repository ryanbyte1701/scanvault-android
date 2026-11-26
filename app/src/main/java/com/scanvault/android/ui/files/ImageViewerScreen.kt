package com.scanvault.android.ui.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.scanvault.android.data.AppContainer
import com.scanvault.android.ui.files.ImageAdjust.Companion.dragged
import kotlin.math.roundToInt

private const val MAX_ZOOM = 10f

/**
 * Full-screen viewer for scans and photos stored in the vault.
 *
 * - Pinch to zoom (1x to 10x) around your fingers, drag to pan, double-tap to zoom in or reset.
 * - Window/level mode: one-finger drag left/right changes contrast, up/down changes brightness,
 *   the way radiology viewers do. Sliders give fine control.
 * - Invert (useful for X-rays) and rotate in 90° steps.
 * All adjustments are display-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(container: AppContainer, fileId: String, name: String, onBack: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var brightness by rememberSaveable { mutableFloatStateOf(0f) }
    var contrast by rememberSaveable { mutableFloatStateOf(1f) }
    var invert by rememberSaveable { mutableStateOf(false) }
    var quarterTurns by rememberSaveable { mutableStateOf(0) }
    var windowLevelMode by rememberSaveable { mutableStateOf(false) }
    var showControls by rememberSaveable { mutableStateOf(true) }
    var showSliders by rememberSaveable { mutableStateOf(false) }

    val adjust = ImageAdjust(brightness, contrast, invert, quarterTurns)

    fun clampOffset(candidate: Offset, s: Float): Offset {
        // Content fills the viewport at 1x, so allow panning by half the overflow on each axis.
        val maxX = viewport.width * (s - 1f) / 2f
        val maxY = viewport.height * (s - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun resetView() { scale = 1f; offset = Offset.Zero }
    fun resetAll() {
        resetView()
        brightness = 0f; contrast = 1f; invert = false; quarterTurns = 0
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewport = it }
            // Taps: single toggles the controls, double zooms toward the tap point or resets.
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) resetView() else {
                            val target = 3f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = target
                            offset = clampOffset((center - tap) * (target - 1f), target)
                        }
                    },
                )
            }
            // Pinch zoom + pan, or window/level when that mode is on and one finger is down.
            .pointerInput(windowLevelMode) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (windowLevelMode && pressed == 1) {
                            val change = event.changes.first { it.pressed }
                            if (change.positionChanged()) {
                                val d = change.position - change.previousPosition
                                val next = ImageAdjust(brightness, contrast, invert, quarterTurns)
                                    .dragged(d.x / size.width, d.y / size.height)
                                brightness = next.brightness
                                contrast = next.contrast
                                change.consume()
                            }
                        } else if (pressed >= 1) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            if (newScale != scale || (scale > 1f && pan != Offset.Zero)) {
                                // Keep the point between the fingers fixed while zooming.
                                val centroid = event.changes.filter { it.pressed }
                                    .fold(Offset.Zero) { acc, c -> acc + c.position } / pressed.toFloat()
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val focus = centroid - center
                                val realZoom = newScale / scale
                                val moved = (offset - focus) * realZoom + focus + pan
                                scale = newScale
                                offset = if (scale <= 1f) Offset.Zero else clampOffset(moved, scale)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = container.api.contentUrl(fileId),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            colorFilter = if (adjust.isIdentity) null else ColorFilter.colorMatrix(adjust.colorMatrix()),
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Couldn't load image", color = Color.White)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                    rotationZ = quarterTurns * 90f
                },
        )

        AnimatedVisibility(visible = showControls, modifier = Modifier.align(Alignment.TopCenter)) {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        }

        AnimatedVisibility(visible = showControls, modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showSliders) {
                    LabeledSlider("Brightness", brightness, -1f..1f, { brightness = it }) {
                        "${(brightness * 100).roundToInt()}"
                    }
                    LabeledSlider("Contrast", contrast, ImageAdjust.MIN_CONTRAST..ImageAdjust.MAX_CONTRAST,
                        { contrast = it }) { "${(contrast * 100).roundToInt()}%" }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = windowLevelMode,
                        onClick = { windowLevelMode = !windowLevelMode },
                        label = { Text(if (windowLevelMode) "Drag: W/L" else "Drag: pan") },
                        leadingIcon = {
                            Icon(
                                if (windowLevelMode) Icons.Default.Contrast else Icons.Default.ZoomIn, null,
                                tint = if (windowLevelMode) MaterialTheme.colorScheme.onPrimary else Color.White,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = Color.White,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Row {
                        IconToggleButton(checked = showSliders, onCheckedChange = { showSliders = it }) {
                            Icon(Icons.Default.Tune, "Brightness and contrast",
                                tint = if (showSliders) MaterialTheme.colorScheme.primary else Color.White)
                        }
                        IconToggleButton(checked = invert, onCheckedChange = { invert = it }) {
                            Icon(Icons.Default.InvertColors, "Invert",
                                tint = if (invert) MaterialTheme.colorScheme.primary else Color.White)
                        }
                        IconButton(onClick = { quarterTurns = (quarterTurns + 1) % 4 }) {
                            Icon(Icons.Default.RotateRight, "Rotate", tint = Color.White)
                        }
                        IconButton(onClick = ::resetAll) {
                            Icon(Icons.Default.RestartAlt, "Reset", tint = Color.White)
                        }
                    }
                }
                Text(
                    "${"%.1f".format(scale)}x" + if (windowLevelMode) "  ·  drag ↔ contrast, ↕ brightness" else "",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    display: () -> String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
        )
        Text(display(), color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(48.dp).padding(start = 8.dp))
    }
}
