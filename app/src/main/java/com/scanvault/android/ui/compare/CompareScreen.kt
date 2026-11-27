package com.scanvault.android.ui.compare

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.scanvault.android.data.AppContainer
import com.scanvault.android.data.RenderedImage
import com.scanvault.android.data.SessionState
import com.scanvault.android.data.SimilarStudy
import com.scanvault.android.data.Study
import com.scanvault.android.data.VaultFile
import com.scanvault.android.ui.components.EmptyState
import com.scanvault.android.ui.components.Loadable
import com.scanvault.android.ui.components.LoadableContent
import com.scanvault.android.ui.components.userMessage
import com.scanvault.android.ui.files.WindowLevel
import com.scanvault.android.ui.files.displayMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAX_ZOOM = 10f
private const val RENDER_IDLE_MS = 220L
/** Photos have no stored window; their "window" is a 0..255 display window starting here. */
private val PHOTO_WINDOW = WindowLevel(127.5, 255.0)

// ============================================================================ picker

data class ComparePick(val base: Study, val others: List<SimilarStudy>)

class ComparePickViewModel(private val container: AppContainer, private val studyId: String) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<ComparePick>>(Loadable.Loading)
    val state = _state.asStateFlow()

    fun load() {
        if (_state.value !is Loadable.Loaded) _state.value = Loadable.Loading
        viewModelScope.launch {
            container.call { api -> ComparePick(api.study(studyId), api.similarStudies(studyId)) }
                .onSuccess { _state.value = Loadable.Loaded(it) }
                .onFailure {
                    val msg = if ((it as? com.scanvault.android.data.ApiException)?.status == 404)
                        "This server doesn't support comparing studies yet. Update ScanVault on the server."
                    else it.userMessage()
                    _state.value = Loadable.Failed(msg)
                }
        }
    }
}

/** Lists the patient's other studies, suggested matches first, to pick one to compare with. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparePickScreen(
    container: AppContainer,
    studyId: String,
    onBack: () -> Unit,
    onPick: (otherStudyId: String) -> Unit,
) {
    val vm: ComparePickViewModel = viewModel(key = "compare-pick-$studyId") { ComparePickViewModel(container, studyId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    LaunchedEffect(session) { if (session == SessionState.Ready) vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare with…") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LoadableContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { pick ->
            val usable = pick.others.filter { it.imageCount > 0 }
            if (usable.isEmpty()) {
                EmptyState(
                    if (pick.others.isEmpty()) "This patient has no other studies yet. Upload a prior study to compare."
                    else "This patient's other studies have no images to compare.",
                    Modifier.padding(padding),
                )
                return@LoadableContent
            }
            val suggested = usable.filter { it.suggested }
            val rest = usable.filter { !it.suggested }
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Shown next to ${pick.base.title}. Zoom, pan, window/level and slices stay linked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (suggested.isNotEmpty()) {
                    item { SectionTitle("Suggested matches") }
                    items(suggested, key = { it.id }) { StudyChoice(container, it) { onPick(it.id) } }
                }
                if (rest.isNotEmpty()) {
                    item { SectionTitle(if (suggested.isEmpty()) "Studies for this patient" else "Other studies") }
                    items(rest, key = { it.id }) { StudyChoice(container, it) { onPick(it.id) } }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudyChoice(container: AppContainer, s: SimilarStudy, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = s.thumbnailIds.firstOrNull()
                if (thumb != null) {
                    AsyncImage(
                        model = container.api.thumbnailUrl(thumb), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                } else Icon(Icons.Default.ImageNotSupported, null, tint = Color.White.copy(alpha = 0.6f))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(s.studyDate ?: "No date", gapText(s.daysApart, s.relation),
                        "${s.imageCount} image${if (s.imageCount == 1) "" else "s"}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.matchReasons.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.matchReasons.forEach { r ->
                            val warn = r == "Other side"
                            SuggestionChip(
                                onClick = onClick,
                                label = { Text(r, style = MaterialTheme.typography.labelSmall) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (warn) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = if (warn) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                                border = null,
                            )
                        }
                    }
                }
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================================ side by side

data class ComparePair(val first: Study, val firstFiles: List<VaultFile>, val second: Study, val secondFiles: List<VaultFile>)

class CompareViewModel(private val container: AppContainer, private val a: String, private val b: String) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<ComparePair>>(Loadable.Loading)
    val state = _state.asStateFlow()

    fun load() {
        if (_state.value !is Loadable.Loaded) _state.value = Loadable.Loading
        viewModelScope.launch {
            container.call { api ->
                coroutineScope {
                    val sa = async { api.study(a) }
                    val sb = async { api.study(b) }
                    val fa = async { api.files(a) }
                    val fb = async { api.files(b) }
                    var pair = ComparePair(sa.await(), fa.await(), sb.await(), fb.await())
                    // Earlier study first (left or top), like films hung prior then current.
                    val da = pair.first.studyDate
                    val db = pair.second.studyDate
                    if ((db != null && (da == null || db < da))) {
                        pair = ComparePair(pair.second, pair.secondFiles, pair.first, pair.firstFiles)
                    }
                    pair
                }
            }.onSuccess { _state.value = Loadable.Loaded(it) }
                .onFailure { _state.value = Loadable.Failed(it.userMessage()) }
        }
    }
}

private class Rendered(val image: ImageBitmap, val info: RenderedImage) {
    val window get() = WindowLevel(info.center, info.width)
}

/** One side of the comparison: which image is showing, its window and the view transform. */
@Stable
private class Pane(val study: Study, val groups: List<SeriesGroup>) {
    var group by mutableIntStateOf(0)
    var index by mutableIntStateOf(0)
    /** Requested frame of a multi-frame file; -1 = the file's default. */
    var frame by mutableIntStateOf(-1)
    var wc by mutableStateOf<Double?>(null)
    var ww by mutableStateOf<Double?>(null)
    var rendered by mutableStateOf<Rendered?>(null)
    /** Which file [rendered] shows; it stays up while the next slice loads. */
    var renderedFor by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var invert by mutableStateOf(false)
    var viewport by mutableStateOf(IntSize.Zero)

