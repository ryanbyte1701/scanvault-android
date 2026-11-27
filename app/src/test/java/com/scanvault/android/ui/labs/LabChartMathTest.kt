package com.scanvault.android.ui.labs

import com.scanvault.android.data.LabPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabChartMathTest {
    private fun p(date: String, v: Double?, lo: Double? = 70.0, hi: Double? = 99.0, flag: String? = null) =
        LabPoint(date = date, value = v?.toString() ?: "Negative", valueNum = v, refLow = lo, refHigh = hi, flag = flag)

    @Test fun pointsAreOrderedByDateAndBandIsInside() {
        val g = chartGeometry(listOf(p("2026-06-09", 108.0), p("2025-09-20", 97.0), p("2026-03-14", 104.0)))!!
        assertEquals(listOf("2025-09-20", "2026-03-14", "2026-06-09"), g.plotted.map { it.date })
        assertEquals(0f, g.points.first().first, 0f)
        assertEquals(1f, g.points.last().first, 0f)
        val (b, t) = g.band!!
        assertTrue(b > 0f && t < 1f && b < t)
        assertTrue("108 is above the band", g.points.last().second > t)
    }

    @Test fun bandComesFromTheLatestPointThatHasOne() {
        val g = chartGeometry(listOf(p("2026-01-01", 120.0, 0.0, 99.0), p("2026-02-01", 142.0, null, null, "H")))!!
        assertEquals(99.0, g.high!!, 0.0)
        assertEquals(1, direction(g.plotted.last(), g.low, g.high))
    }

    @Test fun nonNumericResultsAreNotGraphed() {
        assertNull(chartGeometry(listOf(p("2026-01-01", null))))
        val single = chartGeometry(listOf(p("2026-01-01", 5.0, null, null)))
        assertNotNull(single)
        assertNull(single!!.band)
        assertEquals(0.5f, single.points.single().first, 0.001f)
    }

    @Test fun directionUsesFlagThenRange() {
        assertEquals(-1, direction(p("2026-01-01", 50.0, flag = "L"), 70.0, 99.0))
        assertEquals(-1, direction(p("2026-01-01", 50.0), 70.0, 99.0))
        assertEquals(0, direction(p("2026-01-01", 80.0), 70.0, 99.0))
        assertEquals("70–99", refText(p("2026-01-01", 80.0)))
        assertEquals("0.45", num(0.450))
    }
}
