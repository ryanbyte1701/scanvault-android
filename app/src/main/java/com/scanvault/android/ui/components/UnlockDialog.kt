package com.scanvault.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.scanvault.android.data.AppContainer
import kotlinx.coroutines.launch

/**
 * Shown whenever the server reports its vault is locked (for example after a container restart).
 * The passphrase or recovery key is sent once to the server and never stored on the phone.
 */
@Composable
fun UnlockDialog(container: AppContainer) {
    var useRecoveryKey by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (secret.isBlank() || busy) return
        busy = true; error = null
        scope.launch {
            val result = runCatching {
                if (useRecoveryKey) container.api.unlock(recoveryKey = secret.trim())
                else container.api.unlock(passphrase = secret)
            }
            busy = false
            result.onSuccess { secret = ""; container.markUnlocked() }
                .onFailure { error = it.userMessage() }
        }
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Vault is locked") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The ScanVault server restarted and needs its encryption key before it can show records.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !useRecoveryKey, onClick = { useRecoveryKey = false },
                        label = { Text("Passphrase") })
                    FilterChip(selected = useRecoveryKey, onClick = { useRecoveryKey = true },
                        label = { Text("Recovery key") })
                }
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(if (useRecoveryKey) "Recovery key" else "Vault passphrase") },
                    singleLine = !useRecoveryKey,
                    minLines = if (useRecoveryKey) 3 else 1,
                    visualTransformation = if (useRecoveryKey) androidx.compose.ui.text.input.VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (useRecoveryKey) KeyboardType.Ascii else KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = !busy && secret.isNotBlank()) {
                Text(if (busy) "Unlocking…" else "Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = { container.signOut() }, enabled = !busy) { Text("Sign out") }
        },
    )
}
