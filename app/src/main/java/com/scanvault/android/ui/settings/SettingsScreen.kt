package com.scanvault.android.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scanvault.android.BuildConfig
import com.scanvault.android.data.AppContainer
import com.scanvault.android.data.DarkPalette
import com.scanvault.android.data.SessionState
import com.scanvault.android.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.theme.settings.collectAsStateWithLifecycle()
    val session by container.session.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = settings.mode == mode,
                        onClick = { container.theme.update { it.copy(mode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label) }
                }
            }

            Text("Dark theme color", style = MaterialTheme.typography.titleMedium)
            Text(
                "Used whenever the app is dark. Pure black saves battery on OLED screens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DarkPalette.entries.forEach { palette ->
                    PaletteSwatch(
                        palette = palette,
                        selected = settings.darkPalette == palette,
                        onClick = { container.theme.update { it.copy(darkPalette = palette) } },
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    headlineContent = { Text("Wallpaper colors in light theme") },
                    supportingContent = { Text("Match the light theme to your phone's wallpaper") },
                    trailingContent = {
                        Switch(
                            checked = settings.wallpaperColorsInLight,
                            onCheckedChange = { on -> container.theme.update { it.copy(wallpaperColorsInLight = on) } },
                        )
                    },
                )
            }

            HorizontalDivider()
            Text("Account", style = MaterialTheme.typography.titleMedium)
            ListItem(
                headlineContent = { Text(container.store.username ?: "Not signed in") },
                supportingContent = { Text(container.store.serverUrl ?: "No server") },
            )
            if (session != SessionState.SignedOut) {
                OutlinedButton(onClick = container::logout, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
            }
            Text(
                "ScanVault for Android ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A mini preview of the palette: its background with an accent dot and a surface bar. */
@Composable
private fun PaletteSwatch(palette: DarkPalette, selected: Boolean, onClick: () -> Unit) {
    val accent = Color(palette.accent)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .semantics {
                this.selected = selected
                contentDescription = palette.label
            }
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Box(
            Modifier
                .size(72.dp, 56.dp)
                .background(Color(palette.background), RoundedCornerShape(12.dp))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(20.dp).background(accent, CircleShape), contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                }
                Box(Modifier.size(24.dp, 10.dp).background(Color(palette.surface), RoundedCornerShape(4.dp)))
            }
        }
        Text(
            palette.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
