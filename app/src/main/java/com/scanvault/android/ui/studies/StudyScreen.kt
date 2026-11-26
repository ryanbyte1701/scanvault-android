package com.scanvault.android.ui.studies

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.scanvault.android.BuildConfig
import com.scanvault.android.data.AppContainer
import com.scanvault.android.data.SessionState
import com.scanvault.android.data.Study
import com.scanvault.android.data.UploadPart
import com.scanvault.android.data.VaultFile
import com.scanvault.android.data.kindFor
import com.scanvault.android.data.uploadPartFor
import com.scanvault.android.ui.components.EmptyState
import com.scanvault.android.ui.components.Loadable
import com.scanvault.android.ui.components.LoadableContent
import com.scanvault.android.ui.components.formatBytes
import com.scanvault.android.ui.components.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class StudyDetail(val study: Study, val files: List<VaultFile>)

data class UploadProgress(val done: Int, val total: Int, val current: String)

data class UploadSummary(val uploaded: Int, val duplicates: Int) {
    override fun toString(): String = buildString {
        append("Uploaded $uploaded file(s)")
        if (duplicates > 0) append(", $duplicates already in this study")
    }
}

class StudyViewModel(private val container: AppContainer, private val studyId: String) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<StudyDetail>>(Loadable.Loading)
    val state = _state.asStateFlow()
    private val _upload = MutableStateFlow<UploadProgress?>(null)
    val upload = _upload.asStateFlow()

    fun load() {
        if (_state.value !is Loadable.Loaded) _state.value = Loadable.Loading
        viewModelScope.launch {
            container.call { api -> StudyDetail(api.study(studyId), api.files(studyId)) }
                .onSuccess { _state.value = Loadable.Loaded(it) }
                .onFailure { _state.value = Loadable.Failed(it.userMessage()) }
        }
    }

    /** Uploads files one at a time so each gets its own kind and the user sees progress. */
    suspend fun uploadAll(parts: List<UploadPart>, forcedKind: String? = null): Result<UploadSummary> {
        var uploaded = 0
        var duplicates = 0
        var failure: Throwable? = null
        for ((i, part) in parts.withIndex()) {
            _upload.value = UploadProgress(i, parts.size, part.filename)
            container.call { it.upload(studyId, listOf(part), forcedKind ?: kindFor(part)) }
                .onSuccess { files -> if (files.isNotEmpty() && files.all { it.duplicate }) duplicates++ else uploaded++ }
                .onFailure { failure = it }
            if (failure != null) break
        }
        _upload.value = null
        load()
        return failure?.let { Result.failure<UploadSummary>(it) } ?: Result.success(UploadSummary(uploaded, duplicates))
    }

    suspend fun delete(file: VaultFile): Result<Unit> =
        container.call { it.deleteFile(file.id) }.onSuccess { load() }

    suspend fun deleteStudy(): Result<Unit> = container.call { it.deleteStudy(studyId) }

    /** Downloads a decrypted copy into the app cache so another app can open it. */
    suspend fun downloadForSharing(file: VaultFile): Result<File> = container.call { api ->
        val safeName = file.filename.replace(Regex("""[^\w.\- ]"""), "_").ifBlank { "file" }
        val dir = File(container.sharedDir(), file.id.replace(Regex("""[^\w\-]"""), "_")).apply { mkdirs() }
        val out = File(dir, safeName)
        api.download(file.id) { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StudyScreen(
    container: AppContainer,
    studyId: String,
    onBack: () -> Unit,
    onOpenImage: (fileId: String, name: String, isDicom: Boolean) -> Unit,
) {
    val vm: StudyViewModel = viewModel(key = "study-$studyId") { StudyViewModel(container, studyId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<VaultFile?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDeleteStudy by remember { mutableStateOf(false) }

    LaunchedEffect(session) { if (session == SessionState.Ready) vm.load() }

    fun startUpload(parts: List<UploadPart>, kind: String? = null, cleanup: () -> Unit = {}) {
        scope.launch {
            vm.uploadAll(parts, kind)
                .onSuccess { snackbar.showSnackbar(it.toString()) }
                .onFailure { snackbar.showSnackbar("Upload failed: ${it.userMessage()}") }
            cleanup()
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = pendingCapture ?: return@rememberLauncherForActivityResult
        pendingCapture = null
        val file = File(path)
        if (saved && file.length() > 0) {
            startUpload(listOf(context.contentResolver.uploadPartFor(Uri.fromFile(file))), VaultFile.KIND_IMAGE) {
                file.delete()
            }
        } else {
            file.delete()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) startUpload(uris.map { context.contentResolver.uploadPartFor(it) })
    }

    fun launchCamera() {
        val name = "scan_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".jpg"
        val file = File(container.captureDir(), name)
        pendingCapture = file.absolutePath
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingCapture = null
            scope.launch { snackbar.showSnackbar("No camera app available") }
        }
    }

    fun open(file: VaultFile) {
        if (file.isImage || file.kind == VaultFile.KIND_DICOM) {
            onOpenImage(file.id, file.filename, file.kind == VaultFile.KIND_DICOM)
            return
        }
        scope.launch {
            snackbar.showSnackbar("Opening ${file.filename}…", withDismissAction = false,
                duration = androidx.compose.material3.SnackbarDuration.Short)
        }
        scope.launch {
            vm.downloadForSharing(file).onSuccess { local ->
                val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", local)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, file.contentType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(Intent.createChooser(intent, "Open ${file.filename}"))
                } catch (e: ActivityNotFoundException) {
                    snackbar.showSnackbar("No app can open ${file.contentType}")
                }
            }.onFailure { snackbar.showSnackbar("Download failed: ${it.userMessage()}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text((state as? Loadable.Loaded<StudyDetail>)?.value?.study?.title ?: "Study",
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = vm::load) { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete study") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; confirmDeleteStudy = true },
                        )
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = ::launchCamera, enabled = upload == null, modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PhotoCamera, null)
                        Text("  Camera")
                    }
                    FilledTonalButton(
                        onClick = { picker.launch(arrayOf("image/*", "application/pdf", "application/dicom", "*/*")) },
                        enabled = upload == null, modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.AttachFile, null)
                        Text("  Files")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LoadableContent(state, onRetry = vm::load, modifier = Modifier.padding(padding)) { detail ->
            Column(Modifier.padding(padding)) {
                upload?.let { p ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Uploading ${p.done + 1} of ${p.total}: ${p.current}",
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(
                            progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { StudyHeader(detail.study) }
                    if (detail.files.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState("No files yet. Photograph a report or CD cover, or pick images, PDFs or DICOM files.")
                        }
                    }
                    items(detail.files, key = { it.id }) { f ->
                        FileTile(
                            container, f,
                            modifier = Modifier.combinedClickable(
                                onClick = { open(f) },
                                onLongClick = { toDelete = f },
                            ),
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete file?") },
            text = { Text("Remove ${f.filename} from this study?") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    scope.launch {
                        vm.delete(f).onFailure { snackbar.showSnackbar("Delete failed: ${it.userMessage()}") }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteStudy) {
        AlertDialog(
            onDismissRequest = { confirmDeleteStudy = false },
            title = { Text("Delete study?") },
            text = { Text("This removes the study and all of its files from the vault.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteStudy = false
                    scope.launch {
                        vm.deleteStudy().onSuccess { onBack() }
                            .onFailure { snackbar.showSnackbar("Delete failed: ${it.userMessage()}") }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteStudy = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StudyHeader(study: Study) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val facts = listOfNotNull(study.modality, study.studyDate, study.facility)
        if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
        study.description?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileTile(container: AppContainer, file: VaultFile, modifier: Modifier = Modifier) {
    Card(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            val preview = when {
                file.hasThumbnail -> container.api.thumbnailUrl(file.id)
                file.isImage -> container.api.contentUrl(file.id)
                else -> null
            }
            if (preview != null) {
                AsyncImage(
                    model = preview,
                    contentDescription = file.filename,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    when (file.kind) {
                        VaultFile.KIND_REPORT -> Icons.Default.Description
                        VaultFile.KIND_DICOM -> Icons.Default.ViewInAr
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    },
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(file.filename, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${file.kind} · ${formatBytes(file.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
