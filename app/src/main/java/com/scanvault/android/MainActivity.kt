package com.scanvault.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.scanvault.android.ui.ScanVaultNavHost
import com.scanvault.android.ui.theme.ScanVaultTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ScanVaultApp).container
        setContent {
            ScanVaultTheme {
                ScanVaultNavHost(container)
            }
        }
    }
}
