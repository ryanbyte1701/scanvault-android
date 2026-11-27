package com.scanvault.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.scanvault.android.ui.components.LockScreen
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
import com.scanvault.android.ui.compare.ComparePickScreen
import com.scanvault.android.ui.compare.CompareScreen
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

    // Single sign-on comes back from the browser; finish it wherever the user is.
    val ssoCallback by container.ssoCallbacks.collectAsStateWithLifecycle()
    LaunchedEffect(ssoCallback) {
        val result = ssoCallback ?: return@LaunchedEffect
        container.ssoCallbacks.value = null
        container.completeSso(result)
    }

    LaunchedEffect(session) {
        if (session != SessionState.SignedOut && nav.currentDestination?.route == "login") {
            nav.navigate("patients") { popUpTo("login") { inclusive = true } }
        }
        if (session == SessionState.Ready) container.syncThemeFromServer()
        if (session == SessionState.SignedOut && nav.currentDestination?.route != "login") {
            nav.navigate("login") { popUpTo(0) { inclusive = true } }
        }
    }

    val appLocked by container.appLock.locked.collectAsStateWithLifecycle()
    val showLock = appLocked && session != SessionState.SignedOut

    if (session == SessionState.Locked && !showLock) {
        UnlockDialog(container)
    }

    // While locked, the screens are not composed at all, so no dialog they opened (such as
    // "Delete patient?") stays usable above the lock, and nothing can take keyboard or
    // accessibility focus. Their saved state and view models are kept for after the unlock.
    val saveable = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize()) {
    if (!showLock) saveable.SaveableStateProvider("screens") {
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
                onCompare = { nav.navigate("compare/${enc(id)}") },
            )
        }
        composable(
            "compare/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("id")!!
            ComparePickScreen(
                container, id,
                onBack = { nav.popBackStack() },
                onPick = { other -> nav.navigate("compare/${enc(id)}/${enc(other)}") },
            )
        }
        composable(
            "compare/{a}/{b}",
            arguments = listOf(
                navArgument("a") { type = NavType.StringType },
                navArgument("b") { type = NavType.StringType },
            ),
        ) { entry ->
            CompareScreen(
                container,
                firstId = entry.arguments!!.getString("a")!!,
                secondId = entry.arguments!!.getString("b")!!,
                onBack = { nav.popBackStack() },
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
    if (showLock) LockScreen(container.appLock)
    }
}
