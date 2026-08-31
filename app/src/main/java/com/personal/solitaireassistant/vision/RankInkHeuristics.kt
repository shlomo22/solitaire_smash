package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.Rank
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight rank guess from the large center glyph on Solitaire Smash cards.
 * Works without OpenCV — used as a fallback when template scores are weak.
 */
object RankInkHeuristics {
    data class Guess(val rank: Rank, val confidence: Float)

    private data class ShapeMetrics(
        val density: Float,
        val aspect: Float,
        val topR: Float,
        val midR: Float,
        val botR: Float,
        val leftR: Float,
        val midCR: Float,
        val rightR: Float,
        val colValleys: Int
    )

    fun guess(bitmap: Bitmap): Guess? {
        val m = shapeMetrics(bitmap) ?: return null
        // Very rough shape rules for Smash's bubbly ranks.
        return when {
            // A: pointed top, wider bottom, hollow-ish mid
            m.aspect in 0.55f..1.15f && m.topR < 0.28f && m.botR > 0.32f && m.midCR < 0.42f &&
                m.density in 0.08f..0.32f ->
                Guess(Rank.Ace, 0.62f)

            // 10: two glyphs with a gap, or a clearly wide pair. Must beat K,
            // which also has left+right ink.
            m.colValleys >= 1 && m.aspect > 0.95f && m.density in 0.10f..0.32f ->
                Guess(Rank.Ten, 0.60f)
            m.aspect > 1.18f && m.density in 0.10f..0.30f ->
                Guess(Rank.Ten, 0.56f)

            // K: single glyph, no column gap
            m.aspect in 0.75f..1.15f && m.density > 0.16f && m.leftR > 0.22f && m.rightR > 0.22f &&
                m.midCR < 0.45f && m.colValleys == 0 ->
                Guess(Rank.King, 0.58f)

            // Q: round / wide with bottom weight
            m.aspect in 0.70f..1.20f && m.density in 0.12f..0.34f && m.botR > 0.30f &&
                m.midCR > 0.20f ->
                Guess(Rank.Queen, 0.55f)

            // J: tall and relatively narrow. Keep dens ≤ 0.28 in the general
            // guess path — raising it to 0.40 (v1.4.108) made Queens/Tens
            // score as Jack and broke waste via correctFiveJack (Evaluate:
            // QD→JD, 10H→5H). Waste Four→Jack recovery uses [matchesTallJack]
            // with the wider dens ceiling instead.
            m.aspect < 0.70f && m.density in 0.08f..0.28f && m.midCR > 0.30f ->
                Guess(Rank.Jack, 0.55f)

            // 8: stacked weight top+bot, lighter mid often
            m.aspect in 0.55f..0.95f && abs(m.topR - m.botR) < 0.12f && m.midR < 0.40f &&
                m.density in 0.12f..0.34f ->
                Guess(Rank.Eight, 0.52f)

            // 2: heavier bottom
            m.aspect in 0.55f..1.05f && m.botR > m.topR + 0.08f && m.density in 0.10f..0.30f ->
                Guess(Rank.Two, 0.50f)

            // 3: similar to 2 but more mid/right
            m.aspect in 0.55f..1.00f && m.rightR > m.leftR + 0.05f && m.density in 0.10f..0.30f ->
                Guess(Rank.Three, 0.48f)

            // 4: open top-left-ish, strong vertical
            m.aspect in 0.55f..1.10f && m.topR < 0.38f && m.leftR < 0.40f && m.density in 0.10f..0.28f ->
                Guess(Rank.Four, 0.48f)

            // 9 / 6 rough
            m.aspect in 0.55f..0.95f && m.topR > m.botR + 0.05f && m.density in 0.12f..0.32f ->
                Guess(Rank.Nine, 0.47f)

            m.aspect in 0.55f..0.95f && m.botR > m.topR + 0.05f && m.density in 0.12f..0.32f ->
                Guess(Rank.Six, 0.46f)

            // 7: top-heavy
            m.aspect in 0.55f..1.10f && m.topR > 0.38f && m.botR < 0.30f ->
                Guess(Rank.Seven, 0.47f)

            // 5
            m.aspect in 0.55f..1.00f && m.density in 0.10f..0.30f ->
                Guess(Rank.Five, 0.42f)

            else -> null
        }
    }

