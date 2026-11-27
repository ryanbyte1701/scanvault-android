package com.scanvault.android.ui.labs

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanvault.android.data.AppContainer
import com.scanvault.android.data.LabAnalyte
import com.scanvault.android.data.LabReport
import com.scanvault.android.data.Patient
import com.scanvault.android.data.SessionState
import com.scanvault.android.data.UploadPart
import com.scanvault.android.data.crypto.FileVault
import com.scanvault.android.data.crypto.SecureFileProvider
import com.scanvault.android.data.uploadPartFor
import com.scanvault.android.ui.components.EmptyState
import com.scanvault.android.ui.components.Loadable
import com.scanvault.android.ui.components.LoadableContent
import com.scanvault.android.ui.components.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LabsOverview(val patient: Patient, val analytes: List<LabAnalyte>, val reports: List<LabReport>)

class LabsViewModel(private val container: AppContainer, private val patientId: String) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<LabsOverview>>(Loadable.Loading)
    val state = _state.asStateFlow()
    private val _uploading = MutableStateFlow(false)
    val uploading = _uploading.asStateFlow()
    val messages = Channel<String>(Channel.BUFFERED)
    /** A freshly uploaded report to open for review. */
    val openReview = Channel<String>(Channel.BUFFERED)

    fun load() {
        if (_state.value !is Loadable.Loaded) _state.value = Loadable.Loading
        viewModelScope.launch {
            container.call { api ->
                coroutineScope {
                    val p = async { api.patient(patientId) }
                    val a = async { api.labAnalytes(patientId) }
                    val r = async { api.labReports(patientId) }
                    LabsOverview(p.await(), a.await(), r.await())
                }
            }.onSuccess { _state.value = Loadable.Loaded(it) }
                .onFailure { _state.value = Loadable.Failed(it.userMessage()) }
        }
    }

    fun upload(part: UploadPart) {
        if (_uploading.value) return
        _uploading.value = true
        viewModelScope.launch {
            val result = try {
                container.call { it.uploadLabReport(patientId, part) }
            } finally {
                _uploading.value = false
            }
            load()
            result.onSuccess {
                if (it.duplicate) messages.trySend("This report was uploaded before")
                openReview.trySend(it.id)
            }.onFailure { messages.trySend("Upload failed: ${it.userMessage()}") }
        }
    }

    suspend fun newManualReport(): Result<LabReport> =
        container.call { it.createLabReport(patientId, com.scanvault.android.data.LabReportUpdate(lab = "other")) }
}

