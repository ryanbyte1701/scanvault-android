package com.scanvault.android.ui.labs

import com.scanvault.android.data.LabPoint
import java.time.LocalDate

/** Where an out-of-range value sits: above the range (+1), below it (-1) or inside it (0). */
fun direction(p: LabPoint, low: Double?, high: Double?): Int {
    val v = p.valueNum ?: return 0
    return when {
        p.flag == "H" || p.flag == "HH" -> 1
        p.flag == "L" || p.flag == "LL" -> -1
        high != null && v > high -> 1
        low != null && v < low -> -1
        else -> 0
    }
}

/** The chart's data in 0..1 coordinates (x left to right, y bottom to top), so drawing is just scaling. */
class ChartGeometry(
    val points: List<Pair<Float, Float>>,
    /** Reference band as (bottom, top) in 0..1, or null when the test has no numeric range. */
    val band: Pair<Float, Float>?,
    val yMin: Double,
    val yMax: Double,
    val low: Double?,
    val high: Double?,
    val plotted: List<LabPoint>,
)

/**
 * Lays out [all] points that have a number and a date: x by date, y padded around the values and
 * the reference range of the latest point that has one, so the band is always fully visible.
 */
fun chartGeometry(all: List<LabPoint>): ChartGeometry? {
    val pts = all.filter { it.valueNum != null && it.date != null && parseDay(it.date) != null }
        .sortedBy { it.date }
    if (pts.isEmpty()) return null
    val ref = pts.lastOrNull { it.refLow != null || it.refHigh != null } ?: pts.last()
    val low = ref.refLow
    val high = ref.refHigh
    val values = pts.map { it.valueNum!! }
    var yMin = listOfNotNull(values.min(), low).min()
    var yMax = listOfNotNull(values.max(), high).max()
    val pad = ((yMax - yMin) * 0.12).takeIf { it > 0 } ?: (Math.abs(yMax) * 0.1).takeIf { it > 0 } ?: 1.0
    yMin -= pad
    yMax += pad
    if (yMin < 0 && values.min() >= 0) yMin = 0.0
    val days = pts.map { parseDay(it.date)!!.toEpochDay().toDouble() }
    var d0 = days.min()
    var d1 = days.max()
    if (d0 == d1) { d0 -= 30; d1 += 30 }
    fun y(v: Double) = ((v - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f)
    val xy = pts.indices.map { i -> ((days[i] - d0) / (d1 - d0)).toFloat() to y(values[i]) }
    val band = if (low != null || high != null) y(low ?: yMin) to y(high ?: yMax) else null
    return ChartGeometry(xy, band, yMin, yMax, low, high, pts)
}

fun parseDay(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

fun refText(p: LabPoint): String = p.refText ?: when {
    p.refLow != null && p.refHigh != null -> "${num(p.refLow)}–${num(p.refHigh)}"
    p.refLow != null -> "≥ ${num(p.refLow)}"
    p.refHigh != null -> "≤ ${num(p.refHigh)}"
    else -> ""
}

fun num(v: Double): String = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e12) v.toLong().toString()
    else v.toBigDecimal().stripTrailingZeros().toPlainString()

val FLAG_LABELS = linkedMapOf(
    "H" to "High", "L" to "Low", "HH" to "Critical high", "LL" to "Critical low", "A" to "Abnormal", "C" to "Critical",
)
