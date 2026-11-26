package com.scanvault.android.data

import kotlinx.coroutines.test.runTest
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

    @Test fun patientsParseAndSendBearer() = runTest {
        server.enqueue(json("""[{"id":"p1","name":"Jane Doe","date_of_birth":"1980-02-03","study_count":2,"extra":"ignored"}]"""))
        val list = api.patients()
        assertEquals(1, list.size)
        assertEquals("Jane Doe", list[0].name)
        assertEquals("1980-02-03", list[0].dateOfBirth)
        assertEquals(2, list[0].studyCount)
        assertEquals("Bearer tok123", server.takeRequest().getHeader("Authorization"))
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

    @Test fun unlockPostsRecoveryKey() = runTest {
        server.enqueue(json("""{"unlocked":true}"""))
        api.unlock(recoveryKey = "AAAA-BBBB")
        val req = server.takeRequest()
        assertEquals("/api/vault/unlock", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"recovery_key\":\"AAAA-BBBB\""))
        assertTrue(!body.contains("passphrase"))
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
