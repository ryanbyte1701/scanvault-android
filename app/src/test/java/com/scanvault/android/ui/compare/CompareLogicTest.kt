package com.scanvault.android.ui.compare

import com.scanvault.android.data.VaultFile
import com.scanvault.android.ui.files.WindowLevel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompareLogicTest {
    private fun dcm(id: String, uid: String?, desc: String?) = VaultFile(
        id = id, studyId = "s", kind = VaultFile.KIND_DICOM, contentType = "application/dicom",
        dicom = buildJsonObject {
            if (uid != null) put("series_uid", uid) else put("series_uid", JsonNull)
            if (desc != null) put("series_description", desc)
        },
    )

    @Test fun groupsBySeriesAndKeepsPhotosTogether() {
        val files = listOf(
            VaultFile(id = "r", studyId = "s", kind = VaultFile.KIND_REPORT, contentType = "application/pdf"),
            dcm("a1", "1.1", "SAG PD FS"), dcm("a2", "1.1", "SAG PD FS"),
            dcm("b1", "1.2", "AX T2"),
            VaultFile(id = "p1", studyId = "s", kind = VaultFile.KIND_IMAGE, contentType = "image/jpeg"),
            dcm("c1", null, "AX T2"),
        )
        val g = seriesGroups(files)
        assertEquals(listOf("SAG PD FS", "AX T2 (2)", "Photos and scans", "AX T2 (4)"), g.map { it.label })
        assertEquals(listOf("a1", "a2"), g[0].files.map { it.id })
        assertEquals(listOf("p1"), g[2].files.map { it.id })
    }

    @Test fun picksSeriesWithClosestDescription() {
        val g = listOf(SeriesGroup("1", "AX T2", emptyList()), SeriesGroup("2", "SAG PD FS", emptyList()))
        assertEquals(1, bestSeries(g, "SAG PD FAT SAT"))
        assertEquals(0, bestSeries(g, null))
    }

    @Test fun ctWindowIsCopiedOthersFollowRelatively() {
        val lung = WindowLevel(-600.0, 1500.0)
        assertEquals(lung, linkedWindow(lung, WindowLevel(40.0, 400.0), WindowLevel(50.0, 350.0), bothCt = true))
        // MR: leader doubled its width and raised level by a quarter of its default width
        val next = linkedWindow(WindowLevel(1500.0, 2000.0), WindowLevel(1000.0, 1000.0), WindowLevel(300.0, 600.0), bothCt = false)
        assertEquals(1200.0, next.width, 1e-9)
        assertEquals(600.0, next.center, 1e-9)
    }

    @Test fun slicesFollowByRelativePositionPlusOffset() {
        assertEquals(0f, sliceFraction(0, 1))
        assertEquals(0.5f, sliceFraction(5, 11))
        assertEquals(10, linkedSlice(0.5f, 0f, 21))
        assertEquals(12, linkedSlice(0.5f, 0.1f, 21))
        assertEquals(20, linkedSlice(0.95f, 0.2f, 21)) // clamped to the last slice
        assertEquals(0, linkedSlice(0.7f, 0f, 1))
    }

    @Test fun gapReadsNaturally() {
        assertEquals("1.8 years earlier", gapText(645, "prior"))
        assertEquals("2 years earlier", gapText(731, "prior"))
        assertEquals("6 months later", gapText(183, "later"))
        assertEquals("1 day earlier", gapText(1, "prior"))
        assertEquals("Same day", gapText(0, "same day"))
        assertNull(gapText(null, "prior"))
    }
}
