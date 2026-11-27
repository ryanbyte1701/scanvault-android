package com.scanvault.android.data

import com.scanvault.android.data.net.CleartextPolicyInterceptor
import com.scanvault.android.data.net.InsecureConnectionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown for any non-2xx response from the server. */
open class ApiException(val status: Int, message: String, val code: String? = null) : IOException(message)

/** HTTP 423: the server's vault is locked and must be unlocked with the passphrase or recovery key. */
class VaultLockedException(message: String) : ApiException(423, message, "locked")

/** HTTP 401: the token is missing, expired or revoked. */
class UnauthorizedException(message: String) : ApiException(401, message)

/** A file to upload whose bytes are opened lazily, so large scans stream without being held in memory. */
class UploadPart(
    val filename: String,
    val contentType: String,
    val size: Long,
    /** Only trust [size] as Content-Length when we know it exactly (our own files); providers can be wrong. */
    val exactSize: Boolean = false,
    val open: () -> InputStream,
)

/**
 * Thin REST client for the ScanVault server. All encryption and backups happen server-side;
 * the app only ever sees decrypted content over the (ideally TLS) connection.
 */
class ApiClient(
    baseUrl: String,
    private val tokenProvider: () -> String?,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    private val base: HttpUrl = normalizeBaseUrl(baseUrl)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun url(path: String): HttpUrl = base.newBuilder().addEncodedPathSegments(path.trimStart('/')).build()

    fun authHeader(): String? = tokenProvider()?.let { "Bearer $it" }

    val isHttps: Boolean get() = base.isHttps

    /** Same client with a long read timeout, for imports the server ingests before it replies. */
    private val slowHttp: OkHttpClient by lazy { http.newBuilder().readTimeout(30, TimeUnit.MINUTES).build() }

    // --- Auth / status -------------------------------------------------------------------

    suspend fun status(): ServerStatus = get("api/status", ServerStatus.serializer())

    suspend fun login(username: String, password: String, device: String? = null): LoginResponse =
        send("POST", "api/auth/login", jsonBody(LoginRequest(username, password, device), LoginRequest.serializer()),
            LoginResponse.serializer(), authenticated = false)

    /** URL to open in a Custom Tab to start SSO with provider [providerId]. */
    fun ssoStartUrl(providerId: String, appRedirect: String, appState: String, codeChallenge: String): String =
        url("api/auth/sso/${seg(providerId)}/start").newBuilder()
            .addQueryParameter("app_redirect", appRedirect)
            .addQueryParameter("app_state", appState)
            .addQueryParameter("code_challenge", codeChallenge)
            .build().toString()

    /** Trades the one-time SSO ticket (plus the PKCE verifier) for a normal bearer token. */
    suspend fun ssoExchange(ticket: String, codeVerifier: String): LoginResponse =
        send("POST", "api/auth/sso/exchange",
            jsonBody(SsoExchangeRequest(ticket, codeVerifier), SsoExchangeRequest.serializer()),
            LoginResponse.serializer(), authenticated = false)

    suspend fun logout() {
        sendNoContent("POST", "api/auth/logout", "{}".toRequestBody(JSON))
    }

    /** Sends the vault passphrase or recovery key. Refused over http: these also decrypt the backups. */
    suspend fun unlock(passphrase: String? = null, recoveryKey: String? = null) {
        if (!base.isHttps) {
            throw InsecureConnectionException("The vault key is never sent over unencrypted http. Use an https address.")
        }
        sendNoContent("POST", "api/vault/unlock",
            jsonBody(UnlockRequest(passphrase, recoveryKey), UnlockRequest.serializer()), sensitive = true)
    }

    // --- Preferences ---------------------------------------------------------------------

    suspend fun prefs(): ServerPrefs = get("api/prefs", ServerPrefs.serializer())

    suspend fun savePrefs(prefs: ServerPrefs): ServerPrefs =
        send("PUT", "api/prefs", jsonBody(prefs, ServerPrefs.serializer()), ServerPrefs.serializer())

    // --- Patients ------------------------------------------------------------------------

    suspend fun patients(): List<Patient> = get("api/patients", ListSerializer(Patient.serializer()))

    suspend fun patient(id: String): Patient = get("api/patients/${seg(id)}", Patient.serializer())

    suspend fun createPatient(input: PatientInput): Patient =
        send("POST", "api/patients", jsonBody(input, PatientInput.serializer()), Patient.serializer())

    suspend fun deletePatient(id: String) = sendNoContent("DELETE", "api/patients/${seg(id)}", null)

    suspend fun importToPatient(patientId: String, parts: List<UploadPart>): ImportResult =
        send("POST", "api/patients/${seg(patientId)}/import", multipart(parts, null), ImportResult.serializer(),
            client = slowHttp)

    // --- Studies -------------------------------------------------------------------------

    suspend fun studies(patientId: String): List<Study> =
        get("api/patients/${seg(patientId)}/studies", ListSerializer(Study.serializer()))

    suspend fun study(id: String): Study = get("api/studies/${seg(id)}", Study.serializer())

    suspend fun createStudy(patientId: String, input: StudyInput): Study =
        send("POST", "api/patients/${seg(patientId)}/studies", jsonBody(input, StudyInput.serializer()),
            Study.serializer())

    suspend fun deleteStudy(id: String) = sendNoContent("DELETE", "api/studies/${seg(id)}", null)

    /** The patient's other studies, most comparable first (needs a server with study comparison). */
    suspend fun similarStudies(id: String): List<SimilarStudy> =
        get("api/studies/${seg(id)}/similar", ListSerializer(SimilarStudy.serializer()))

    // --- Files ---------------------------------------------------------------------------

    suspend fun files(studyId: String): List<VaultFile> =
        get("api/studies/${seg(studyId)}/files", ListSerializer(VaultFile.serializer()))

    /** Uploads one or more files. The server may return the created file list or a single file. */
    suspend fun upload(studyId: String, parts: List<UploadPart>, kind: String?): List<VaultFile> =
        withContext(Dispatchers.IO) {
            execute(request("POST", "api/studies/${seg(studyId)}/files", multipart(parts, kind)), slowHttp) { body ->
                val element = json.parseToJsonElement(body)
                if (element is JsonArray) {
                    json.decodeFromJsonElement(ListSerializer(VaultFile.serializer()), element)
                } else {
                    val obj = element as JsonObject
                    val files = obj["files"]
                    if (files != null) json.decodeFromJsonElement(ListSerializer(VaultFile.serializer()), files)
                    else listOf(json.decodeFromJsonElement(VaultFile.serializer(), obj))
                }
            }
        }

    suspend fun deleteFile(id: String) = sendNoContent("DELETE", "api/files/${seg(id)}", null)

    /**
     * Renders a DICOM file server-side with a true window/level in modality units (HU for CT).
     * Omit [center]/[width] to use the file's own default window.
     */
    suspend fun render(fileId: String, center: Double? = null, width: Double? = null, frame: Int? = null): RenderedImage =
        withContext(Dispatchers.IO) {
            val url = url("api/files/${seg(fileId)}/render").newBuilder().apply {
                center?.let { addQueryParameter("wc", fmt(it)) }
                width?.let { addQueryParameter("ww", fmt(it)) }
                frame?.let { addQueryParameter("frame", it.toString()) }
            }.build()
            val builder = Request.Builder().url(url).header("Accept", "image/jpeg")
            authHeader()?.let { builder.header("Authorization", it) }
            http.newCall(builder.build()).await().use { r ->
                if (!r.isSuccessful) throw errorFor(r)
                fun d(name: String) = r.header(name)?.toDoubleOrNull()?.takeIf { it.isFinite() }
                fun i(name: String) = r.header(name)?.toIntOrNull()
                RenderedImage(
                    bytes = readBounded(r, MAX_RENDER_BYTES).let { buf -> buf.readByteArray() },
                    center = d("X-Window-Center") ?: center ?: 0.0,
                    width = d("X-Window-Width")?.takeIf { it > 0 } ?: width ?: 1.0,
                    defaultCenter = d("X-Default-Center"),
                    defaultWidth = d("X-Default-Width"),
                    min = d("X-Min"),
                    max = d("X-Max"),
                    frames = (i("X-Frames") ?: 1).coerceIn(1, 100_000),
                    frame = (i("X-Frame") ?: frame ?: 0).coerceAtLeast(0),
                    rgb = r.header("X-Rgb").let { it == "1" || it.equals("true", true) },
                    modality = r.header("X-Modality"),
                )
            }
        }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    fun contentUrl(fileId: String): String = url("api/files/${seg(fileId)}/content").toString()

    fun thumbnailUrl(fileId: String): String = url("api/files/${seg(fileId)}/thumbnail").toString()

    /** Streams a file's decrypted content into [sink]. */
    suspend fun download(fileId: String, sink: (InputStream) -> Unit) = withContext(Dispatchers.IO) {
        val response = http.newCall(request("GET", "api/files/${seg(fileId)}/content", null)).await()
        response.use {
            if (!it.isSuccessful) throw errorFor(it)
            sink(it.body!!.byteStream())
        }
    }

    // --- Plumbing ------------------------------------------------------------------------

    private fun seg(id: String): String = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private fun <T> jsonBody(value: T, serializer: KSerializer<T>): RequestBody =
        json.encodeToString(serializer, value).toRequestBody(JSON)

    private fun multipart(parts: List<UploadPart>, kind: String?): RequestBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (kind != null) builder.addFormDataPart("kind", kind)
        parts.forEach { part ->
            builder.addFormDataPart("file", part.filename, StreamingBody(part))
        }
        return builder.build()
    }

    private fun request(
        method: String,
        path: String,
        body: RequestBody?,
        authenticated: Boolean = true,
        sensitive: Boolean = false,
    ): Request {
        val builder = Request.Builder().url(url(path)).header("Accept", "application/json")
        if (sensitive) builder.header(CleartextPolicyInterceptor.SENSITIVE_HEADER, "1")
        if (authenticated) authHeader()?.let { builder.header("Authorization", it) }
        builder.method(method, body ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody() else null)
        return builder.build()
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>): T =
        send("GET", path, null, serializer)

    private suspend fun <T> send(
        method: String,
        path: String,
        body: RequestBody?,
        serializer: KSerializer<T>,
        authenticated: Boolean = true,
        client: OkHttpClient = http,
    ): T = withContext(Dispatchers.IO) {
        execute(request(method, path, body, authenticated), client) { json.decodeFromString(serializer, it) }
    }

    private suspend fun sendNoContent(method: String, path: String, body: RequestBody?, sensitive: Boolean = false) =
        withContext(Dispatchers.IO) {
            execute(request(method, path, body, sensitive = sensitive)) { }
        }

    /** Runs a call that is cancelled (aborting the transfer) if the calling coroutine is cancelled. */
    private suspend fun <T> execute(request: Request, client: OkHttpClient = http, parse: (String) -> T): T {
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw errorFor(response)
            return withContext(Dispatchers.IO) { parse(readBounded(response, MAX_JSON_BYTES).readUtf8()) }
        }
    }

    /** Reads a response body but refuses one larger than [max], so a hostile server can't exhaust memory. */
    private fun readBounded(response: Response, max: Long): okio.Buffer {
        val body = response.body ?: return okio.Buffer()
        if (body.contentLength() > max) throw ApiException(response.code, "The server sent an unexpectedly large reply.")
        val source = body.source()
        if (source.request(max + 1)) throw ApiException(response.code, "The server sent an unexpectedly large reply.")
        return source.buffer
    }

    private fun errorFor(response: Response): ApiException {
        val raw = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val parsed = runCatching { json.decodeFromString(ErrorBody.serializer(), raw) }.getOrNull()
        val message = parsed?.error ?: detailMessage(raw) ?: response.message.ifBlank { "HTTP ${response.code}" }
        return when {
            response.code == 423 || parsed?.code == "locked" -> VaultLockedException(message)
            response.code == 401 -> UnauthorizedException(message)
            parsed?.code == "setup_required" ->
                ApiException(response.code, "Finish setting up ScanVault in its web interface first.", parsed.code)
            parsed?.code == "sso_expired" ->
                ApiException(response.code, "Single sign-on took too long. Please try again.", parsed.code)
            parsed?.code == "sso_pkce" ->
                ApiException(response.code, "Single sign-on couldn't be verified. Please try again.", parsed.code)
            response.code == 413 -> ApiException(413, "File is larger than the server's upload limit.", parsed?.code)
            else -> ApiException(response.code, message, parsed?.code)
        }
    }

    /** FastAPI-style {"detail": "..."} or {"detail": [{"msg": "..."}]} bodies. */
    private fun detailMessage(raw: String): String? = runCatching {
        when (val d = json.parseToJsonElement(raw).jsonObject["detail"]) {
            is JsonPrimitive -> d.content
            is JsonArray -> d.mapNotNull { (it as? JsonObject)?.get("msg")?.let { m -> (m as? JsonPrimitive)?.content } }
                .joinToString("; ").ifBlank { null }
            else -> null
        }
    }.getOrNull()

    private class StreamingBody(private val part: UploadPart) : RequestBody() {
        override fun contentType() = part.contentType.toMediaType()
        override fun contentLength() = if (part.exactSize && part.size >= 0) part.size else -1L
        override fun writeTo(sink: okio.BufferedSink) {
            try {
                part.open().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        sink.write(buffer, 0, read)
                    }
                }
            } catch (e: java.io.IOException) {
                throw e
            } catch (e: Exception) {
                // Another app's document provider can throw anything (SecurityException,
                // IllegalStateException...). On OkHttp's thread that would crash the app.
                throw java.io.IOException("Couldn't read ${part.filename}", e)
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_JSON_BYTES = 32L * 1024 * 1024
        private const val MAX_RENDER_BYTES = 64L * 1024 * 1024

        /** Plain client for tests; the app uses [com.scanvault.android.data.net.HttpClients.create]. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()

        private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            })
        }

        /** Accepts "nas.local:8080", "https://host/" or "https://host/scanvault" and returns a URL ending in "/". */
        fun normalizeBaseUrl(input: String): HttpUrl {
            var value = input.trim()
            require(value.isNotEmpty()) { "Server address is empty" }
            if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
                value = "https://$value"
            }
            if (!value.endsWith("/")) value += "/"
            return value.toHttpUrl()
        }
    }
}