/** Downloads a report's PDF into the encrypted vault and returns a link another app can read once. */
suspend fun shareLabPdf(container: AppContainer, fileId: String, name: String): Result<android.net.Uri> =
    container.call { api ->
        withContext(Dispatchers.IO) { container.vault.pruneShared(maxAgeMillis = 60 * 60_000L) }
        var entry: FileVault.Entry? = null
        api.download(fileId) { input -> entry = container.vault.store(input) }
        SecureFileProvider.share(container.context, entry!!, name.replace(Regex("""[^\w.\- ]"""), "_").ifBlank { "lab-report.pdf" }, "application/pdf")
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabsScreen(
    container: AppContainer,
    patientId: String,
    onBack: () -> Unit,
    onOpenReport: (String) -> Unit,
    onOpenAnalyte: (String) -> Unit,
) {
    val vm: LabsViewModel = viewModel(key = "labs-$patientId") { LabsViewModel(container, patientId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver

    LaunchedEffect(session) { if (session == SessionState.Ready) vm.load() }
    LaunchedEffect(vm) { for (m in vm.messages) snackbar.showSnackbar(m) }
    LaunchedEffect(vm) { for (id in vm.openReview) onOpenReport(id) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val part = runCatching { resolver.uploadPartFor(uri) }.getOrNull()
        if (part != null) vm.upload(part) else scope.launch { snackbar.showSnackbar("That file couldn't be read") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lab results") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            vm.newManualReport().onSuccess { onOpenReport(it.id) }
                                .onFailure { snackbar.showSnackbar(it.userMessage()) }
                        }
                    }) { Icon(Icons.Default.Edit, "Enter results by hand") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!uploading) {
                        container.appLock.expectExternalTrip()
                        picker.launch(arrayOf("application/pdf", "image/*"))
                    }
                },
                icon = { Icon(Icons.Default.UploadFile, null) },
                text = { Text("Lab report") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadableContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { o ->
            Column(Modifier.padding(padding)) {
                if (uploading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Reading the report…", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                val drafts = o.reports.filter { it.isDraft }
                if (o.analytes.isEmpty() && drafts.isEmpty()) {
                    EmptyState("No lab results yet for ${o.patient.name}. Upload a Labcorp, Quest or Sonora Quest report PDF. You check every value before it is saved.")
                    return@Column
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    if (drafts.isNotEmpty()) item {
                        Card(
                            Modifier.fillMaxWidth().padding(16.dp, 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${drafts.size} report(s) waiting for review", fontWeight = FontWeight.SemiBold)
                                Text("Values are graphed after you check and save them.", style = MaterialTheme.typography.bodySmall)
                                drafts.forEach { d ->
                                    TextButton(onClick = { onOpenReport(d.id) }) {
                                        Text("Review " + listOfNotNull(d.labName ?: d.filename, d.collected).joinToString(" · "))
                                    }
                                }
                            }
                        }
                    }
                    val flagged = o.analytes.filter { it.latest.flag != null }
                    if (flagged.isNotEmpty()) {
                        item { SectionTitle("Outside the reference range") }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(flagged, key = { it.key }) { a ->
                                    AssistChip(
                                        onClick = { onOpenAnalyte(a.key) },
                                        label = { Text("${a.name} ${a.latest.value} ${flagGlyph(a.latest.flag)}") },
                                        leadingIcon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
                                    )
                                }
                            }
                        }
                    }
                    o.analytes.groupBy { it.panel }.forEach { (panel, list) ->
                        item(key = "h-$panel") { SectionTitle(panel) }
                        items(list, key = { it.key }) { a -> AnalyteRow(a) { onOpenAnalyte(a.key) } }
                    }
                    val saved = o.reports.filter { !it.isDraft }
                    if (saved.isNotEmpty()) {
                        item { SectionTitle("Reports") }
                        items(saved, key = { "r-" + it.id }) { r ->
                            ListItem(
                                headlineContent = { Text(r.labName ?: "Lab report") },
                                overlineContent = { Text(r.collected ?: "") },
                                supportingContent = {
                                    Text("${r.resultCount} results" + if (r.flaggedCount > 0) " · ${r.flaggedCount} flagged" else "")
                                },
                                modifier = Modifier.clickable { onOpenReport(r.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

fun flagGlyph(flag: String?): String = when (flag) {
    "H", "HH" -> "▲"
    "L", "LL" -> "▼"
    null -> ""
    else -> "!"
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AnalyteRow(a: LabAnalyte, onClick: () -> Unit) {
    val flagText = a.latest.flag?.let { "${flagGlyph(it)} ${FLAG_LABELS[it] ?: it}" }
    ListItem(
        headlineContent = { Text(a.name) },
        supportingContent = {
            Text(listOfNotNull(a.latest.date, "${a.count} result(s)", refText(a.latest).ifBlank { null }?.let { "ref $it" }).joinToString(" · "))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Sparkline(a.sparkValues, a.latest.refLow, a.latest.refHigh)
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${a.latest.value} ${a.latest.unit.orEmpty()}".trim(), fontWeight = FontWeight.SemiBold)
                    if (flagText != null) Text(flagText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

@Composable
private fun Sparkline(values: List<Double>, low: Double?, high: Double?) {
    if (values.isEmpty()) return
    val line = MaterialTheme.colorScheme.primary
    val band = MaterialTheme.colorScheme.secondaryContainer
    Canvas(Modifier.width(72.dp).height(28.dp).clearAndSetSemantics { }) {
        var lo = listOfNotNull(values.min(), low).min()
        var hi = listOfNotNull(values.max(), high).max()
        if (hi == lo) { hi += 1; lo -= 1 }
        fun y(v: Double) = size.height * (1f - ((v - lo) / (hi - lo)).toFloat())
        if (low != null || high != null) {
            val top = y(high ?: hi)
            drawRect(band, Offset(0f, top), Size(size.width, y(low ?: lo) - top))
        }
        if (values.size == 1) {
            drawCircle(line, 3.dp.toPx(), Offset(size.width / 2, y(values[0])))
        } else {
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = size.width * i / (values.size - 1)
                if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
            }
            drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/** Opens a report's PDF in another app (decrypted only through the app's one-time file link). */
fun openLabPdf(context: android.content.Context, container: AppContainer, report: LabReport, scope: kotlinx.coroutines.CoroutineScope, snackbar: SnackbarHostState) {
    val fileId = report.fileId ?: return
    scope.launch {
        shareLabPdf(container, fileId, report.filename ?: "lab-report.pdf").onSuccess { uri ->
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                container.appLock.expectExternalTrip()
                context.startActivity(Intent.createChooser(intent, "Open lab report"))
            } catch (e: ActivityNotFoundException) {
                snackbar.showSnackbar("No app can open PDFs")
            }
        }.onFailure { snackbar.showSnackbar("Download failed: ${it.userMessage()}") }
    }
}

@Composable
internal fun Badge(text: String, warn: Boolean = false) {
    Surface(
        color = if (warn) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}