    val files: List<VaultFile> get() = groups.getOrNull(group)?.files.orEmpty()
    val file: VaultFile? get() = files.getOrNull(index)
    val isDicom: Boolean get() = file?.kind == VaultFile.KIND_DICOM
    val frames: Int get() = rendered?.takeIf { it.info.frames > 1 && isDicom }?.info?.frames ?: 1
    val count: Int get() = if (files.size > 1) files.size else frames
    val position: Int get() = if (files.size > 1) index else (if (frame >= 0) frame else rendered?.info?.frame ?: 0)
    val fraction: Float get() = sliceFraction(position, count)
    val hasWindow: Boolean get() = if (isDicom) rendered?.info?.rgb == false else file != null
    val isCt: Boolean get() = isDicom && rendered?.info?.modality.equals("CT", ignoreCase = true)
    val defaultWindow: WindowLevel
        get() = if (!isDicom) PHOTO_WINDOW else rendered?.info?.let {
            WindowLevel(it.defaultCenter ?: it.center, it.defaultWidth ?: it.width)
        } ?: PHOTO_WINDOW
    val window: WindowLevel? get() { val c = wc; val w = ww; return if (c != null && w != null) WindowLevel(c, w) else null }
    val valueRange: Double
        get() = rendered?.info?.let { i -> val mn = i.min; val mx = i.max; if (mn != null && mx != null && mx > mn) mx - mn else null }
            ?: if (isDicom) (ww ?: 400.0) * 2 else 255.0

    /** Moves to slice [n] (clamped); returns false if nothing changed. */
    fun goTo(n: Int): Boolean {
        val t = n.coerceIn(0, (count - 1).coerceAtLeast(0))
        if (t == position) return false
        if (files.size > 1) { index = t; frame = -1 } else frame = t
        return true
    }

    fun colorFilter(): ColorFilter? {
        if (!hasWindow) return if (invert) ColorFilter.colorMatrix(ColorMatrix(displayMatrix(1f, 0f, true))) else null
        val shown = if (isDicom) (rendered?.window ?: return null) else PHOTO_WINDOW
        val (s, o) = (window ?: shown).previewFrom(shown)
        if (s == 1f && o == 0f && !invert) return null
        return ColorFilter.colorMatrix(ColorMatrix(displayMatrix(s, o, invert)))
    }

