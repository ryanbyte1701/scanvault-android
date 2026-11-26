package com.scanvault.android.data

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File

/** Session-level state shared by every screen. */
sealed interface SessionState {
    data object SignedOut : SessionState
    data object Ready : SessionState
    data object Locked : SessionState
}

/**
 * Simple manual dependency container: holds the secure store, the HTTP client and the
 * current API client, and tracks whether the user is signed in and the vault unlocked.
 */
class AppContainer(private val context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store = SecureStore(context)
    val theme = ThemeStore(context).also { store ->
        store.onChanged = { settings ->
            if (_session.value != SessionState.SignedOut) {
                appScope.launch {
                    runCatching {
                        api.savePrefs(ServerPrefs(theme = settings.mode.wire, darkColor = settings.darkColor))
                    }
                }
            }
        }
    }

    /** Pulls the user's theme from the server so it matches the web app. Best effort. */
    fun syncThemeFromServer() {
        if (_session.value == SessionState.SignedOut) return
        appScope.launch { runCatching { api.prefs() }.onSuccess { theme.applyServer(it) } }
    }

    private val baseHttp: OkHttpClient = ApiClient.defaultHttpClient()

    private val _session = MutableStateFlow<SessionState>(
        if (store.serverUrl != null && store.token != null) SessionState.Ready else SessionState.SignedOut
    )
    val session: StateFlow<SessionState> = _session.asStateFlow()

    @Volatile private var cachedApi: ApiClient? = null

    val api: ApiClient
        get() {
            val url = store.serverUrl ?: error("No server configured")
            cachedApi?.let { if (it.url("").toString() == ApiClient.normalizeBaseUrl(url).toString()) return it }
            return ApiClient(url, { store.token }, baseHttp).also { cachedApi = it }
        }

    /**
     * Image loader that sends the bearer token, but only to the configured ScanVault host,
     * and keeps decoded thumbnails in memory only (no PHI written to a disk cache).
     */
    val imageLoader: ImageLoader by lazy {
        val authed = baseHttp.newBuilder().addInterceptor { chain ->
            val req = chain.request()
            val server = store.serverUrl?.let { runCatching { ApiClient.normalizeBaseUrl(it) }.getOrNull() }
            val token = store.token
            if (server != null && token != null && req.url.host == server.host && req.url.port == server.port) {
                chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
            } else {
                chain.proceed(req)
            }
        }.build()
        ImageLoader.Builder(context)
            .okHttpClient(authed)
            .diskCache(null as DiskCache?)
            .crossfade(true)
            .build()
    }

    /** Builds a throwaway client for a server the user has not saved yet (login screen). */
    fun clientFor(url: String, token: String? = null) = ApiClient(url, { token }, baseHttp)

    fun onSignedIn(url: String, username: String, token: String, unlocked: Boolean) {
        store.serverUrl = ApiClient.normalizeBaseUrl(url).toString()
        store.username = username
        store.token = token
        cachedApi = null
        _session.value = if (unlocked) SessionState.Ready else SessionState.Locked
    }

    fun markLocked() { if (_session.value != SessionState.SignedOut) _session.value = SessionState.Locked }

    fun markUnlocked() { if (_session.value != SessionState.SignedOut) _session.value = SessionState.Ready }

    /** Revokes the token on the server (best effort) and forgets it locally. */
    fun logout() {
        val url = store.serverUrl
        val token = store.token
        if (url != null && token != null) {
            appScope.launch { runCatching { clientFor(url, token).logout() } }
        }
        signOut()
    }

    fun signOut() {
        store.token = null
        cachedApi = null
        imageLoader.memoryCache?.clear()
        clearSharedCache()
        _session.value = SessionState.SignedOut
    }

    /** Runs an API call and translates vault-locked / unauthorized errors into session changes. */
    suspend fun <T> call(block: suspend (ApiClient) -> T): Result<T> = try {
        Result.success(block(api))
    } catch (e: VaultLockedException) {
        markLocked(); Result.failure(e)
    } catch (e: UnauthorizedException) {
        signOut(); Result.failure(e)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Directory for decrypted files handed to other apps via FileProvider. */
    fun sharedDir(): File = File(context.cacheDir, "shared").apply { mkdirs() }

    /** Directory for camera captures before upload. */
    fun captureDir(): File = File(context.cacheDir, "captures").apply { mkdirs() }

    fun clearSharedCache() {
        File(context.cacheDir, "shared").deleteRecursively()
        File(context.cacheDir, "captures").deleteRecursively()
    }
}
