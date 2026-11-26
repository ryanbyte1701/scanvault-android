package com.scanvault.android.ui.patients

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanvault.android.data.AppContainer
import com.scanvault.android.data.Patient
import com.scanvault.android.data.SessionState
import com.scanvault.android.data.Study
import com.scanvault.android.data.StudyInput
import com.scanvault.android.data.uploadPartFor
import com.scanvault.android.ui.components.EmptyState
import com.scanvault.android.ui.components.Loadable
import com.scanvault.android.ui.components.LoadableContent
import com.scanvault.android.ui.components.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PatientDetail(val patient: Patient, val studies: List<Study>)

class PatientDetailViewModel(private val container: AppContainer, private val patientId: String) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<PatientDetail>>(Loadable.Loading)
    val state = _state.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    fun load() {
        if (_state.value !is Loadable.Loaded) _state.value = Loadable.Loading
        viewModelScope.launch {
            container.call { api ->
                PatientDetail(api.patient(patientId), api.studies(patientId).sortedByDescending { it.studyDate ?: it.createdAt })
            }.onSuccess { _state.value = Loadable.Loaded(it) }
                .onFailure { _state.value = Loadable.Failed(it.userMessage()) }
        }
    }

    suspend fun createStudy(input: StudyInput): Result<Study> =
        container.call { it.createStudy(patientId, input) }.onSuccess { load() }

    suspend fun delete(): Result<Unit> = container.call { it.deletePatient(patientId) }

    /** Sends DICOM files or a zipped imaging-CD export; the server groups them into studies. */
    suspend fun importFiles(parts: List<com.scanvault.android.data.UploadPart>): Result<String> {
        _importing.value = true
        val result = container.call { it.importToPatient(patientId, parts) }
        _importing.value = false
        load()
        return result.map { r ->
            buildString {
                append("Imported ${r.files.size} file(s) into ${r.studies.size} study(ies)")
                if (r.skipped.isNotEmpty()) append(", skipped ${r.skipped.size}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientDetailScreen(
    container: AppContainer,
    patientId: String,
    onBack: () -> Unit,
    onOpenStudy: (String) -> Unit,
) {
    val vm: PatientDetailViewModel = viewModel(key = "patient-$patientId") { PatientDetailViewModel(container, patientId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resolver = LocalContext.current.contentResolver
    var showAdd by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(session) { if (session == SessionState.Ready) vm.load() }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val parts = uris.map { resolver.uploadPartFor(it) }
            vm.importFiles(parts)
                .onSuccess { snackbar.showSnackbar(it) }
                .onFailure { snackbar.showSnackbar("Import failed: ${it.userMessage()}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((state as? Loadable.Loaded<PatientDetail>)?.value?.patient?.name ?: "Patient") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Import DICOM / CD (.zip)") },
                            leadingIcon = { Icon(Icons.Default.FolderZip, null) },
                            onClick = {
                                menu = false
                                importPicker.launch(arrayOf("application/zip", "application/dicom", "application/octet-stream", "*/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete patient") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Study") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadableContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { detail ->
            Column(Modifier.padding(padding)) {
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                PatientHeader(detail.patient)
                HorizontalDivider()
                if (detail.studies.isEmpty()) {
                    EmptyState("No studies yet. Add one for a scan visit, or import a DICOM CD export from the menu.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                        items(detail.studies, key = { it.id }) { s ->
                            ListItem(
                                leadingContent = { Icon(Icons.Default.MedicalServices, null) },
                                headlineContent = { Text(s.title) },
                                overlineContent = {
                                    Text(listOfNotNull(s.modality, s.studyDate).joinToString(" · "))
                                },
                                supportingContent = {
                                    Text(listOfNotNull(s.facility, "${s.fileCount} file(s)").joinToString(" · "))
                                },
                                modifier = Modifier.clickable { onOpenStudy(s.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddStudyDialog(onDismiss = { showAdd = false }, onSave = { vm.createStudy(it).map { } })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete patient?") },
            text = { Text("This removes the patient and all of their studies and files from the vault. Existing backups are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        vm.delete().onSuccess { onBack() }
                            .onFailure { snackbar.showSnackbar("Delete failed: ${it.userMessage()}") }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PatientHeader(p: Patient) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val facts = listOfNotNull(
            p.dateOfBirth?.let { "DOB $it" },
            p.sex,
            p.mrn?.let { "MRN $it" },
        )
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
        p.notes?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddStudyDialog(onDismiss: () -> Unit, onSave: suspend (StudyInput) -> Result<Unit>) {
    var title by remember { mutableStateOf("") }
    var modality by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var facility by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dateValid = date.isBlank() || Regex("""\d{4}-\d{2}-\d{2}""").matches(date.trim())

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New study") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title, e.g. MRI left knee") }, singleLine = true)
                OutlinedTextField(modality, { modality = it }, label = { Text("Modality (MR, CT, XR, US…)") }, singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Study date (YYYY-MM-DD)") },
                    singleLine = true, isError = !dateValid)
                OutlinedTextField(facility, { facility = it }, label = { Text("Imaging center") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Notes") }, minLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && title.isNotBlank() && dateValid,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        onSave(StudyInput(
                            title = title.trim(),
                            modality = modality.trim().ifBlank { null },
                            studyDate = date.trim().ifBlank { null },
                            facility = facility.trim().ifBlank { null },
                            description = description.trim().ifBlank { null },
                        )).onSuccess { onDismiss() }.onFailure { error = it.userMessage() }
                        busy = false
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