    fun clamp(candidate: Offset, s: Float): Offset {
        val maxX = viewport.width * (s - 1f) / 2f
        val maxY = viewport.height * (s - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }
}

/**
 * Two studies side by side (stacked on a phone held upright). Zoom, pan, window/level, invert and
 * slice changes on one side are mirrored on the other while linked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(container: AppContainer, firstId: String, secondId: String, onBack: () -> Unit) {
    val vm: CompareViewModel = viewModel(key = "compare-$firstId-$secondId") { CompareViewModel(container, firstId, secondId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    LaunchedEffect(session) { if (session == SessionState.Ready) vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare studies") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LoadableContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { pair ->
            CompareContent(container, pair, Modifier.padding(padding))
        }
    }
}

@Composable
private fun CompareContent(container: AppContainer, pair: ComparePair, modifier: Modifier) {
    val panes = remember(pair) {
        val a = Pane(pair.first, seriesGroups(pair.firstFiles))
        val b = Pane(pair.second, seriesGroups(pair.secondFiles))
        b.group = bestSeries(b.groups, a.groups.firstOrNull()?.label)
        mutableListOf(a, b)
    }
    var swapped by rememberSaveable { mutableStateOf(false) }
    val shown = if (swapped) panes.reversed() else panes
    var linked by rememberSaveable { mutableStateOf(true) }
    var wlMode by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(0) }
    /** Slice offset (as a fraction) of the second pane relative to the first, kept while linked. */
    var sliceOffset by remember { mutableFloatStateOf(0f) }

    panes.forEach { p -> RenderEffect(container, p) }

    fun others(p: Pane) = if (linked) shown.filter { it !== p } else emptyList()
    fun setView(p: Pane, s: Float, o: Offset) {
        for (q in listOf(p) + others(p)) { q.scale = s; q.offset = if (s <= 1f) Offset.Zero else q.clamp(o, s) }
    }
    fun setWindow(p: Pane, w: WindowLevel) {
        p.wc = w.center; p.ww = w.width.coerceAtLeast(WindowLevel.MIN_WIDTH)
        for (q in others(p)) {
            if (!q.hasWindow || q.window == null) continue
            val next = linkedWindow(w, p.defaultWindow, q.defaultWindow, bothCt = p.isCt && q.isCt)
            q.wc = next.center; q.ww = next.width
        }
    }
    fun move(p: Pane, n: Int) {
        if (!p.goTo(n)) return
        for (q in others(p)) {
            val sign = if (shown.indexOf(q) > shown.indexOf(p)) 1f else -1f
            q.goTo(linkedSlice(p.fraction, sign * sliceOffset, q.count))
        }
    }
    fun relink() {
        sliceOffset = shown[1].fraction - shown[0].fraction
        val lead = shown[active.coerceIn(0, 1)]
        setView(lead, lead.scale, lead.offset)
    }

