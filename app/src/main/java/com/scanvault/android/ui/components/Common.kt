package com.scanvault.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.DecimalFormat

/** Generic load state for a screen's main content. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}

@Composable
fun <T> LoadableContent(
    state: Loadable<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        Loadable.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is Loadable.Failed -> ErrorState(state.message, onRetry, modifier)
        is Loadable.Loaded -> content(state.value)
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(message, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun Throwable.userMessage(): String = when (this) {
    is java.net.UnknownHostException -> "Can't find the server. Check the address and your network."
    is java.net.ConnectException -> "Can't connect to the server. Is ScanVault running?"
    is java.net.SocketTimeoutException -> "The server took too long to respond."
    is javax.net.ssl.SSLException -> "Secure connection failed: ${message ?: "TLS error"}"
    else -> message ?: this::class.java.simpleName
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return DecimalFormat(if (i == 0) "0" else "0.#").format(value) + " " + units[i]
}
