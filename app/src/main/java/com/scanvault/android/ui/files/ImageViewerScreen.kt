package com.scanvault.android.ui.files

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import com.scanvault.android.data.RenderedImage
import com.scanvault.android.ui.components.userMessage
import com.scanvault.android.ui.files.ImageAdjust.Companion.dragged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MAX_ZOOM = 10f
private const val RENDER_IDLE_MS = 220L

/** What the server last rendered for a DICOM file, decoded and ready to draw. */
private class DicomFrame(val image: ImageBitmap, val info: RenderedImage) {
    val window get() = WindowLevel(info.center, info.width)
}

/**
 * Full-screen viewer for images and DICOM files stored in the vault.
 *
 * - Pinch to zoom (1x to 10x) around your fingers, drag to pan, double-tap to zoom in or reset.
 * - W/L drag mode: one-finger drag sideways changes contrast (window), up/down brightness (level).
 * - Photos and scans: display-only brightness/contrast. DICOM: true window/level in modality units
 *   (HU for CT) rendered by the server, previewed instantly while dragging, with CT presets and a
 *   frame slider for multi-frame files.
 * - Invert (useful for X-rays), rotate in 90° steps, reset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    container: AppContainer,
    fileId: String,
    name: String,
    isDicom: Boolean,
    onBack: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var invert by rememberSaveable { mutableStateOf(false) }
    var quarterTurns by rememberSaveable { mutableIntStateOf(0) }
    var windowLevelMode by rememberSaveable { mutableStateOf(isDicom) }
    var showControls by rememberSaveable { mutableStateOf(true) }
    var showSliders by rememberSaveable { mutableStateOf(isDicom) }

    // Photo adjustments.
    var brightness by rememberSaveable { mutableFloatStateOf(0f) }
    var contrast by rememberSaveable { mutableFloatStateOf(1f) }

    // DICOM state: the requested window and frame (null/-1 = file default) and the last render.
    var wc by rememberSaveable { mutableStateOf<Double?>(null) }
    var ww by rememberSaveable { mutableStateOf<Double?>(null) }
    var frame by rememberSaveable { mutableIntStateOf(-1) }
    var dicom by remember { mutableStateOf<DicomFrame?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var dicomError by remember { mutableStateOf<String?>(null) }

    if (isDicom) {
        LaunchedEffect(fileId) {
            snapshotFlow { Triple(wc, ww, frame) }.collectLatest { (c, w, f) ->
                val last = dicom
                if (last != null && c == last.info.center && w == last.info.width &&
                    (f == -1 || f == last.info.frame)) return@collectLatest
                if (last != null) delay(RENDER_IDLE_MS) // debounce drags and slider moves
                rendering = true
                val result = container.call { it.render(fileId, c, w, f.takeIf { it >= 0 }) }
                    .mapCatching { info ->
                        val bmp = withContext(Dispatchers.Default) {
                            BitmapFactory.decodeByteArray(info.bytes, 0, info.bytes.size)
                        } ?: error("Couldn't decode the rendered image")
                        DicomFrame(bmp.asImageBitmap(), info)
                    }
                rendering = false
                result.onSuccess { r ->
                    dicom = r
                    dicomError = null
                    if (wc == null || ww == null) { wc = r.info.center; ww = r.info.width }
                    if (frame < 0) frame = r.info.frame
                }.onFailure { dicomError = it.userMessage() }
            }
        }
    }

    val dicomRange: Double = dicom?.info?.let { i ->
        if (i.min != null && i.max != null && i.max > i.min) i.max - i.min else null
    } ?: ((ww ?: 400.0) * 2)

    val colorFilter: ColorFilter? = if (isDicom) {
        val d = dicom
        val c = wc
        val w = ww
        if (d == null) null else {
            val (s, o) = if (c != null && w != null) WindowLevel(c, w).previewFrom(d.window) else 1f to 0f
            if (s == 1f && o == 0f && !invert) null else ColorFilter.colorMatrix(ColorMatrix(displayMatrix(s, o, invert)))
        }
    } else {
        val a = ImageAdjust(brightness, contrast, invert, quarterTurns)
        if (a.isIdentity) null else ColorFilter.colorMatrix(a.colorMatrix())
    }

    fun clampOffset(candidate: Offset, s: Float): Offset {
        val maxX = viewport.width * (s - 1f) / 2f
        val maxY = viewport.height * (s - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun resetView() { scale = 1f; offset = Offset.Zero }
    fun defaultWindow() {
        dicom?.info?.let { i ->
            wc = i.defaultCenter ?: i.center
            ww = i.defaultWidth ?: i.width
        }
    }
    fun resetAll() {
        resetView()
        brightness = 0f; contrast = 1f; invert = false; quarterTurns = 0
        if (isDicom) defaultWindow()
    }

    val imageModifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            scaleX = scale; scaleY = scale
            translationX = offset.x; translationY = offset.y
            rotationZ = quarterTurns * 90f
        }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewport = it }
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
            .pointerInput(windowLevelMode, isDicom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (windowLevelMode && pressed == 1) {
                            val change = event.changes.first { it.pressed }
                            if (change.positionChanged()) {
                                val d = change.position - change.previousPosition
                                val fx = d.x / size.width
                                val fy = d.y / size.height
                                if (isDicom) {
                                    val c = wc; val w = ww
                                    if (c != null && w != null) {
                                        val next = WindowLevel(c, w).dragged(fx, fy, dicomRange)
                                        wc = next.center; ww = next.width
                                    }
                                } else {
                                    val next = ImageAdjust(brightness, contrast).dragged(fx, fy)
                                    brightness = next.brightness; contrast = next.contrast
                                }
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
                                val focus = centroid - Offset(size.width / 2f, size.height / 2f)
                                val moved = (offset - focus) * (newScale / scale) + focus + pan
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
        if (isDicom) {
            val d = dicom
            when {
                d != null -> Image(
                    bitmap = d.image,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    colorFilter = colorFilter,
                    modifier = imageModifier,
                )
                dicomError != null -> Text(
                    "Couldn't render this DICOM file: $dicomError",
                    color = Color.White, modifier = Modifier.padding(32.dp),
                )
                else -> CircularProgressIndicator()
            }
        } else {
            SubcomposeAsyncImage(
                model = container.api.contentUrl(fileId),
                contentDescription = name,
                contentScale = ContentScale.Fit,
                colorFilter = colorFilter,
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Couldn't load image", color = Color.White)
                    }
                },
                modifier = imageModifier,
            )
        }

        AnimatedVisibility(visible = showControls, modifier = Modifier.align(Alignment.TopCenter)) {
            Column {
                TopAppBar(
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                    ),
                )
                if (rendering) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
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
                val info = dicom?.info
                if (isDicom && info != null) {
                    WindowPresets(
                        isCt = info.modality.equals("CT", ignoreCase = true),
                        onDefault = ::defaultWindow,
                        onPreset = { wc = it.level; ww = it.window },
                    )
                }
                if (showSliders) {
                    if (isDicom) {
                        val c = wc
                        val w = ww
                        if (info != null && c != null && w != null) {
                            val lo = info.min ?: (c - dicomRange)
                            val hi = info.max ?: (c + dicomRange)
                            LabeledSlider("Level", c.toFloat(), lo.toFloat()..hi.toFloat(), { wc = it.toDouble() }) {
                                "${c.roundToInt()}"
                            }
                            LabeledSlider("Window", w.toFloat(), 1f..(dicomRange * 2).toFloat().coerceAtLeast(2f),
                                { ww = it.toDouble() }) { "${w.roundToInt()}" }
                        }
                    } else {
                        LabeledSlider("Brightness", brightness, -1f..1f, { brightness = it }) {
                            "${(brightness * 100).roundToInt()}"
                        }
                        LabeledSlider("Contrast", contrast, ImageAdjust.MIN_CONTRAST..ImageAdjust.MAX_CONTRAST,
                            { contrast = it }) { "${(contrast * 100).roundToInt()}%" }
                    }
                }
                if (isDicom && info != null && info.frames > 1) {
                    val shown = if (frame >= 0) frame else info.frame
                    LabeledSlider(
                        "Frame", shown.toFloat(), 0f..(info.frames - 1).toFloat(),
                        { frame = it.roundToInt() },
                        steps = (info.frames - 2).coerceAtLeast(0),
                    ) { "${shown + 1}/${info.frames}" }
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
                            Icon(Icons.Default.Tune, if (isDicom) "Window and level" else "Brightness and contrast",
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
                val status = buildList {
                    add("${"%.1f".format(scale)}x")
                    if (isDicom) {
                        val c = wc; val w = ww
                        if (c != null && w != null) {
                            val unit = if (info?.modality.equals("CT", ignoreCase = true)) " HU" else ""
                            add("L ${c.roundToInt()} / W ${w.roundToInt()}$unit")
                        }
                        info?.modality?.let { add(it) }
                    }
                    if (windowLevelMode) add("drag ↔ contrast, ↕ brightness")
                }.joinToString("  ·  ")
                Text(status, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WindowPresets(isCt: Boolean, onDefault: () -> Unit, onPreset: (CtPreset) -> Unit) {
    val chipColors = AssistChipDefaults.assistChipColors(labelColor = Color.White)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = onDefault, label = { Text("Default") }, colors = chipColors)
        if (isCt) {
            CtPreset.entries.forEach { p ->
                AssistChip(onClick = { onPreset(p) }, label = { Text(p.label) }, colors = chipColors)
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
    steps: Int = 0,
    display: () -> String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = if (steps in 1..200) steps else 0,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
        )
        Text(display(), color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp).padding(start = 8.dp))
    }
}