    /**
     * Waste Four→Jack recovery only. Full waste-card crops of real Jacks sit
     * at dens ~0.34-0.35 (corner J + center J + pip); the general [guess]
     * Jack dens ceiling stays 0.28 so Queens/Tens are not re-labeled Jack.
     * Golden scan: aspect &lt; 0.70 + dens ≤ 0.40 + midCR &gt; 0.30 hits 14/14
     * waste Jacks and 0/17 waste Fours (Fours have aspect ≥ 0.70).
     */
    fun matchesTallJack(bitmap: Bitmap): Boolean {
        val m = shapeMetrics(bitmap) ?: return false
        return m.aspect < 0.70f &&
            m.density in 0.08f..0.40f &&
            m.midCR > 0.30f
    }

    private fun shapeMetrics(bitmap: Bitmap): ShapeMetrics? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 24 || h < 24) return null

        // Center glyph ROI used by Smash's chunky ranks.
        val left = (w * 0.18f).toInt()
        val top = (h * 0.28f).toInt()
        val right = (w * 0.82f).toInt()
        val bottom = (h * 0.78f).toInt()
        val rw = (right - left).coerceAtLeast(8)
        val rh = (bottom - top).coerceAtLeast(8)

        val step = max(1, min(rw, rh) / 48)
        var ink = 0
        var total = 0
        var minX = rw
        var maxX = 0
        var minY = rh
        var maxY = 0
        val cols = IntArray((rw + step - 1) / step)
        val rows = IntArray((rh + step - 1) / step)

        var yy = top
        var rowIdx = 0
        while (yy < bottom) {
            var xx = left
            var colIdx = 0
            while (xx < right) {
                val c = bitmap.getPixel(xx, yy)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                total++
                val isInk = SmashColorAnalyzer.isRedInk(r, g, b) ||
                    SmashColorAnalyzer.isBlackInk(r, g, b)
                if (isInk) {
                    ink++
                    val lx = xx - left
                    val ly = yy - top
                    minX = min(minX, lx)
                    maxX = max(maxX, lx)
                    minY = min(minY, ly)
                    maxY = max(maxY, ly)
                    if (colIdx in cols.indices) cols[colIdx]++
                    if (rowIdx in rows.indices) rows[rowIdx]++
                }
                xx += step
                colIdx++
            }
            yy += step
            rowIdx++
        }
        if (ink < 8 || total == 0) return null

        val density = ink.toFloat() / total
        if (density < 0.04f || density > 0.55f) return null

        val bw = (maxX - minX).coerceAtLeast(1).toFloat()
        val bh = (maxY - minY).coerceAtLeast(1).toFloat()
        val aspect = bw / bh

        val topBand = rows.take((rows.size * 0.33f).toInt().coerceAtLeast(1)).sum()
        val midBand = rows.drop((rows.size * 0.33f).toInt())
            .take((rows.size * 0.34f).toInt().coerceAtLeast(1)).sum()
        val botBand = rows.takeLast((rows.size * 0.33f).toInt().coerceAtLeast(1)).sum()
        val bandSum = (topBand + midBand + botBand).coerceAtLeast(1).toFloat()
        val topR = topBand / bandSum
        val midR = midBand / bandSum
        val botR = botBand / bandSum

        val leftBand = cols.take((cols.size * 0.33f).toInt().coerceAtLeast(1)).sum()
        val midC = cols.drop((cols.size * 0.33f).toInt())
            .take((cols.size * 0.34f).toInt().coerceAtLeast(1)).sum()
        val rightBand = cols.takeLast((cols.size * 0.33f).toInt().coerceAtLeast(1)).sum()
        val colSum = (leftBand + midC + rightBand).coerceAtLeast(1).toFloat()
        val leftR = leftBand / colSum
        val midCR = midC / colSum
        val rightR = rightBand / colSum

        val maxCol = cols.maxOrNull()?.toFloat() ?: 0f
        var colValleys = 0
        if (maxCol >= 1.5f) {
            val hi = maxCol * 0.45f
            val lo = maxCol * 0.15f
            var state = 0
            for (value in cols) {
                when {
                    value >= hi -> {
                        if (state == 2) colValleys++
                        state = 1
                    }
                    value <= lo && state == 1 -> state = 2
                }
            }
        }

        return ShapeMetrics(
            density = density,
            aspect = aspect,
            topR = topR,
            midR = midR,
            botR = botR,
            leftR = leftR,
            midCR = midCR,
            rightR = rightR,
            colValleys = colValleys
        )
    }
}
