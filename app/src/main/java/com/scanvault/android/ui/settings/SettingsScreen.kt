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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import com.scanvault.android.data.DarkColors
import com.scanvault.android.data.DarkPreset
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
                "Used whenever the app is dark, and shared with the ScanVault web app. Pure black saves battery on OLED screens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DarkPreset.entries.forEach { preset ->
                    PaletteSwatch(
                        label = preset.label,
                        hex = preset.hex,
                        selected = settings.darkColor.equals(preset.hex, ignoreCase = true),
                        onClick = { container.theme.update { it.copy(darkColor = preset.hex) } },
                    )
                }
            }
            CustomColorPicker(
                current = settings.darkColor,
                onPick = { hex -> container.theme.update { it.copy(darkColor = hex) } },
            )

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

/** A mini preview of a dark palette: its background with an accent dot and a surface bar. */
@Composable
private fun PaletteSwatch(label: String, hex: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DarkColors.from(hex)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(84.dp)
            .semantics {
                this.selected = selected
                contentDescription = label
            }
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Box(
            Modifier
                .size(72.dp, 56.dp)
                .background(Color(colors.background), RoundedCornerShape(12.dp))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(20.dp).background(Color(colors.accent), CircleShape), contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(14.dp))
                }
                Box(Modifier.size(24.dp, 10.dp).background(Color(colors.surfaceHigh), RoundedCornerShape(4.dp)))
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Any color: pick a hue and how colorful it is. Lightness is fixed low because the
 * dark palette caps it at 13% anyway; the result is stored as a hex color.
 */
@Composable
private fun CustomColorPicker(current: String, onPick: (String) -> Unit) {
    val initial = remember(current) {
        DarkColors.parseHex(current)?.let { DarkColors.rgbToHsl(it) } ?: Triple(190f, 0.3f, 0.1f)
    }
    var hue by remember(current) { mutableFloatStateOf(initial.first) }
    var sat by remember(current) { mutableFloatStateOf(initial.second.coerceAtMost(0.45f)) }
    val preview = DarkColors.toHex(DarkColors.hslToArgb(hue, sat, 0.12f))
    val isCustom = DarkPreset.entries.none { it.hex.equals(current, ignoreCase = true) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Custom color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (isCustom) current else preview, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier.size(28.dp)
                    .background(Color(DarkColors.from(preview).background), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        }
        // A rainbow strip under the hue slider shows what each position means.
        Box(
            Modifier.fillMaxWidth().height(8.dp).padding(horizontal = 10.dp)
                .background(
                    Brush.horizontalGradient((0..6).map { Color(DarkColors.hslToArgb(it * 60f % 360f, 0.7f, 0.5f)) }),
                    RoundedCornerShape(4.dp),
                ),
        )
        Slider(
            value = hue, onValueChange = { hue = it }, valueRange = 0f..359f,
            onValueChangeFinished = { onPick(preview) },
        )
        Text("Colorfulness", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = sat, onValueChange = { sat = it }, valueRange = 0f..0.45f,
            onValueChangeFinished = { onPick(preview) },
        )
    }
}