    Column(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sideBySide = maxWidth > maxHeight
            val paneContent: @Composable (Int, Modifier) -> Unit = { i, m ->
                val p = shown[i]
                PaneView(
                    container, p, isActive = active == i, wlMode = wlMode, modifier = m,
                    onActivate = { active = i },
                    onView = { s, o -> setView(p, s, o) },
                    onWindow = { setWindow(p, it) },
                    onSlice = { move(p, it) },
                    onSeries = { g ->
                        p.group = g; p.index = 0; p.frame = -1; p.wc = null; p.ww = null; p.rendered = null
                        if (linked) relink()
                    },
                )
            }
            if (sideBySide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    paneContent(0, Modifier.weight(1f).fillMaxSize())
                    paneContent(1, Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    paneContent(0, Modifier.weight(1f).fillMaxWidth())
                    paneContent(1, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = wlMode,
                    onClick = { wlMode = !wlMode },
                    label = { Text(if (wlMode) "Drag: W/L" else "Drag: pan") },
                    leadingIcon = { Icon(if (wlMode) Icons.Default.Contrast else Icons.Default.ZoomIn, null) },
                )
                FilterChip(
                    selected = linked,
                    onClick = { linked = !linked; if (linked) relink() },
                    label = { Text(if (linked) "Linked" else "Unlinked") },
                    leadingIcon = { Icon(if (linked) Icons.Default.Link else Icons.Default.LinkOff, null) },
                )
                val lead = shown[active.coerceIn(0, 1)]
                IconToggleButton(checked = lead.invert, onCheckedChange = { v ->
                    lead.invert = v; others(lead).forEach { it.invert = v }
                }) {
                    Icon(Icons.Default.InvertColors, "Invert",
                        tint = if (lead.invert) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = {
                    for (q in if (linked) shown else listOf(lead)) {
                        q.scale = 1f; q.offset = Offset.Zero; q.invert = false
                        if (q.hasWindow) { val d = q.defaultWindow; q.wc = d.center; q.ww = d.width }
                    }
                }) { Icon(Icons.Default.RestartAlt, "Reset") }
                IconButton(onClick = { swapped = !swapped; sliceOffset = -sliceOffset; active = 1 - active }) {
                    Icon(Icons.Default.SwapHoriz, "Swap sides")
                }
            }
        }
    }
}

/** Fetches server renders for a DICOM pane as its image, frame or window changes (debounced). */
@Composable
private fun RenderEffect(container: AppContainer, p: Pane) {
    LaunchedEffect(p) {
        snapshotFlow { Triple(p.file, p.window, p.frame) }.collectLatest { (file, win, frame) ->
            if (file == null) return@collectLatest
            if (file.kind != VaultFile.KIND_DICOM) {
                if (p.wc == null || p.ww == null) { p.wc = PHOTO_WINDOW.center; p.ww = PHOTO_WINDOW.width }
                return@collectLatest
            }
            val last = p.rendered
            val sameFile = last != null && p.renderedFor == file.id
            if (last != null && sameFile && win != null && win.center == last.info.center &&
                win.width == last.info.width && (frame == -1 || frame == last.info.frame)) return@collectLatest
            if (sameFile) delay(RENDER_IDLE_MS)
            p.loading = true
            val result = container.call { it.render(file.id, win?.center, win?.width, frame.takeIf { f -> f >= 0 }) }
                .mapCatching { info ->
                    val bmp = withContext(Dispatchers.Default) { decodeBounded(info.bytes) }
                        ?: error("Couldn't decode the rendered image")
                    Rendered(bmp.asImageBitmap(), info)
                }
            p.loading = false
            result.onSuccess { r ->
                p.rendered = r; p.renderedFor = file.id; p.error = null
                if (p.wc == null || p.ww == null) { p.wc = r.info.center; p.ww = r.info.width }
            }.onFailure { p.error = it.userMessage() }
        }
    }
}

@Composable
private fun PaneView(
    container: AppContainer,
    p: Pane,
    isActive: Boolean,
    wlMode: Boolean,
    modifier: Modifier,
    onActivate: () -> Unit,
    onView: (Float, Offset) -> Unit,
    onWindow: (WindowLevel) -> Unit,
    onSlice: (Int) -> Unit,
    onSeries: (Int) -> Unit,
) {
    val mode by rememberUpdatedState(wlMode)
    val view by rememberUpdatedState(onView)
    val windowCb by rememberUpdatedState(onWindow)
    val sliceCb by rememberUpdatedState(onSlice)
    val activate by rememberUpdatedState(onActivate)
    val accent = MaterialTheme.colorScheme.primary

    Column(
        modifier
            .background(Color.Black)
            .border(2.dp, if (isActive) accent else Color.Transparent),
    ) {
        PaneHeader(p, onSeries)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { p.viewport = it }
                .pointerInput(p) {
                    detectTapGestures(
                        onPress = { activate() },
                        onDoubleTap = { tap ->
                            if (p.scale > 1.01f) view(1f, Offset.Zero) else {
                                val target = 3f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                view(target, (center - tap) * (target - 1f))
                            }
                        },
                    )
                }
                .pointerInput(p) {
                    val step = 28.dp.toPx()
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        activate()
                        var acc = 0f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed == 1 && (mode || p.scale <= 1.01f)) {
                                val change = event.changes.first { it.pressed }
                                if (!change.positionChanged()) continue
                                val d = change.position - change.previousPosition
                                if (mode && p.hasWindow) {
                                    val w = p.window ?: continue
                                    onWindowDrag(w, d.x / size.width, d.y / size.height, p.valueRange, windowCb)
                                } else if (!mode && p.count > 1) {
                                    // Not zoomed in: a vertical swipe steps through the slices.
                                    acc += d.y
                                    while (abs(acc) >= step) {
                                        sliceCb(p.position + if (acc > 0) 1 else -1)
                                        acc -= step * if (acc > 0) 1 else -1
                                    }
                                }
                                change.consume()
                            } else if (pressed >= 1) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val newScale = (p.scale * zoom).coerceIn(1f, MAX_ZOOM)
                                if (newScale != p.scale || (p.scale > 1f && pan != Offset.Zero)) {
                                    val centroid = event.changes.filter { it.pressed }
                                        .fold(Offset.Zero) { a, c -> a + c.position } / pressed.toFloat()
                                    val focus = centroid - Offset(size.width / 2f, size.height / 2f)
                                    val moved = (p.offset - focus) * (newScale / p.scale) + focus + pan
                                    view(newScale, moved)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val imageModifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = p.scale; scaleY = p.scale
                translationX = p.offset.x; translationY = p.offset.y
            }
            val f = p.file
            when {
                f == null -> Text("No images in this study", color = Color.White, modifier = Modifier.padding(24.dp))
                f.kind == VaultFile.KIND_DICOM -> {
                    val r = p.rendered
                    when {
                        r != null -> Image(
                            bitmap = r.image, contentDescription = f.filename, contentScale = ContentScale.Fit,
                            colorFilter = p.colorFilter(), modifier = imageModifier,
                        )
                        p.error != null -> Text("Couldn't render: ${p.error}", color = Color.White,
                            modifier = Modifier.padding(24.dp))
                        else -> CircularProgressIndicator()
                    }
                }
                else -> AsyncImage(
                    model = container.api.contentUrl(f.id), contentDescription = f.filename,
                    contentScale = ContentScale.Fit, colorFilter = p.colorFilter(), modifier = imageModifier,
                )
            }
            Hud(p, Modifier.align(Alignment.TopStart))
            if (p.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.BottomCenter))
        }
        if (p.count > 1) {
            Slider(
                value = p.position.toFloat(),
                onValueChange = { activate(); sliceCb(it.roundToInt()) },
                valueRange = 0f..(p.count - 1).toFloat(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
    }
}

private fun onWindowDrag(w: WindowLevel, fx: Float, fy: Float, range: Double, cb: (WindowLevel) -> Unit) {
    cb(w.dragged(fx, fy, range))
}

@Composable
private fun PaneHeader(p: Pane, onSeries: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.study.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(p.study.studyDate ?: "No date", p.study.modality).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (p.groups.size > 1) {
                Box {
                    TextButton(onClick = { open = true }) {
                        Text(p.groups.getOrNull(p.group)?.label ?: "Series", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(120.dp))
                        Icon(Icons.Default.ArrowDropDown, "Choose series")
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        p.groups.forEachIndexed { i, g ->
                            DropdownMenuItem(
                                text = { Text("${g.label} (${g.files.size})") },
                                onClick = { open = false; if (i != p.group) onSeries(i) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hud(p: Pane, modifier: Modifier) {
    val parts = buildList {
        val w = p.window
        if (p.isDicom && p.hasWindow && w != null) add("W ${w.width.roundToInt()} · L ${w.center.roundToInt()}${if (p.isCt) " HU" else ""}")
        if (p.count > 1) add("${if (p.files.size > 1) "Image" else "Frame"} ${p.position + 1}/${p.count}")
        add("${(p.scale * 100).roundToInt()}%")
    }
    Row(modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEach {
            Text(
                it, color = Color(0xFFD8F3EF), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

/** Decodes a rendered frame, downsampling anything over 4096 px a side so it can't exhaust memory. */
private fun decodeBounded(bytes: ByteArray, maxSide: Int = 4096): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
