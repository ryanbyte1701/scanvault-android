package com.scanvault.android.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LabsApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = ApiClient(server.url("/").toString(), { "tok" })
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun analytesParseLatestAndSparkline() = runTest {
        server.enqueue(json("""[{"key":"glucose","name":"Glucose","loinc":"2345-7","panel":"Metabolic","unit":"mg/dL","count":2,
            "latest":{"date":"2026-06-09","value":"108","value_num":108.0,"unit":"mg/dL","ref_low":65,"ref_high":99,"flag":"H","lab_name":"Sonora Quest Laboratories"},
            "spark":[["2026-03-14",104.0],["2026-06-09",108.0]]}]"""))
        val a = api.labAnalytes("p1").single()
        assertEquals("H", a.latest.flag)
        assertEquals(99.0, a.latest.refHigh!!, 0.0)
        assertEquals(listOf(104.0, 108.0), a.sparkValues)
        assertEquals("/api/patients/p1/labs/analytes", server.takeRequest().path)
    }

    @Test fun uploadSendsOneFileAndReturnsDraft() = runTest {
        server.enqueue(json("""{"id":"r1","patient_id":"p1","status":"draft","lab":"labcorp","collected":"2026-03-14",
            "rows":[{"name":"Glucose","value":"104","analyte":"glucose","unit":"mg/dL","ref_low":70,"ref_high":99,"flag":"H","prev_value":"97","prev_date":"2025-09-20","confidence":"high","page":1}],
            "warnings":[],"result_count":1}"""))
        val part = UploadPart("labs.pdf", "application/pdf", 4, exactSize = true) { "%PDF".byteInputStream() }
        val r = api.uploadLabReport("p1", part)
        assertTrue(r.isDraft)
        assertEquals("97", r.rows.single().prevValue)
        val req = server.takeRequest()
        assertEquals("/api/patients/p1/labs/upload", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("filename=\"labs.pdf\""))
    }

    @Test fun saveSendsRowsAndOmitsDefaults() = runTest {
        server.enqueue(json("""{"id":"r1","patient_id":"p1","status":"saved"}"""))
        api.updateLabReport("r1", LabReportUpdate(collected = "2026-03-14", rows = listOf(
            LabRow(name = "Glucose", value = "105", analyte = "glucose", refLow = 70.0, refHigh = 99.0),
            LabRow(name = "BUN", value = "15", include = false),
        ), save = true))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/labs/reports/r1", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"save\":true"))
        assertTrue(body.contains("\"ref_low\":70.0"))
        assertTrue(body.contains("\"include\":false"))
        assertFalse("unset fields are left out", body.contains("\"flag\""))
    }
}
