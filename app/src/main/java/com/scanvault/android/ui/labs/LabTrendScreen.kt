package com.scanvault.android.ui.labs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanvault.android.data.AppContainer
import com.scanvault.android.data.LabPoint
import com.scanvault.android.data.LabSeries
import com.scanvault.android.data.SessionState
import com.scanvault.android.ui.components.Loadable
import com.scanvault.android.ui.components.LoadableContent
import com.scanvault.android.ui.components.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LabTrendViewModel(private val container: AppContainer, private val patientId: String, private val key: String) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<LabSeries>>(Loadable.Loading)
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            container.call { it.labSeries(patientId, key) }
                .onSuccess { _state.value = Loadable.Loaded(it) }
                .onFailure { _state.value = Loadable.Failed(it.userMessage()) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabTrendScreen(container: AppContainer, patientId: String, key: String, onBack: () -> Unit) {
    val vm: LabTrendViewModel = viewModel(key = "labtrend-$patientId-$key") { LabTrendViewModel(container, patientId, key) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    LaunchedEffect(session) { if (session == SessionState.Ready) vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? Loadable.Loaded<LabSeries>)?.value?.name ?: "Lab trend") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LoadableContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { s ->
            // Only one unit is graphed: the latest result's. Others are listed below.
            val unit = s.points.lastOrNull()?.unit.orEmpty()
            val same = s.points.filter { it.unit.orEmpty() == unit }
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            listOfNotNull(s.loinc?.let { "LOINC $it" }, unit.ifBlank { null }, same.lastOrNull()?.let(::refText)?.ifBlank { null }?.let { "Reference $it" })
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (same.size < s.points.size) {
                            Text("${s.points.size - same.size} result(s) use a different unit and are listed but not graphed.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    val geo = remember(same) { chartGeometry(same) }
                    if (geo == null) {
                        Text("These results are not numbers, so there is nothing to graph.", Modifier.padding(16.dp))
                    } else {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { TrendChart(geo) }
                        Text(
                            "Shaded band: the reference range from the latest result. ▲/▼ marks results outside it. Tap a point for its value.",
                            Modifier.padding(16.dp, 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(s.points.reversed(), key = { it.id ?: (it.date + it.value) }) { p ->
                    ListItem(
                        overlineContent = { Text(p.date.orEmpty() + if (p.source == "previous") " · from a later report" else "") },
                        headlineContent = {
                            Text("${p.value} ${p.unit.orEmpty()}".trim() + (p.flag?.let { "  ${flagGlyph(it)} ${FLAG_LABELS[it] ?: it}" } ?: ""),
                                fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = { Text(listOfNotNull(refText(p).ifBlank { null }?.let { "ref $it" }, p.labName).joinToString(" · ")) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TrendChart(geo: ChartGeometry) {
    val line = MaterialTheme.colorScheme.primary
    val bandColor = MaterialTheme.colorScheme.secondaryContainer
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.colorScheme.onSurfaceVariant
    val alert = MaterialTheme.colorScheme.error
    val surface = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    var selected by remember(geo) { mutableStateOf(geo.points.lastIndex) }
    val sel = geo.plotted[selected.coerceIn(0, geo.plotted.lastIndex)]
    val label = TextStyle(fontSize = 11.sp, color = axis)
    val desc = geo.plotted.joinToString("; ") { "${it.date}: ${it.value}" }

    Column(Modifier.padding(12.dp)) {
        Text("${sel.value} ${sel.unit.orEmpty()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(listOfNotNull(sel.date, sel.labName, sel.flag?.let { FLAG_LABELS[it] }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = axis)
        Canvas(
            Modifier.fillMaxWidth().height(220.dp).padding(top = 8.dp)
                .semantics { contentDescription = "Values over time: $desc" }
                .pointerInput(geo) {
                    detectTapGestures { pos ->
                        val left = 40.dp.toPx(); val right = 12.dp.toPx()
                        val w = size.width - left - right
                        selected = geo.points.indices.minByOrNull { Math.abs(left + geo.points[it].first * w - pos.x) } ?: selected
                    }
                },
        ) {
            val left = 40.dp.toPx(); val right = 12.dp.toPx(); val top = 8.dp.toPx(); val bottom = 22.dp.toPx()
            val w = size.width - left - right
            val h = size.height - top - bottom
            fun X(f: Float) = left + f * w
            fun Y(f: Float) = top + (1f - f) * h
            for (i in 0..4) {
                val f = i / 4f
                drawLine(grid, Offset(left, Y(f)), Offset(left + w, Y(f)), 1f)
                val v = geo.yMin + (geo.yMax - geo.yMin) * f
                val t = measurer.measure(if (geo.yMax - geo.yMin < 5) "%.2f".format(v).trimEnd('0').trimEnd('.') else Math.round(v).toString(), label)
                drawText(t, topLeft = Offset(left - t.size.width - 6f, Y(f) - t.size.height / 2))
            }
            geo.band?.let { (b, t) -> drawRect(bandColor, Offset(left, Y(t)), Size(w, Y(b) - Y(t))) }
            val path = Path()
            geo.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(X(x), Y(y)) else path.lineTo(X(x), Y(y)) }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
            val sx = X(geo.points[selected.coerceIn(0, geo.points.lastIndex)].first)
            drawLine(axis, Offset(sx, top), Offset(sx, top + h), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            geo.points.forEachIndexed { i, (x, y) ->
                val p: LabPoint = geo.plotted[i]
                val d = direction(p, geo.low, geo.high)
                val c = if (d != 0) alert else line
                drawCircle(surface, 6.dp.toPx(), Offset(X(x), Y(y)))
                drawCircle(c, (if (i == selected) 5.5f else 4.5f).dp.toPx(), Offset(X(x), Y(y)))
                if (d != 0) {
                    val g = measurer.measure(if (d > 0) "▲" else "▼", TextStyle(fontSize = 10.sp, color = alert))
                    drawText(g, topLeft = Offset(X(x) - g.size.width / 2, if (d > 0) Y(y) - 10.dp.toPx() - g.size.height / 2 else Y(y) + 6.dp.toPx()))
                }
            }
            val first = measurer.measure(geo.plotted.first().date.orEmpty(), label)
            drawText(first, topLeft = Offset(left, size.height - first.size.height))
            if (geo.plotted.size > 1) {
                val last = measurer.measure(geo.plotted.last().date.orEmpty(), label)
                drawText(last, topLeft = Offset(left + w - last.size.width, size.height - last.size.height))
            }
        }
    }
}
