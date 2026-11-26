package com.scanvault.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scanvault.android.ui.ScanVaultNavHost
import com.scanvault.android.ui.theme.ScanVaultTheme
import com.scanvault.android.ui.theme.isAppInDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ScanVaultApp).container
        setContent {
            val settings by container.theme.settings.collectAsStateWithLifecycle()
            val dark = isAppInDarkTheme(settings)
            // Keep status/navigation bar icons readable when the in-app theme differs from the system's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            ScanVaultTheme(settings) {
                ScanVaultNavHost(container)
            }
        }
    }
}
