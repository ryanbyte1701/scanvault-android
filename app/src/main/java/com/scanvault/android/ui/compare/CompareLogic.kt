package com.scanvault.android.ui.compare

import com.scanvault.android.data.VaultFile
import com.scanvault.android.ui.files.WindowLevel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/** One series (or the loose photos) of a study: the images a pane steps through. */
data class SeriesGroup(val key: String, val label: String, val files: List<VaultFile>)

private fun VaultFile.tag(name: String): String? =
    (dicom?.get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun VaultFile.seriesKey(): String =
    if (kind == VaultFile.KIND_DICOM) tag("series_uid") ?: tag("series_description") ?: "series" else "photos"

/** Images in a study grouped by DICOM series, in the server's order; photos go together in one group. */
fun seriesGroups(files: List<VaultFile>): List<SeriesGroup> {
    val images = files.filter { it.kind == VaultFile.KIND_DICOM || (it.isImage && it.kind != VaultFile.KIND_REPORT) }
    val byKey = LinkedHashMap<String, MutableList<VaultFile>>()
    for (f in images) byKey.getOrPut(f.seriesKey()) { mutableListOf() }.add(f)
    val groups = byKey.map { (k, list) ->
        val label = if (k == "photos") "Photos and scans" else list.first().tag("series_description") ?: "Series"
        SeriesGroup(k, label, list)
    }
    // Two series with the same description get a number so the picker can tell them apart.
    return groups.mapIndexed { i, g ->
        if (groups.count { it.label == g.label } > 1) g.copy(label = "${g.label} (${i + 1})") else g
    }
}

private fun words(s: String?): Set<String> =
    Regex("[a-z0-9]+").findAll((s ?: "").lowercase()).map { it.value }.toSet()

/** The series whose description shares the most words with [label], else the first. */
fun bestSeries(groups: List<SeriesGroup>, label: String?): Int {
    val w = words(label)
    var best = 0
    var top = -1.0
    groups.forEachIndexed { i, g ->
        val v = words(g.label)
        val union = (w + v).size
        val score = if (union == 0) 0.0 else (w intersect v).size.toDouble() / union
        if (score > top) { top = score; best = i }
    }
    return best
}

/**
 * The window to show on the follower when the leader's window changes. CT is in HU on both sides,
 * so it is copied. Other modalities have no common scale, so the change is applied relative to
 * each image's own default window.
 */
fun linkedWindow(
    leader: WindowLevel,
    leaderDefault: WindowLevel,
    followerDefault: WindowLevel,
    bothCt: Boolean,
): WindowLevel {
    if (bothCt) return leader
    val ratio = followerDefault.width / leaderDefault.width.coerceAtLeast(WindowLevel.MIN_WIDTH)
    return WindowLevel(
        center = followerDefault.center + (leader.center - leaderDefault.center) * ratio,
        width = (followerDefault.width * leader.width / leaderDefault.width.coerceAtLeast(WindowLevel.MIN_WIDTH))
            .coerceAtLeast(WindowLevel.MIN_WIDTH),
    )
}

/** Position 0..1 of slice [index] in a stack of [count]. */
fun sliceFraction(index: Int, count: Int): Float = if (count > 1) index.toFloat() / (count - 1) else 0f

/**
 * The slice a linked follower should show: the leader's relative position plus the offset the
 * person set while unlinked, so stacks with different slice counts still line up.
 */
fun linkedSlice(leaderFraction: Float, offset: Float, followerCount: Int): Int {
    if (followerCount <= 1) return 0
    val t = (leaderFraction + offset).coerceIn(0f, 1f)
    return (t * (followerCount - 1)).roundToInt()
}

/** "1.8 years earlier", "3 months later", "Same day". */
fun gapText(daysApart: Int?, relation: String?): String? {
    if (daysApart == null || relation == null) return null
    if (relation == "same day") return "Same day"
    val d = abs(daysApart)
    val amount = when {
        d < 45 -> "$d day${if (d == 1) "" else "s"}"
        d < 548 -> "${(d / 30.4).roundToInt()} months"
        else -> {
            val y = (d / 365.25 * 10).roundToInt() / 10.0
            (if (y % 1.0 == 0.0) "${y.toInt()}" else "$y") + " years"
        }
    }
    return "$amount ${if (relation == "prior") "earlier" else "later"}"
}
