package com.scanvault.android.ui.labs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanvault.android.data.AppContainer
import com.scanvault.android.data.LabCatalogEntry
import com.scanvault.android.data.LabReport
import com.scanvault.android.data.LabReportUpdate
import com.scanvault.android.data.LabRow
import com.scanvault.android.data.SessionState
import com.scanvault.android.ui.components.GuardDialogTouches
import com.scanvault.android.ui.components.Loadable
import com.scanvault.android.ui.components.LoadableContent
import com.scanvault.android.ui.components.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val LABS = linkedMapOf("labcorp" to "Labcorp", "sonora_quest" to "Sonora Quest", "quest" to "Quest Diagnostics", "other" to "Other")

class LabReviewViewModel(private val container: AppContainer, private val reportId: String) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<LabReport>>(Loadable.Loading)
    val state = _state.asStateFlow()
    /** The rows being edited; kept here so a rotation doesn't lose corrections. */
    val rows = MutableStateFlow<List<LabRow>>(emptyList())
    val lab = MutableStateFlow("other")
    val collected = MutableStateFlow("")
    val catalog = MutableStateFlow<List<LabCatalogEntry>>(emptyList())
    private var loaded = false

    fun load() {
        if (loaded) return
        viewModelScope.launch {
            container.call { api -> api.labReport(reportId) to runCatching { api.labInfo().catalog }.getOrDefault(emptyList()) }
                .onSuccess { (r, cat) ->
                    loaded = true
                    rows.value = r.rows
                    lab.value = r.lab ?: "other"
                    collected.value = r.collected.orEmpty()
                    catalog.value = cat
                    _state.value = Loadable.Loaded(r)
                }.onFailure { _state.value = Loadable.Failed(it.userMessage()) }
        }
    }

    private fun update(save: Boolean) = LabReportUpdate(
        lab = lab.value,
        collected = collected.value.trim().ifBlank { null },
        rows = rows.value.filter { it.name.isNotBlank() || it.value.isNotBlank() },
        save = save,
    )

    suspend fun save(): Result<LabReport> = container.call { it.updateLabReport(reportId, update(save = true)) }

    /** Keeps the edits in the draft so the review can be finished later. */
    suspend fun keepDraft(): Result<LabReport> = container.call { it.updateLabReport(reportId, update(save = false)) }

    suspend fun delete(): Result<Unit> = container.call { it.deleteLabReport(reportId) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabReviewScreen(container: AppContainer, reportId: String, onDone: () -> Unit) {
    val vm: LabReviewViewModel = viewModel(key = "labreview-$reportId") { LabReviewViewModel(container, reportId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val lab by vm.lab.collectAsStateWithLifecycle()
    val collected by vm.collected.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var labMenu by remember { mutableStateOf(false) }

    LaunchedEffect(session) { if (session == SessionState.Ready) vm.load() }

    val report = (state as? Loadable.Loaded<LabReport>)?.value
    val saved = report != null && !report.isDraft
    val leave: () -> Unit = {
        if (report != null && report.isDraft) scope.launch { vm.keepDraft(); onDone() } else onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (saved) "Edit lab report" else "Review lab results") },
                navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (report?.fileId != null) IconButton(onClick = { openLabPdf(context, container, report, scope, snackbar) }) {
                        Icon(Icons.Default.PictureAsPdf, "Open the PDF")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, if (saved) "Delete report" else "Discard") }
                },
            )
        },
        bottomBar = {
            if (report != null) Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.rows.value = vm.rows.value + LabRow(source = "manual", confidence = "high"); editing = vm.rows.value.lastIndex }) {
                    Icon(Icons.Default.Add, null); Text("Add")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !busy && DATE.matches(collected.trim()) && rows.any { it.include },
                    onClick = {
                        busy = true
                        scope.launch {
                            vm.save().onSuccess { onDone() }.onFailure { snackbar.showSnackbar(it.userMessage()) }
                            busy = false
                        }
                    },
                ) { Text("Save ${rows.count { it.include }} results") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadableContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { r ->
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (r.duplicate) item { Note("This file was uploaded before. You are looking at that report.") }
                items(r.warnings) { Note(it) }
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Lab: ", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { labMenu = true }) { Text(LABS[lab] ?: lab) }
                            DropdownMenu(expanded = labMenu, onDismissRequest = { labMenu = false }) {
                                LABS.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { vm.lab.value = k; labMenu = false }) }
                            }
                        }
                        OutlinedTextField(
                            collected, { vm.collected.value = it },
                            label = { Text("Collected (YYYY-MM-DD)") }, singleLine = true,
                            isError = !DATE.matches(collected.trim()),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            (if (r.method == "ocr") "Read with OCR: check every value. " else "") +
                                "Tap a result to correct it. Untick anything you don't want to keep.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
                itemsIndexed(rows) { i, row ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = i }.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(row.include, { on -> vm.rows.value = rows.toMutableList().also { it[i] = row.copy(include = on) } })
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(row.name.ifBlank { "New result" }, fontWeight = FontWeight.SemiBold)
                                if (row.confidence == "low") Badge("check", warn = true)
                            }
                            val ref = refText(com.scanvault.android.data.LabPoint(refLow = row.refLow, refHigh = row.refHigh, refText = row.refText))
                            Text(
                                listOfNotNull(
                                    catalog.firstOrNull { it.key == row.analyte }?.name?.takeIf { !it.equals(row.name, true) }?.let { "→ $it" },
                                    ref.ifBlank { null }?.let { "ref $it" },
                                    row.prevValue?.let { "prev $it (${row.prevDate})" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${row.value} ${row.unit.orEmpty()}".trim(), fontWeight = FontWeight.SemiBold)
                            row.flag?.let { Text("${flagGlyph(it)} ${FLAG_LABELS[it] ?: it}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { i ->
        val row = vm.rows.value.getOrNull(i)
        if (row == null) editing = null else RowDialog(
            row, catalog,
            onDismiss = { editing = null },
            onSave = { updated -> vm.rows.value = vm.rows.value.toMutableList().also { it[i] = updated }; editing = null },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { GuardDialogTouches(); Text(if (saved) "Delete lab report?" else "Discard this report?") },
            text = { Text((if (saved) "Its results are removed from the graphs. " else "") + "The uploaded PDF is deleted too.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { vm.delete().onSuccess { onDone() }.onFailure { snackbar.showSnackbar(it.userMessage()) } }
                }) { Text(if (saved) "Delete" else "Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Note(text: String) {
    Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RowDialog(row: LabRow, catalog: List<LabCatalogEntry>, onDismiss: () -> Unit, onSave: (LabRow) -> Unit) {
    var name by rememberSaveable { mutableStateOf(row.name) }
    var value by rememberSaveable { mutableStateOf(row.value) }
    var unit by rememberSaveable { mutableStateOf(row.unit.orEmpty()) }
    var low by rememberSaveable { mutableStateOf(row.refLow?.let(::num).orEmpty()) }
    var high by rememberSaveable { mutableStateOf(row.refHigh?.let(::num).orEmpty()) }
    var flag by rememberSaveable { mutableStateOf(row.flag) }
    var analyte by rememberSaveable { mutableStateOf(row.analyte?.takeIf { !it.startsWith("x_") }) }
    var flagMenu by remember { mutableStateOf(false) }
    var pickTest by remember { mutableStateOf(false) }
    val numOk = { s: String -> s.isBlank() || s.trim().toDoubleOrNull() != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { GuardDialogTouches(); Text("Result") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Test") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value, { value = it }, label = { Text("Result") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(low, { low = it }, label = { Text("Range low") }, singleLine = true, isError = !numOk(low),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(high, { high = it }, label = { Text("Range high") }, singleLine = true, isError = !numOk(high),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Flag: ")
                    TextButton(onClick = { flagMenu = true }) { Text(flag?.let { FLAG_LABELS[it] } ?: "None") }
                    DropdownMenu(expanded = flagMenu, onDismissRequest = { flagMenu = false }) {
                        DropdownMenuItem(text = { Text("None") }, onClick = { flag = null; flagMenu = false })
                        FLAG_LABELS.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { flag = k; flagMenu = false }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Graph as: ")
                    TextButton(onClick = { pickTest = true }) {
                        Text(catalog.firstOrNull { it.key == analyte }?.name ?: "Its own name")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && value.isNotBlank() && numOk(low) && numOk(high),
                onClick = {
                    onSave(row.copy(
                        name = name.trim(), value = value.trim(), unit = unit.trim().ifBlank { null },
                        refLow = low.trim().toDoubleOrNull(), refHigh = high.trim().toDoubleOrNull(),
                        refText = if (low.isBlank() && high.isBlank()) row.refText else null,
                        flag = flag, analyte = analyte, confidence = "high",
                    ))
                },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickTest) {
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pickTest = false },
            title = { GuardDialogTouches(); Text("Graph as") },
            text = {
                Column {
                    OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        item {
                            Text("Its own name (not in the list)", Modifier.fillMaxWidth().clickable { analyte = null; pickTest = false }.padding(12.dp))
                        }
                        items(catalog.filter { q.isBlank() || it.name.contains(q, true) || (it.loinc ?: "").contains(q) }, key = { it.key }) { c ->
                            Text("${c.name} · ${c.panel.orEmpty()}", Modifier.fillMaxWidth().clickable { analyte = c.key; pickTest = false }.padding(12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickTest = false }) { Text("Close") } },
        )
    }
}
