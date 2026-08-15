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

    fun guess(bitmap: Bitmap): Guess? {
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
        val aspect = bw / bh // width/height of ink bbox

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

        // Very rough shape rules for Smash's bubbly ranks.
        return when {
            // A: pointed top, wider bottom, hollow-ish mid
            aspect in 0.55f..1.15f && topR < 0.28f && botR > 0.32f && midCR < 0.42f &&
                density in 0.08f..0.32f ->
                Guess(Rank.Ace, 0.62f)

            // 10: two glyphs with a gap, or a clearly wide pair. Must beat K,
            // which also has left+right ink.
            colValleys >= 1 && aspect > 0.95f && density in 0.10f..0.32f ->
                Guess(Rank.Ten, 0.60f)
            aspect > 1.18f && density in 0.10f..0.30f ->
                Guess(Rank.Ten, 0.56f)

            // K: single glyph, no column gap
            aspect in 0.75f..1.15f && density > 0.16f && leftR > 0.22f && rightR > 0.22f &&
                midCR < 0.45f && colValleys == 0 ->
                Guess(Rank.King, 0.58f)

            // Q: round / wide with bottom weight
            aspect in 0.70f..1.20f && density in 0.12f..0.34f && botR > 0.30f &&
                midCR > 0.20f ->
                Guess(Rank.Queen, 0.55f)

            // J: tall and relatively narrow
            aspect < 0.70f && density in 0.08f..0.28f && midCR > 0.30f ->
                Guess(Rank.Jack, 0.55f)

            // 8: stacked weight top+bot, lighter mid often
            aspect in 0.55f..0.95f && abs(topR - botR) < 0.12f && midR < 0.40f &&
                density in 0.12f..0.34f ->
                Guess(Rank.Eight, 0.52f)

            // 2: heavier bottom
            aspect in 0.55f..1.05f && botR > topR + 0.08f && density in 0.10f..0.30f ->
                Guess(Rank.Two, 0.50f)

            // 3: similar to 2 but more mid/right
            aspect in 0.55f..1.00f && rightR > leftR + 0.05f && density in 0.10f..0.30f ->
                Guess(Rank.Three, 0.48f)

            // 4: open top-left-ish, strong vertical
            aspect in 0.55f..1.10f && topR < 0.38f && leftR < 0.40f && density in 0.10f..0.28f ->
                Guess(Rank.Four, 0.48f)

            // 9 / 6 rough
            aspect in 0.55f..0.95f && topR > botR + 0.05f && density in 0.12f..0.32f ->
                Guess(Rank.Nine, 0.47f)

            aspect in 0.55f..0.95f && botR > topR + 0.05f && density in 0.12f..0.32f ->
                Guess(Rank.Six, 0.46f)

            // 7: top-heavy
            aspect in 0.55f..1.10f && topR > 0.38f && botR < 0.30f ->
                Guess(Rank.Seven, 0.47f)

            // 5
            aspect in 0.55f..1.00f && density in 0.10f..0.30f ->
                Guess(Rank.Five, 0.42f)

            else -> null
        }
    }
}
