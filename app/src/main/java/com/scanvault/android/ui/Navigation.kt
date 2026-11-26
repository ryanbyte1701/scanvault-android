package com.scanvault.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.scanvault.android.data.AppContainer
import com.scanvault.android.data.SessionState
import com.scanvault.android.ui.components.UnlockDialog
import com.scanvault.android.ui.files.ImageViewerScreen
import com.scanvault.android.ui.login.LoginScreen
import com.scanvault.android.ui.patients.PatientDetailScreen
import com.scanvault.android.ui.patients.PatientsScreen
import com.scanvault.android.ui.settings.SettingsScreen
import com.scanvault.android.ui.studies.StudyScreen

// Navigation decodes path arguments itself, so only encode here.
private fun enc(s: String): String = android.net.Uri.encode(s)

@Composable
fun ScanVaultNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val session by container.session.collectAsStateWithLifecycle()
    val startDestination = remember { if (session == SessionState.SignedOut) "login" else "patients" }

    LaunchedEffect(session) {
        if (session == SessionState.Ready) container.syncThemeFromServer()
        if (session == SessionState.SignedOut && nav.currentDestination?.route != "login") {
            nav.navigate("login") { popUpTo(0) { inclusive = true } }
        }
    }

    if (session == SessionState.Locked) {
        UnlockDialog(container)
    }

    NavHost(
        navController = nav,
        startDestination = startDestination,
    ) {
        composable("login") {
            LoginScreen(container, onOpenSettings = { nav.navigate("settings") }) {
                nav.navigate("patients") { popUpTo("login") { inclusive = true } }
            }
        }
        composable("patients") {
            PatientsScreen(
                container,
                onOpen = { nav.navigate("patient/${enc(it)}") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(container, onBack = { nav.popBackStack() })
        }
        composable(
            "patient/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("id")!!
            PatientDetailScreen(
                container, id,
                onBack = { nav.popBackStack() },
                onOpenStudy = { nav.navigate("study/${enc(it)}") },
            )
        }
        composable(
            "study/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("id")!!
            StudyScreen(
                container, id,
                onBack = { nav.popBackStack() },
                onOpenImage = { fileId, name, isDicom ->
                    nav.navigate("image/${enc(fileId)}/${enc(name)}?dicom=$isDicom")
                },
            )
        }
        composable(
            "image/{id}/{name}?dicom={dicom}",
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("name") { type = NavType.StringType },
                navArgument("dicom") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            ImageViewerScreen(
                container,
                fileId = entry.arguments!!.getString("id")!!,
                name = entry.arguments!!.getString("name")!!,
                isDicom = entry.arguments!!.getBoolean("dicom"),
                onBack = { nav.popBackStack() },
            )
        }
    }
}
