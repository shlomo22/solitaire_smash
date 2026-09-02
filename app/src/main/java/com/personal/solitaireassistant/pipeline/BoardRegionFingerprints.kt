package com.personal.solitaireassistant.pipeline

import android.graphics.Bitmap
import com.personal.solitaireassistant.vision.BoardGeometryProfile

/**
 * Per-region board fingerprint used to skip [com.personal.solitaireassistant.vision.GameStateDetector.detect]
 * on a static frame.
 *
 * v1.4.94 tried a single near-match over the whole ~3640-sample grid
 * ([FINGERPRINT_NOISE_TOLERANCE] = 8) and missed real waste-card swaps:
 * the ink glyph moved fewer quantized samples than capture noise elsewhere
 * consumed of that global budget, so detect() reused stale state for ~80s.
 * Reverted in v1.4.95.
 *
 * This version gives each Smash pile its own sample array and its own
 * tiny tolerance. A waste swap has to hide inside the waste cell (dense
 * 20×12 over the profile waste rect), not under a board-wide allowance.
 * Any region over its budget forces a full detect(). Exact-match still
 * wins inside a region; the tolerance only absorbs 1–2 bucket flips.
 */
internal object BoardRegionFingerprints {
    data class RegionSpec(
        val name: String,
        val leftFrac: Float,
        val topFrac: Float,
        val rightFrac: Float,
        val bottomFrac: Float,
        val cols: Int,
        val rows: Int,
        val tolerance: Int
    )

    data class Snapshot(val samples: Array<IntArray>) {
        override fun equals(other: Any?): Boolean =
            other is Snapshot && samples.size == other.samples.size &&
                samples.indices.all { samples[it].contentEquals(other.samples[it]) }

        override fun hashCode(): Int = samples.fold(0) { acc, a -> acc * 31 + a.contentHashCode() }
    }

    data class CompareResult(
        val changed: List<String>,
        val diffs: List<Pair<String, Int>>
    ) {
        val unchanged: Boolean get() = changed.isEmpty()
        fun note(): String =
            if (unchanged) {
                "skip"
            } else {
                "changed:" + diffs.filter { it.second > 0 }.joinToString(",") { "${it.first}=${it.second}" }
            }
    }

    /**
     * Tight on waste/stock/foundations: a playable-card swap is a localized
     * ink change and must always trip. Tableau columns are larger and pick
     * up more capture jitter, so they get one extra sample of slack.
     */
    const val WASTE_TOLERANCE = 1
    const val STOCK_TOLERANCE = 1
    const val FOUNDATION_TOLERANCE = 1
    const val TABLEAU_TOLERANCE = 2

    val SPECS: List<RegionSpec> = buildList {
        val p = BoardGeometryProfile()
        add(
            RegionSpec(
                name = "waste",
                leftFrac = p.waste.left,
                topFrac = p.waste.top,
                rightFrac = p.waste.right,
                bottomFrac = p.waste.bottom,
                cols = 20,
                rows = 12,
                tolerance = WASTE_TOLERANCE
            )
        )
        add(
            RegionSpec(
                name = "stock",
                leftFrac = p.stock.left,
                topFrac = p.stock.top,
                rightFrac = p.stock.right,
                bottomFrac = p.stock.bottom,
                cols = 12,
                rows = 10,
                tolerance = STOCK_TOLERANCE
            )
        )
        p.foundations.forEachIndexed { i, rect ->
            add(
                RegionSpec(
                    name = "f$i",
                    leftFrac = rect.left,
                    topFrac = rect.top,
                    rightFrac = rect.right,
                    bottomFrac = rect.bottom,
                    cols = 8,
                    rows = 8,
                    tolerance = FOUNDATION_TOLERANCE
                )
            )
        }
        val colW = (p.tableauRight - p.tableauLeft) / 7f
        for (i in 0 until 7) {
            add(
                RegionSpec(
                    name = "t$i",
                    leftFrac = p.tableauLeft + colW * i,
                    topFrac = p.tableauTop,
                    rightFrac = p.tableauLeft + colW * (i + 1),
                    bottomFrac = 0.68f,
                    cols = 8,
                    rows = 16,
                    tolerance = TABLEAU_TOLERANCE
                )
            )
        }
    }

    fun sample(bitmap: Bitmap): Snapshot {
        val w = bitmap.width
        val h = bitmap.height
        val regions = Array(SPECS.size) { i ->
            val spec = SPECS[i]
            sampleRegion(
                bitmap = bitmap,
                left = (spec.leftFrac * w).toInt().coerceIn(0, w - 1),
                top = (spec.topFrac * h).toInt().coerceIn(0, h - 1),
                right = (spec.rightFrac * w).toInt().coerceIn(1, w),
                bottom = (spec.bottomFrac * h).toInt().coerceIn(1, h),
                cols = spec.cols,
                rows = spec.rows
            )
        }
        return Snapshot(regions)
    }

    fun compare(previous: Snapshot?, current: Snapshot): CompareResult {
        if (previous == null || previous.samples.size != current.samples.size) {
            return CompareResult(changed = SPECS.map { it.name }, diffs = emptyList())
        }
        val changed = mutableListOf<String>()
        val diffs = mutableListOf<Pair<String, Int>>()
        for (i in SPECS.indices) {
            val spec = SPECS[i]
            val n = countDiffs(previous.samples[i], current.samples[i])
            diffs += spec.name to n
            if (n > spec.tolerance) changed += spec.name
        }
        return CompareResult(changed = changed, diffs = diffs)
    }

    internal fun countDiffs(a: IntArray, b: IntArray): Int {
        val n = minOf(a.size, b.size)
        var d = 0
        for (i in 0 until n) if (a[i] != b[i]) d++
        d += kotlin.math.abs(a.size - b.size)
        return d
    }

    internal fun quantize(color: Int): Int =
        (((color shr 20) and 0xF) shl 8) or
            (((color shr 12) and 0xF) shl 4) or
            ((color shr 4) and 0xF)

    private fun sampleRegion(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cols: Int,
        rows: Int
    ): IntArray {
        if (bottom <= top || right <= left) return IntArray(0)
        val stepX = ((right - left) / cols).coerceAtLeast(1)
        val stepY = ((bottom - top) / rows).coerceAtLeast(1)
        val out = IntArray(((bottom - top + stepY - 1) / stepY) * ((right - left + stepX - 1) / stepX))
        var i = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                if (i < out.size) out[i++] = quantize(bitmap.getPixel(x, y))
                x += stepX
            }
            y += stepY
        }
        return if (i == out.size) out else out.copyOf(i)
    }
}
