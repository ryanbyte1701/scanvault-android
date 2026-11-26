package com.scanvault.android.ui.patients

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scanvault.android.data.AppContainer
import com.scanvault.android.data.Patient
import com.scanvault.android.data.PatientInput
import com.scanvault.android.data.SessionState
import com.scanvault.android.ui.components.EmptyState
import com.scanvault.android.ui.components.Loadable
import com.scanvault.android.ui.components.LoadableContent
import com.scanvault.android.ui.components.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PatientsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<List<Patient>>>(Loadable.Loading)
    val state = _state.asStateFlow()

    fun load() {
        if (_state.value !is Loadable.Loaded) _state.value = Loadable.Loading
        viewModelScope.launch {
            container.call { it.patients() }
                .onSuccess { list -> _state.value = Loadable.Loaded(list.sortedBy { it.name.lowercase() }) }
                .onFailure { _state.value = Loadable.Failed(it.userMessage()) }
        }
    }

    suspend fun create(input: PatientInput): Result<Patient> =
        container.call { it.createPatient(input) }.onSuccess { load() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientsScreen(container: AppContainer, onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm: PatientsViewModel = viewModel { PatientsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }

    // Load on entry, and again once a locked vault has been unlocked.
    LaunchedEffect(session) { if (session == SessionState.Ready) vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Patients") },
                actions = {
                    IconButton(onClick = vm::load) { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Patient") },
            )
        },
    ) { padding ->
        LoadableContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { patients ->
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search patients") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val filtered = patients.filter {
                    query.isBlank() || it.name.contains(query, true) || (it.mrn?.contains(query, true) == true)
                }
                if (filtered.isEmpty()) {
                    EmptyState(if (patients.isEmpty()) "No patients yet. Add one to start storing scans." else "No matches.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                        items(filtered, key = { it.id }) { p ->
                            ListItem(
                                leadingContent = { Icon(Icons.Default.Person, null) },
                                headlineContent = { Text(p.name) },
                                supportingContent = {
                                    Text(listOfNotNull(
                                        p.dateOfBirth?.let { "DOB $it" },
                                        p.mrn?.let { "MRN $it" },
                                        "${p.studyCount} ${if (p.studyCount == 1) "study" else "studies"}",
                                    ).joinToString(" · "))
                                },
                                modifier = Modifier.clickable { onOpen(p.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddPatientDialog(
            onDismiss = { showAdd = false },
            onSave = { input -> vm.create(input).map { } },
        )
    }
}

@Composable
private fun AddPatientDialog(onDismiss: () -> Unit, onSave: suspend (PatientInput) -> Result<Unit>) {
    var name by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("") }
    var mrn by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dobValid = dob.isBlank() || Regex("""\d{4}-\d{2}-\d{2}""").matches(dob.trim())

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New patient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(dob, { dob = it }, label = { Text("Date of birth (YYYY-MM-DD)") },
                    singleLine = true, isError = !dobValid)
                OutlinedTextField(sex, { sex = it }, label = { Text("Sex (optional)") }, singleLine = true)
                OutlinedTextField(mrn, { mrn = it }, label = { Text("MRN (optional)") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, minLines = 2)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank() && dobValid,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        onSave(PatientInput(
                            name = name.trim(),
                            dateOfBirth = dob.trim().ifBlank { null },
                            sex = sex.trim().ifBlank { null },
                            mrn = mrn.trim().ifBlank { null },
                            notes = notes.trim().ifBlank { null },
                        )).onSuccess { onDismiss() }.onFailure { error = it.userMessage() }
                        busy = false
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
