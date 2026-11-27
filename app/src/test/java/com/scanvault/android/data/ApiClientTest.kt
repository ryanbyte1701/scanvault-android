package com.scanvault.android.data

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ApiClientTest {
    private lateinit var server: MockWebServer
    private var token: String? = "tok123"
    private lateinit var api: ApiClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = ApiClient(server.url("/").toString(), { token })
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun loginSendsCredentialsWithoutBearer() = runTest {
        server.enqueue(json("""{"token":"abc","expires_at":"2026-12-26T00:00:00Z"}"""))
        val res = api.login("ryan", "pw")
        assertEquals("abc", res.token)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/auth/login", req.path)
        assertEquals(null, req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"username\":\"ryan\""))
    }

    @Test fun loginSendsAndReturnsDeviceToken() = runTest {
        server.enqueue(json("""{"token":"abc","expires_at":"2026-12-26T00:00:00Z","unlocked":true,"device":"d1.ff"}"""))
        server.enqueue(json("""{"token":"def"}"""))
        assertEquals("d1.ff", api.login("ryan", "pw", device = "d1.old").device)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"device\":\"d1.old\""))
        api.login("ryan", "pw")
        assertTrue("no device field when there is none", !server.takeRequest().body.readUtf8().contains("device"))
    }

    @Test fun patientsParseAndSendBearer() = runTest {
        server.enqueue(json("""[{"id":"p1","name":"Jane Doe","date_of_birth":"1980-02-03","study_count":2,"extra":"ignored"}]"""))
        val list = api.patients()
        assertEquals(1, list.size)
        assertEquals("Jane Doe", list[0].name)
        assertEquals("1980-02-03", list[0].dateOfBirth)
        assertEquals(2, list[0].studyCount)
        assertEquals("Bearer tok123", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun similarStudiesParse() = runTest {
        server.enqueue(json("""[{"id":"s2","patient_id":"p1","title":"MRI left knee","modality":"MR","study_date":"2024-06-10",
            "similarity":7.0,"suggested":true,"match_reasons":["Same type (MRI)","Same body part (knee)"],"days_apart":642,
            "relation":"prior","thumbnail_ids":["f9"],"image_count":8,"file_count":9,"body_part":"KNEE"}]"""))
        val list = api.similarStudies("s 1")
        assertEquals("/api/studies/s%201/similar", server.takeRequest().path)
        val s = list.single()
        assertTrue(s.suggested)
        assertEquals(listOf("Same type (MRI)", "Same body part (knee)"), s.matchReasons)
        assertEquals(642, s.daysApart)
        assertEquals("prior", s.relation)
        assertEquals(listOf("f9"), s.thumbnailIds)
        assertEquals(8, s.imageCount)
    }

    @Test fun filesParseDicomAndThumbnailFlags() = runTest {
        server.enqueue(json("""[{"id":"f1","study_id":"s1","filename":"IM0001.dcm","content_type":"application/dicom",
            "size_bytes":524288,"kind":"dicom","has_thumbnail":true,"dicom":{"modality":"MR","institution":"X"}}]"""))
        val f = api.files("s1").single()
        assertEquals(VaultFile.KIND_DICOM, f.kind)
        assertTrue(f.hasThumbnail)
        assertEquals("/api/studies/s1/files", server.takeRequest().path)
    }

    @Test fun lockedVaultMapsToVaultLockedException() = runTest {
        server.enqueue(json("""{"error":"Vault is locked","code":"locked"}""", 423))
        try {
            api.patients(); fail("expected exception")
        } catch (e: VaultLockedException) {
            assertEquals("Vault is locked", e.message)
        }
    }

    @Test fun unauthorizedMapsToUnauthorizedException() = runTest {
        server.enqueue(json("""{"error":"Invalid token"}""", 401))
        try {
            api.patients(); fail("expected exception")
        } catch (e: UnauthorizedException) {
            assertEquals(401, e.status)
        }
    }

    @Test fun uploadSendsMultipartWithKind() = runTest {
        server.enqueue(json("""[{"id":"f9","study_id":"s1","filename":"report.pdf","content_type":"application/pdf","size_bytes":3,"kind":"report"}]"""))
        val bytes = "pdf".toByteArray()
        val part = UploadPart("report.pdf", "application/pdf", bytes.size.toLong()) { bytes.inputStream() }
        val out = api.upload("s1", listOf(part), "report")
        assertEquals("f9", out.single().id)
        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(body.contains("name=\"kind\""))
        assertTrue(body.contains("name=\"file\"; filename=\"report.pdf\""))
    }

    @Test fun uploadAcceptsSingleObjectResponse() = runTest {
        server.enqueue(json("""{"id":"f1","study_id":"s1","filename":"a.jpg","content_type":"image/jpeg"}"""))
        val part = UploadPart("a.jpg", "image/jpeg", 1) { byteArrayOf(1).inputStream() }
        assertEquals("f1", api.upload("s1", listOf(part), null).single().id)
    }

    @Test fun setupRequiredAndTooLargeGetFriendlyMessages() = runTest {
        server.enqueue(json("""{"error":"Setup required","code":"setup_required"}""", 409))
        server.enqueue(json("""{"error":"Too large"}""", 413))
        try { api.patients(); fail() } catch (e: ApiException) {
            assertEquals("setup_required", e.code); assertTrue(e.message!!.contains("setting up"))
        }
        try { api.patients(); fail() } catch (e: ApiException) {
            assertEquals(413, e.status); assertTrue(e.message!!.contains("upload limit"))
        }
    }

    @Test fun duplicateFlagParses() = runTest {
        server.enqueue(json("""[{"id":"f1","study_id":"s1","patient_id":"p1","filename":"a.jpg","content_type":"image/jpeg","sha256":"ab","duplicate":true}]"""))
        val part = UploadPart("a.jpg", "image/jpeg", 1) { byteArrayOf(1).inputStream() }
        assertTrue(api.upload("s1", listOf(part), null).single().duplicate)
    }

    @Test fun renderSendsWindowAndParsesHeaders() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg")
            .setHeader("X-Window-Center", "-600").setHeader("X-Window-Width", "1500")
            .setHeader("X-Default-Center", "40").setHeader("X-Default-Width", "400")
            .setHeader("X-Min", "-1024").setHeader("X-Max", "3071")
            .setHeader("X-Frames", "120").setHeader("X-Frame", "5")
            .setHeader("X-Rgb", "0").setHeader("X-Modality", "CT")
            .setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
        val r = api.render("f1", -600.0, 1500.0, 5)
        val req = server.takeRequest()
        assertEquals("/api/files/f1/render?wc=-600&ww=1500&frame=5", req.path)
        assertEquals(-600.0, r.center, 0.0)
        assertEquals(400.0, r.defaultWidth!!, 0.0)
        assertEquals(120, r.frames)
        assertEquals("CT", r.modality)
        assertEquals(3, r.bytes.size)
    }

    @Test fun renderWithoutWindowUsesFileDefault() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("X-Window-Center", "40")
            .setHeader("X-Window-Width", "400").setBody("x"))
        api.render("f1")
        assertEquals("/api/files/f1/render", server.takeRequest().path)
    }

    @Test fun prefsRoundTrip() = runTest {
        server.enqueue(json("""{"theme":"dark","dark_color":"#1a1022"}"""))
        server.enqueue(json("""{"theme":"light","dark_color":"#000000"}"""))
        assertEquals("#1a1022", api.prefs().darkColor)
        api.savePrefs(ServerPrefs(theme = "light", darkColor = "#000000"))
        server.takeRequest()
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        val body = put.body.readUtf8()
        assertTrue(body.contains("\"dark_color\":\"#000000\""))
        assertTrue(body.contains("\"theme\":\"light\""))
    }

    @Test fun statusParsesSsoProviders() = runTest {
        server.enqueue(json("""{"setup_complete":true,"unlocked":false,"authenticated":false,"local_login":false,
            "sso":[{"id":"google","name":"Google","preset":"google"},{"id":"authentik","name":"Home SSO"}]}"""))
        val s = api.status()
        assertTrue(!s.localLogin)
        assertEquals(listOf("Google", "Home SSO"), s.sso.map { it.name })
    }

    @Test fun ssoStartUrlCarriesRedirectStateAndChallenge() {
        val url = api.ssoStartUrl("google", "scanvault://sso", "st", "ch").toHttpUrl()
        assertEquals("/api/auth/sso/google/start", url.encodedPath)
        assertEquals("scanvault://sso", url.queryParameter("app_redirect"))
        assertEquals("st", url.queryParameter("app_state"))
        assertEquals("ch", url.queryParameter("code_challenge"))
    }

    @Test fun ssoExchangeSendsTicketAndVerifierWithoutBearer() = runTest {
        server.enqueue(json("""{"token":"t2","expires_at":"2026-12-27T00:00:00Z","unlocked":false}"""))
        val r = api.ssoExchange("tick", "ver")
        assertEquals("t2", r.token)
        assertEquals(false, r.unlocked)
        val req = server.takeRequest()
        assertEquals("/api/auth/sso/exchange", req.path)
        assertEquals(null, req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"ticket\":\"tick\""))
        assertTrue(body.contains("\"code_verifier\":\"ver\""))
    }

    @Test fun ssoErrorsGetFriendlyMessages() = runTest {
        server.enqueue(json("""{"error":"expired","code":"sso_expired"}""", 400))
        try { api.ssoExchange("a", "b"); fail() } catch (e: ApiException) {
            assertEquals("sso_expired", e.code); assertTrue(e.message!!.contains("too long"))
        }
    }

    @Test fun nullFilenameDoesNotBreakTheFileList() = runTest {
        server.enqueue(json("""[{"id":"f1","study_id":"s1","filename":null,"content_type":"image/jpeg"},
            {"id":"f2","study_id":"s1","filename":"ok.jpg"}]"""))
        val files = api.files("s1")
        assertEquals(listOf("file", "ok.jpg"), files.map { it.filename })
    }

    @Test fun fastApiDetailErrorsAreReadable() = runTest {
        server.enqueue(json("""{"detail":"Patient not found"}""", 404))
        server.enqueue(json("""{"detail":[{"loc":["body","file"],"msg":"Field required"}]}""", 422))
        try { api.patient("x"); fail() } catch (e: ApiException) { assertEquals("Patient not found", e.message) }
        try { api.patients(); fail() } catch (e: ApiException) { assertEquals("Field required", e.message) }
    }

    @Test fun oversizedReplyIsRefused() = runTest {
        server.enqueue(json("[" + "1,".repeat(17 * 1024 * 1024) + "1]"))
        try { api.patients(); fail() } catch (e: ApiException) { assertTrue(e.message!!.contains("large")) }
    }

    @Test fun unlockPostsRecoveryKeyOverHttps() = runTest {
        val cert = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val tls = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory(), false); start() }
        val client = okhttp3.OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .addNetworkInterceptor(com.scanvault.android.data.net.CleartextPolicyInterceptor { false })
            .build()
        val httpsApi = ApiClient(tls.url("/").newBuilder().host("localhost").build().toString(), { "tok" }, client)
        tls.enqueue(json("""{"unlocked":true}"""))
        httpsApi.unlock(recoveryKey = "AAAA-BBBB")
        val req = tls.takeRequest()
        assertEquals(null, req.getHeader("X-ScanVault-Sensitive"))
        assertEquals("/api/vault/unlock", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"recovery_key\":\"AAAA-BBBB\""))
        assertTrue(!body.contains("passphrase"))
        tls.shutdown()
    }

    @Test fun unlockIsRefusedOverHttp() = runTest {
        try { api.unlock(passphrase = "p"); fail() } catch (e: com.scanvault.android.data.net.InsecureConnectionException) { }
        assertEquals(0, server.requestCount)
    }

    @Test fun idsArePathEncoded() = runTest {
        server.enqueue(json("""{"id":"a/b","name":"X"}"""))
        api.patient("a/b")
        assertEquals("/api/patients/a%2Fb", server.takeRequest().path)
    }

    @Test fun normalizeBaseUrlAddsSchemeAndSlash() {
        assertEquals("https://nas.local:8443/", ApiClient.normalizeBaseUrl("nas.local:8443").toString())
        assertEquals("http://10.0.0.5/scanvault/", ApiClient.normalizeBaseUrl(" http://10.0.0.5/scanvault ").toString())
    }

    @Test fun subpathDeploymentBuildsUrls() {
        val c = ApiClient("https://host/scanvault", { null })
        assertEquals("https://host/scanvault/api/files/f1/content", c.contentUrl("f1"))
    }

    @Test fun kindDetection() {
        assertEquals(VaultFile.KIND_DICOM, kindFor("IM0001.DCM", "application/octet-stream"))
        assertEquals(VaultFile.KIND_IMAGE, kindFor("x.jpg", "image/jpeg"))
        assertEquals(VaultFile.KIND_REPORT, kindFor("r.pdf", "application/pdf"))
        assertEquals(VaultFile.KIND_OTHER, kindFor("cd.zip", "application/zip"))
    }
}
