package com.scanvault.android.ui.studies

import com.scanvault.android.ui.components.GuardDialogTouches
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
import androidx.compose.material.icons.filled.Compare
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
import com.scanvault.android.data.PendingUpload
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.scanvault.android.data.crypto.FileVault
import com.scanvault.android.data.crypto.SecureFileProvider
import java.io.File

data class StudyDetail(val study: Study, val files: List<VaultFile>)

data class UploadProgress(val done: Int, val total: Int, val current: String)

data class UploadSummary(val uploaded: Int, val duplicates: Int, val skipped: Int = 0) {
    override fun toString(): String = buildString {
        append("Uploaded $uploaded file(s)")
        if (duplicates > 0) append(", $duplicates already in this study")
        if (skipped > 0) append(", $skipped skipped")
    }
}

/** MIME types the app will hand to another app as-is; anything else is offered as a generic file. */
private val SHAREABLE_TYPES = setOf(
    "application/pdf", "text/plain", "application/dicom", "application/zip",
    "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

fun safeViewType(contentType: String): String {
    val t = contentType.substringBefore(';').trim().lowercase()
    return if (t in SHAREABLE_TYPES || (t.startsWith("image/") && !t.contains("svg"))) t else "application/octet-stream"
}

class StudyViewModel(private val container: AppContainer, private val studyId: String) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<StudyDetail>>(Loadable.Loading)
    val state = _state.asStateFlow()
    private val _upload = MutableStateFlow<UploadProgress?>(null)
    val upload = _upload.asStateFlow()
    /** One-off messages for the snackbar; survives rotation because it lives in the ViewModel. */
    val messages = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)

    fun load() {
        if (_state.value !is Loadable.Loaded) _state.value = Loadable.Loading
        viewModelScope.launch {
            container.call { api -> StudyDetail(api.study(studyId), api.files(studyId)) }
                .onSuccess { _state.value = Loadable.Loaded(it) }
                .onFailure { _state.value = Loadable.Failed(it.userMessage()) }
        }
    }

    /**
     * Uploads in the ViewModel's scope, so rotating the phone or leaving the screen doesn't
     * cancel it, and progress is always cleared at the end.
     */
    fun startUpload(parts: List<UploadPart>, kind: String? = null) {
        if (_upload.value != null) return
        viewModelScope.launch {
            uploadAll(parts, kind)
                .onSuccess { messages.trySend(it.toString()) }
                .onFailure { messages.trySend("Upload failed: ${it.userMessage()}") }
        }
    }

    /** Uploads files one at a time so each gets its own kind and the user sees progress. */
    private suspend fun uploadAll(parts: List<UploadPart>, forcedKind: String? = null): Result<UploadSummary> {
        var uploaded = 0
        var duplicates = 0
        var skipped = 0
        var failure: Throwable? = null
        try {
            for ((i, part) in parts.withIndex()) {
                _upload.value = UploadProgress(i, parts.size, part.filename)
                container.call { it.upload(studyId, listOf(part), forcedKind ?: kindFor(part)) }
                    .onSuccess { files ->
                        // A zip expands server-side into many files, or none if all were skipped.
                        if (files.isEmpty()) skipped++
                        duplicates += files.count { it.duplicate }
                        uploaded += files.count { !it.duplicate }
                    }
                    .onFailure { failure = it }
                if (failure != null) break
            }
        } finally {
            _upload.value = null
        }
        load()
        return failure?.let { Result.failure<UploadSummary>(it) }
            ?: Result.success(UploadSummary(uploaded, duplicates, skipped))
    }

    suspend fun delete(file: VaultFile): Result<Unit> =
        container.call { it.deleteFile(file.id) }.onSuccess { load() }

    suspend fun deleteStudy(): Result<Unit> = container.call { it.deleteStudy(studyId) }

    /**
     * Downloads a file straight into the encrypted vault and returns a link another app can read
     * through [SecureFileProvider]; no decrypted copy is written to storage.
     */
    suspend fun downloadForSharing(file: VaultFile): Result<Uri> = container.call { api ->
        withContext(Dispatchers.IO) { container.vault.pruneShared(maxAgeMillis = 60 * 60_000L) }
        var entry: FileVault.Entry? = null
        api.download(file.id) { input -> entry = container.vault.store(input) }
        val safeName = file.filename.replace(Regex("""[^\w.\- ]"""), "_").ifBlank { "file" }
        SecureFileProvider.share(container.context, entry!!, safeName, safeViewType(file.contentType))
    }

    /**
     * The camera returned a photo: encrypt it into the vault's pending area right away, delete
     * the camera's plain copy, then upload. If the upload fails the encrypted photo is kept and
     * the study shows Retry / Discard, so a photo is never lost to a bad connection.
     */
    fun captureReturned(photo: File) {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { photo.inputStream().use { container.vault.store(it, FileVault.Area.PENDING) } }
            }
            stored.onFailure {
                // Couldn't encrypt (Keystore trouble): upload straight from the camera file instead.
                val part = UploadPart(photo.name, "image/jpeg", photo.length(), exactSize = true) { photo.inputStream() }
                uploadAll(listOf(part), VaultFile.KIND_IMAGE)
                    .onSuccess { messages.trySend(it.toString()) }
                    .onFailure { e -> messages.trySend("Upload failed: ${e.userMessage()}") }
                withContext(Dispatchers.IO) { photo.delete() }
                return@launch
            }
            val entry = stored.getOrThrow()
            withContext(Dispatchers.IO) { photo.delete() }
            val item = PendingUpload(entry.id, studyId, photo.name, System.currentTimeMillis())
            container.pendingUploads.add(item)
            retryPending(item)
        }
    }

    fun retryPending(item: PendingUpload) {
        viewModelScope.launch {
            val entry = container.pendingUploads.entry(item) ?: run { container.pendingUploads.remove(item.id); return@launch }
            val size = withContext(Dispatchers.IO) { runCatching { container.vault.reader(entry).use { it.plaintextLength } } }
                .getOrElse { -1L }
            val part = UploadPart(item.filename, "image/jpeg", size, exactSize = size >= 0) {
                container.vault.reader(entry).inputStream()
            }
            uploadAll(listOf(part), VaultFile.KIND_IMAGE)
                .onSuccess {
                    container.pendingUploads.remove(item.id)
                    messages.trySend(it.toString())
                }
                .onFailure { e ->
                    container.pendingUploads.markFailed(item.id, e.userMessage())
                    messages.trySend("Upload failed. The photo is saved securely on this phone; tap Retry.")
                }
        }
    }

    fun discardPending(item: PendingUpload) = container.pendingUploads.remove(item.id)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StudyScreen(
    container: AppContainer,
    studyId: String,
    onBack: () -> Unit,
    onOpenImage: (fileId: String, name: String, isDicom: Boolean) -> Unit,
    onCompare: () -> Unit = {},
) {
    val vm: StudyViewModel = viewModel(key = "study-$studyId") { StudyViewModel(container, studyId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val upload by vm.upload.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    // Dialog state survives rotation (ids, not objects, so it can be saved).
    var toDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val toDelete = toDeleteId?.let { id -> (state as? Loadable.Loaded<StudyDetail>)?.value?.files?.firstOrNull { it.id == id } }
    var menu by remember { mutableStateOf(false) }
    var confirmDeleteStudy by rememberSaveable { mutableStateOf(false) }
    val allPending by container.pendingUploads.items.collectAsStateWithLifecycle()
    val pending = allPending.filter { it.studyId == studyId }

    LaunchedEffect(session) { if (session == SessionState.Ready) vm.load() }
    LaunchedEffect(vm) { for (m in vm.messages) snackbar.showSnackbar(m) }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val path = pendingCapture ?: return@rememberLauncherForActivityResult
        pendingCapture = null
        val file = File(path)
        if (saved && file.length() > 0) vm.captureReturned(file) else file.delete()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val parts = uris.mapNotNull { runCatching { context.contentResolver.uploadPartFor(it) }.getOrNull() }
        if (parts.size < uris.size) scope.launch { snackbar.showSnackbar("Some files couldn't be read and were skipped") }
        if (parts.isNotEmpty()) vm.startUpload(parts)
    }

    fun launchCamera() {
        val name = "scan_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".jpg"
        val file = File(container.captureDir(), name)
        pendingCapture = file.absolutePath
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        try {
            container.appLock.expectExternalTrip()
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingCapture = null
            scope.launch { snackbar.showSnackbar("No camera app available") }
        }
    }

    fun open(file: VaultFile) {
        // TIFF (and HEIC before Android 9) can't be shown in the app's viewer; hand them to another app.
        val t = file.contentType.lowercase()
        val viewable = file.isImage && !t.contains("tiff") &&
            !((t.contains("heic") || t.contains("heif")) && android.os.Build.VERSION.SDK_INT < 28)
        if (viewable || file.kind == VaultFile.KIND_DICOM) {
            onOpenImage(file.id, file.filename, file.kind == VaultFile.KIND_DICOM)
            return
        }
        scope.launch {
            snackbar.showSnackbar("Opening ${file.filename}…", withDismissAction = false,
                duration = androidx.compose.material3.SnackbarDuration.Short)
        }
        scope.launch {
            vm.downloadForSharing(file).onSuccess { uri ->
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, safeViewType(file.contentType))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    container.appLock.expectExternalTrip()
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
                    val hasImages = (state as? Loadable.Loaded<StudyDetail>)?.value?.files
                        ?.any { it.kind == VaultFile.KIND_DICOM || it.isImage } == true
                    if (hasImages) IconButton(onClick = onCompare) { Icon(Icons.Default.Compare, "Compare with another study") }
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
                        onClick = {
                            container.appLock.expectExternalTrip()
                            picker.launch(arrayOf("image/*", "application/pdf", "application/dicom", "*/*"))
                        },
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
                    items(pending, key = { "pending-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { p ->
                        PendingUploadCard(p, uploading = upload != null, onRetry = { vm.retryPending(p) },
                            onDiscard = { vm.discardPending(p) })
                    }
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
                                onLongClick = { toDeleteId = f.id },
                            ),
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDeleteId = null },
            title = { GuardDialogTouches(); Text("Delete file?") },
            text = { Text("Remove ${f.filename} from this study?") },
            confirmButton = {
                TextButton(onClick = {
                    toDeleteId = null
                    scope.launch {
                        vm.delete(f).onFailure { snackbar.showSnackbar("Delete failed: ${it.userMessage()}") }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDeleteId = null }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteStudy) {
        AlertDialog(
            onDismissRequest = { confirmDeleteStudy = false },
            title = { GuardDialogTouches(); Text("Delete study?") },
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

@Composable
private fun PendingUploadCard(item: PendingUpload, uploading: Boolean, onRetry: () -> Unit, onDiscard: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Photo not uploaded yet", style = MaterialTheme.typography.titleSmall)
            Text(
                (item.lastError?.let { "$it. " } ?: "") + "It's saved encrypted on this phone until it uploads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRetry, enabled = !uploading) { Text("Retry") }
                TextButton(onClick = onDiscard, enabled = !uploading) { Text("Discard") }
            }
        }
    }
}
