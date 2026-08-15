package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.Suit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Locates the top-left suit pip (to the right of the rank) and classifies
 * black suits by shape. Spades are pointed at the top; clubs have a wider
 * three-lobe silhouette.
 */
object SuitBadgeHeuristics {
    data class Guess(
        val suit: Suit,
        val confidence: Float,
        val margin: Float
    )

    data class LocatedBadge(
        val bitmap: Bitmap,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    fun locateBadge(cardCrop: Bitmap): LocatedBadge? {
        val w = cardCrop.width
        val h = cardCrop.height
        if (w < 20 || h < 20) return null
        // Smash uses the standard corner index: rank top-left, pip top-right.
        // The huge center glyph sits lower and must never be treated as the pip.
        return findPipIn(cardCrop, (w * 0.48f).toInt(), 0, w, (h * 0.26f).toInt().coerceAtLeast(12))
            ?: findPipIn(
                cardCrop,
                (w * 0.38f).toInt(),
                0,
                w,
                (h * 0.22f).toInt().coerceAtLeast(12)
            )
    }

    private fun findPipIn(
        cardCrop: Bitmap,
        searchLeft: Int,
        searchTop: Int,
        searchRight: Int,
        searchBottom: Int
    ): LocatedBadge? {
        val w = cardCrop.width
        val h = cardCrop.height
        if (searchRight - searchLeft < 8 || searchBottom - searchTop < 8) return null
        val colW = searchRight - searchLeft
        val colH = searchBottom - searchTop
        val colInk = IntArray(colW)
        for (x in searchLeft until searchRight) {
            var n = 0
            for (y in searchTop until searchBottom) {
                if (isInk(cardCrop.getPixel(x, y))) n++
            }
            colInk[x - searchLeft] = n
        }
        val threshold = max(1, colH / 12)
        val runs = mutableListOf<IntRange>()
        var runStart = -1
        for (x in 0 until colW) {
            val on = colInk[x] >= threshold
            if (on && runStart < 0) runStart = x
            if (!on && runStart >= 0) {
                if (x - runStart >= 2) runs += runStart until x
                runStart = -1
            }
        }
        if (runStart >= 0 && colW - runStart >= 2) runs += runStart until colW

        val pipRun = when {
            runs.isEmpty() -> return null
            runs.size >= 2 -> runs.last()
            else -> {
                val only = runs[0]
                val width = only.last - only.first + 1
                if (width >= colW * 0.55f) {
                    val split = only.first + (width * 0.52f).toInt()
                    split..only.last
                } else {
                    only
                }
            }
        }

        val left = (searchLeft + pipRun.first - 2).coerceIn(0, w - 8)
        val right = (searchLeft + pipRun.last + 3).coerceIn(left + 8, w)
        var minY = searchBottom
        var maxY = searchTop
        var ink = 0
        for (x in left until right) {
            for (y in searchTop until searchBottom) {
                if (isInk(cardCrop.getPixel(x, y))) {
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                    ink++
                }
            }
        }
        if (ink < 6 || maxY < minY) return null
        if (minY > h * 0.18f) return null
        val padY = max(1, ((maxY - minY + 1) * 0.14f).toInt())
        val top = (minY - padY).coerceIn(0, h - 8)
        val bottom = (maxY + padY + 1).coerceIn(top + 8, h)
        val bw = right - left
        val bh = bottom - top
        if (bw < 8 || bh < 8) return null
        if (bh > h * 0.28f || bw > w * 0.34f) return null
        val aspect = bw.toFloat() / bh
        if (aspect < 0.45f || aspect > 1.90f) return null
        return LocatedBadge(
            bitmap = Bitmap.createBitmap(cardCrop, left, top, bw, bh),
            left = left,
            top = top,
            width = bw,
            height = bh
        )
    }

    fun blackSuitGuess(
        badge: Bitmap,
        minMargin: Float = 0.40f,
        allowWidePeakClubRule: Boolean = true
    ): Guess? {
        val w = badge.width
        val h = badge.height
        if (w < 8 || h < 8) return null

        val grid = 24
        val pixels = IntArray(w * h)
        badge.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = Array(grid) { BooleanArray(grid) }
        var ink = 0
        for (y in 0 until grid) {
            val sy = (((y + 0.5f) * h) / grid).toInt().coerceIn(0, h - 1)
            for (x in 0 until grid) {
                val sx = (((x + 0.5f) * w) / grid).toInt().coerceIn(0, w - 1)
                val on = isInk(pixels[sy * w + sx])
                mask[y][x] = on
                if (on) ink++
            }
        }
        if (ink < 10) return null

        // Measure against the ink bbox so top padding doesn't fake a point
        // and side padding doesn't fake a wide lobe.
        var inkMinX = grid
        var inkMaxX = -1
        var inkMinY = grid
        var inkMaxY = -1
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                if (mask[y][x]) {
                    inkMinX = min(inkMinX, x)
                    inkMaxX = max(inkMaxX, x)
                    inkMinY = min(inkMinY, y)
                    inkMaxY = max(inkMaxY, y)
                }
            }
        }
        if (inkMaxX < inkMinX || inkMaxY < inkMinY) return null
        val pipW = (inkMaxX - inkMinX + 1).toFloat()
        val pipH = (inkMaxY - inkMinY + 1).toFloat()

        fun pipBandWidth(y0: Int, y1: Int): Float {
            var left = grid
            var right = -1
            for (y in y0 until y1.coerceAtMost(grid)) {
                for (x in inkMinX..inkMaxX) {
                    if (mask[y][x]) {
                        left = min(left, x)
                        right = max(right, x)
                    }
                }
            }
            return if (right >= left) (right - left + 1).toFloat() / pipW else 0f
        }

        val tipY1 = inkMinY
        val tipY2 = (inkMinY + max(1, (pipH * 0.12f).toInt())).coerceAtMost(grid)
        val shoulderY2 = (inkMinY + max(2, (pipH * 0.38f).toInt())).coerceAtMost(grid)
        val midY0 = (inkMinY + (pipH * 0.32f).toInt()).coerceIn(0, grid - 1)
        val midY1 = (inkMinY + (pipH * 0.68f).toInt()).coerceIn(midY0 + 1, grid)
        val peakWidth = pipBandWidth(tipY1, tipY2)
        val shoulderWidth = pipBandWidth(tipY2, shoulderY2)
        val midWidth = pipBandWidth(midY0, midY1)

        val midY = (inkMinY + (pipH * 0.45f).toInt()).coerceIn(2, grid - 3)
        val profile = FloatArray(grid)
        for (x in 0 until grid) {
            var sum = 0
            for (y in (midY - 2)..(midY + 2)) {
                if (y in 0 until grid && mask[y][x]) sum++
            }
            profile[x] = sum.toFloat()
        }
        val maxProfile = profile.maxOrNull() ?: 0f
        var valleys = 0
        if (maxProfile >= 1.5f) {
            val hi = maxProfile * 0.50f
            val lo = maxProfile * 0.15f
            var state = 0
            for (x in inkMinX..inkMaxX) {
                val value = profile[x]
                when {
                    value >= hi -> {
                        if (state == 2) valleys++
                        state = 1
                    }
                    value <= lo && state == 1 -> state = 2
                }
            }
        }

        val upperMidY = (inkMinY + (pipH * 0.22f).toInt()).coerceIn(1, grid - 1)
        val upperProfile = FloatArray(grid)
        for (x in 0 until grid) {
            var sum = 0
            for (y in inkMinY until upperMidY) {
                if (y in 0 until grid && mask[y][x]) sum++
            }
            upperProfile[x] = sum.toFloat()
        }
        val upperMax = upperProfile.maxOrNull() ?: 0f
        var upperValleys = 0
        var tipMass = 0f
        var tipMoment = 0f
        if (upperMax >= 1.5f) {
            val hi = upperMax * 0.45f
            val lo = upperMax * 0.12f
            var state = 0
            for (x in inkMinX..inkMaxX) {
                val value = upperProfile[x]
                tipMass += value
                tipMoment += value * x
                when {
                    value >= hi -> {
                        if (state == 2) upperValleys++
                        state = 1
                    }
                    value <= lo && state == 1 -> state = 2
                }
            }
        }
        val pipCx = (inkMinX + inkMaxX) * 0.5f
        val tipOffset = if (tipMass > 0f) abs(tipMoment / tipMass - pipCx) / pipW else 1f

        var spadeScore = 0f
        var clubScore = 0f

        // Relative to the pip: a spade point is a small fraction of body
        // width. Smash spade shoulders are round, so do not treat a mid
        // band as a club lobe.
        when {
            peakWidth <= 0.34f && shoulderWidth >= peakWidth + 0.12f -> spadeScore += 0.52f
            allowWidePeakClubRule && peakWidth >= 0.42f -> clubScore += 0.36f
        }
        if (peakWidth <= 0.30f) spadeScore += 0.18f
        if (!allowWidePeakClubRule &&
            peakWidth >= 0.38f &&
            shoulderWidth < peakWidth + 0.14f &&
            upperValleys == 0
        ) {
            spadeScore += 0.34f
        }
        if (upperValleys >= 1 && peakWidth >= 0.38f) clubScore += 0.34f
        if (upperValleys == 0 && peakWidth <= 0.38f && tipOffset <= 0.18f) {
            spadeScore += 0.22f
        }

        when {
            valleys >= 2 -> clubScore += 0.48f
            valleys == 0 && peakWidth <= 0.40f -> spadeScore += 0.28f
            valleys == 1 && peakWidth <= 0.34f -> spadeScore += 0.10f
        }
        if (midWidth >= 0.78f && peakWidth <= 0.36f) spadeScore += 0.10f

        val best = max(spadeScore, clubScore)
        val margin = abs(spadeScore - clubScore)
        // peakWidth>=0.42 alone yields Clubs@0.36 on most badges — not discriminative.
        if (best < 0.28f || margin < minMargin) return null
        val suit = if (spadeScore > clubScore) Suit.Spades else Suit.Clubs
        val confidence = (0.45f + best * 0.4f + margin * 0.35f).coerceIn(0.45f, 0.95f)
        return Guess(suit, confidence, margin)
    }

    /** True when the badge top reads as a spade tip (narrow shoulders, no lobes). */
    fun blackSuitShoulderSpadeTip(cardCrop: Bitmap): Boolean {
        val located = locateBadge(cardCrop) ?: return false
        return try {
            blackSuitShoulderSpadeTipBadge(located.bitmap)
        } finally {
            if (!located.bitmap.isRecycled) located.bitmap.recycle()
        }
    }

    private fun blackSuitShoulderSpadeTipBadge(badge: Bitmap): Boolean {
        val w = badge.width
        val h = badge.height
        if (w < 8 || h < 8) return false
        val grid = 24
        val pixels = IntArray(w * h)
        badge.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = Array(grid) { BooleanArray(grid) }
        var ink = 0
        for (y in 0 until grid) {
            val sy = (((y + 0.5f) * h) / grid).toInt().coerceIn(0, h - 1)
            for (x in 0 until grid) {
                val sx = (((x + 0.5f) * w) / grid).toInt().coerceIn(0, w - 1)
                val on = isInk(pixels[sy * w + sx])
                mask[y][x] = on
                if (on) ink++
            }
        }
        if (ink < 10) return false
        var inkMinX = grid
        var inkMaxX = -1
        var inkMinY = grid
        var inkMaxY = -1
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                if (mask[y][x]) {
                    inkMinX = min(inkMinX, x)
                    inkMaxX = max(inkMaxX, x)
                    inkMinY = min(inkMinY, y)
                    inkMaxY = max(inkMaxY, y)
                }
            }
        }
        if (inkMaxX < inkMinX || inkMaxY < inkMinY) return false
        val pipW = (inkMaxX - inkMinX + 1).toFloat()
        val pipH = (inkMaxY - inkMinY + 1).toFloat()
        fun pipBandWidth(y0: Int, y1: Int): Float {
            var left = grid
            var right = -1
            for (y in y0 until y1.coerceAtMost(grid)) {
                for (x in inkMinX..inkMaxX) {
                    if (mask[y][x]) {
                        left = min(left, x)
                        right = max(right, x)
                    }
                }
            }
            return if (right >= left) (right - left + 1).toFloat() / pipW else 0f
        }
        val tipY2 = (inkMinY + max(1, (pipH * 0.12f).toInt())).coerceAtMost(grid)
        val shoulderY2 = (inkMinY + max(2, (pipH * 0.38f).toInt())).coerceAtMost(grid)
        val peakWidth = pipBandWidth(inkMinY, tipY2)
        val shoulderWidth = pipBandWidth(tipY2, shoulderY2)
        val upperMidY = (inkMinY + (pipH * 0.22f).toInt()).coerceIn(1, grid - 1)
        val upperProfile = FloatArray(grid)
        for (x in 0 until grid) {
            var sum = 0
            for (y in inkMinY until upperMidY) {
                if (y in 0 until grid && mask[y][x]) sum++
            }
            upperProfile[x] = sum.toFloat()
        }
        val upperMax = upperProfile.maxOrNull() ?: 0f
        var upperValleys = 0
        if (upperMax >= 1.5f) {
            val hi = upperMax * 0.45f
            val lo = upperMax * 0.12f
            var state = 0
            for (x in inkMinX..inkMaxX) {
                when {
                    upperProfile[x] >= hi -> {
                        if (state == 2) upperValleys++
                        state = 1
                    }
                    upperProfile[x] <= lo && state == 1 -> state = 2
                }
            }
        }
        val midY = (inkMinY + (pipH * 0.45f).toInt()).coerceIn(2, grid - 3)
        val profile = FloatArray(grid)
        for (x in 0 until grid) {
            var sum = 0
            for (y in (midY - 2)..(midY + 2)) {
                if (y in 0 until grid && mask[y][x]) sum++
            }
            profile[x] = sum.toFloat()
        }
        val maxProfile = profile.maxOrNull() ?: 0f
        var valleys = 0
        if (maxProfile >= 1.5f) {
            val hi = maxProfile * 0.50f
            val lo = maxProfile * 0.15f
            var state = 0
            for (x in inkMinX..inkMaxX) {
                when {
                    profile[x] >= hi -> {
                        if (state == 2) valleys++
                        state = 1
                    }
                    profile[x] <= lo && state == 1 -> state = 2
                }
            }
        }
        return (peakWidth >= 0.38f &&
            shoulderWidth < peakWidth + 0.14f &&
            upperValleys == 0 &&
            valleys < 2) ||
            (peakWidth <= 0.40f && valleys == 0 && upperValleys == 0)
    }

    /** True when the badge top shows club lobes (twin valleys), not just a wide peak. */
    fun blackSuitClubLobeEvidence(cardCrop: Bitmap): Boolean {
        val located = locateBadge(cardCrop) ?: return false
        return try {
            val (upperValleys, valleys) = blackSuitValleyFeatures(located.bitmap)
            upperValleys >= 1 || valleys >= 2
        } finally {
            if (!located.bitmap.isRecycled) located.bitmap.recycle()
        }
    }

    private fun blackSuitValleyFeatures(badge: Bitmap): Pair<Int, Int> {
        val w = badge.width
        val h = badge.height
        if (w < 8 || h < 8) return 0 to 0
        val grid = 24
        val pixels = IntArray(w * h)
        badge.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = Array(grid) { BooleanArray(grid) }
        var ink = 0
        for (y in 0 until grid) {
            val sy = (((y + 0.5f) * h) / grid).toInt().coerceIn(0, h - 1)
            for (x in 0 until grid) {
                val sx = (((x + 0.5f) * w) / grid).toInt().coerceIn(0, w - 1)
                if (isInk(pixels[sy * w + sx])) {
                    mask[y][x] = true
                    ink++
                }
            }
        }
        if (ink < 10) return 0 to 0
        var inkMinX = grid
        var inkMaxX = -1
        var inkMinY = grid
        var inkMaxY = -1
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                if (mask[y][x]) {
                    inkMinX = min(inkMinX, x)
                    inkMaxX = max(inkMaxX, x)
                    inkMinY = min(inkMinY, y)
                    inkMaxY = max(inkMaxY, y)
                }
            }
        }
        if (inkMaxX < inkMinX || inkMaxY < inkMinY) return 0 to 0
        val pipH = (inkMaxY - inkMinY + 1).toFloat()
        val upperMidY = (inkMinY + (pipH * 0.22f).toInt()).coerceIn(1, grid - 1)
        val upperProfile = FloatArray(grid)
        for (x in 0 until grid) {
            var sum = 0
            for (y in inkMinY until upperMidY) {
                if (y in 0 until grid && mask[y][x]) sum++
            }
            upperProfile[x] = sum.toFloat()
        }
        val upperMax = upperProfile.maxOrNull() ?: 0f
        var upperValleys = 0
        if (upperMax >= 1.5f) {
            val hi = upperMax * 0.45f
            val lo = upperMax * 0.12f
            var state = 0
            for (x in inkMinX..inkMaxX) {
                when {
                    upperProfile[x] >= hi -> {
                        if (state == 2) upperValleys++
                        state = 1
                    }
                    upperProfile[x] <= lo && state == 1 -> state = 2
                }
            }
        }
        val midY = (inkMinY + (pipH * 0.45f).toInt()).coerceIn(2, grid - 3)
        val profile = FloatArray(grid)
        for (x in 0 until grid) {
            var sum = 0
            for (y in (midY - 2)..(midY + 2)) {
                if (y in 0 until grid && mask[y][x]) sum++
            }
            profile[x] = sum.toFloat()
        }
        val maxProfile = profile.maxOrNull() ?: 0f
        var valleys = 0
        if (maxProfile >= 1.5f) {
            val hi = maxProfile * 0.50f
            val lo = maxProfile * 0.15f
            var state = 0
            for (x in inkMinX..inkMaxX) {
                when {
                    profile[x] >= hi -> {
                        if (state == 2) valleys++
                        state = 1
                    }
                    profile[x] <= lo && state == 1 -> state = 2
                }
            }
        }
        return upperValleys to valleys
    }

    /** Wide peak plus wide shoulders — club lobes, not a spade tip. */
    fun blackSuitWideClubTop(cardCrop: Bitmap): Boolean {
        val located = locateBadge(cardCrop) ?: return false
        return try {
            blackSuitWideClubTopBadge(located.bitmap)
        } finally {
            if (!located.bitmap.isRecycled) located.bitmap.recycle()
        }
    }

    private fun blackSuitWideClubTopBadge(badge: Bitmap): Boolean {
        val w = badge.width
        val h = badge.height
        if (w < 8 || h < 8) return false
        val grid = 24
        val pixels = IntArray(w * h)
        badge.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = Array(grid) { BooleanArray(grid) }
        for (y in 0 until grid) {
            val sy = (((y + 0.5f) * h) / grid).toInt().coerceIn(0, h - 1)
            for (x in 0 until grid) {
                val sx = (((x + 0.5f) * w) / grid).toInt().coerceIn(0, w - 1)
                mask[y][x] = isInk(pixels[sy * w + sx])
            }
        }
        var inkMinX = grid
        var inkMaxX = -1
        var inkMinY = grid
        var inkMaxY = -1
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                if (mask[y][x]) {
                    inkMinX = min(inkMinX, x)
                    inkMaxX = max(inkMaxX, x)
                    inkMinY = min(inkMinY, y)
                    inkMaxY = max(inkMaxY, y)
                }
            }
        }
        if (inkMaxX < inkMinX || inkMaxY < inkMinY) return false
        val pipW = (inkMaxX - inkMinX + 1).toFloat()
        val pipH = (inkMaxY - inkMinY + 1).toFloat()
        fun pipBandWidth(y0: Int, y1: Int): Float {
            var left = grid
            var right = -1
            for (y in y0 until y1.coerceAtMost(grid)) {
                for (x in inkMinX..inkMaxX) {
                    if (mask[y][x]) {
                        left = min(left, x)
                        right = max(right, x)
                    }
                }
            }
            return if (right >= left) (right - left + 1).toFloat() / pipW else 0f
        }
        val tipY2 = (inkMinY + max(1, (pipH * 0.12f).toInt())).coerceAtMost(grid)
        val shoulderY2 = (inkMinY + max(2, (pipH * 0.38f).toInt())).coerceAtMost(grid)
        val peakWidth = pipBandWidth(inkMinY, tipY2)
        val shoulderWidth = pipBandWidth(tipY2, shoulderY2)
        return peakWidth >= 0.42f && shoulderWidth >= 0.75f
    }

    /** Wide peak but narrow shoulders — spade shoulder, not club lobes. */
    fun blackSuitNarrowShoulderSpadePeak(cardCrop: Bitmap): Boolean {
        val located = locateBadge(cardCrop) ?: return false
        return try {
            blackSuitNarrowShoulderSpadePeakBadge(located.bitmap)
        } finally {
            if (!located.bitmap.isRecycled) located.bitmap.recycle()
        }
    }

    private fun blackSuitNarrowShoulderSpadePeakBadge(badge: Bitmap): Boolean {
        val w = badge.width
        val h = badge.height
        if (w < 8 || h < 8) return false
        val grid = 24
        val pixels = IntArray(w * h)
        badge.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = Array(grid) { BooleanArray(grid) }
        for (y in 0 until grid) {
            val sy = (((y + 0.5f) * h) / grid).toInt().coerceIn(0, h - 1)
            for (x in 0 until grid) {
                val sx = (((x + 0.5f) * w) / grid).toInt().coerceIn(0, w - 1)
                mask[y][x] = isInk(pixels[sy * w + sx])
            }
        }
        var inkMinX = grid
        var inkMaxX = -1
        var inkMinY = grid
        var inkMaxY = -1
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                if (mask[y][x]) {
                    inkMinX = min(inkMinX, x)
                    inkMaxX = max(inkMaxX, x)
                    inkMinY = min(inkMinY, y)
                    inkMaxY = max(inkMaxY, y)
                }
            }
        }
        if (inkMaxX < inkMinX || inkMaxY < inkMinY) return false
        val pipW = (inkMaxX - inkMinX + 1).toFloat()
        val pipH = (inkMaxY - inkMinY + 1).toFloat()
        fun pipBandWidth(y0: Int, y1: Int): Float {
            var left = grid
            var right = -1
            for (y in y0 until y1.coerceAtMost(grid)) {
                for (x in inkMinX..inkMaxX) {
                    if (mask[y][x]) {
                        left = min(left, x)
                        right = max(right, x)
                    }
                }
            }
            return if (right >= left) (right - left + 1).toFloat() / pipW else 0f
        }
        val tipY2 = (inkMinY + max(1, (pipH * 0.12f).toInt())).coerceAtMost(grid)
        val shoulderY2 = (inkMinY + max(2, (pipH * 0.38f).toInt())).coerceAtMost(grid)
        val peakWidth = pipBandWidth(inkMinY, tipY2)
        val shoulderWidth = pipBandWidth(tipY2, shoulderY2)
        val (upperValleys, valleys) = blackSuitValleyFeatures(badge)
        return peakWidth >= 0.42f &&
            shoulderWidth < 0.75f &&
            upperValleys == 0 &&
            valleys == 0
    }

    fun blackSuitShapeMetrics(cardCrop: Bitmap): String {
        val located = locateBadge(cardCrop) ?: return "no-badge"
        return try {
            blackSuitShapeMetricsBadge(located.bitmap)
        } finally {
            if (!located.bitmap.isRecycled) located.bitmap.recycle()
        }
    }

    private fun blackSuitShapeMetricsBadge(badge: Bitmap): String {
        val w = badge.width
        val h = badge.height
        if (w < 8 || h < 8) return "tiny"
        val grid = 24
        val pixels = IntArray(w * h)
        badge.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = Array(grid) { BooleanArray(grid) }
        for (y in 0 until grid) {
            val sy = (((y + 0.5f) * h) / grid).toInt().coerceIn(0, h - 1)
            for (x in 0 until grid) {
                val sx = (((x + 0.5f) * w) / grid).toInt().coerceIn(0, w - 1)
                mask[y][x] = isInk(pixels[sy * w + sx])
            }
        }
        var inkMinX = grid
        var inkMaxX = -1
        var inkMinY = grid
        var inkMaxY = -1
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                if (mask[y][x]) {
                    inkMinX = min(inkMinX, x)
                    inkMaxX = max(inkMaxX, x)
                    inkMinY = min(inkMinY, y)
                    inkMaxY = max(inkMaxY, y)
                }
            }
        }
        if (inkMaxX < inkMinX || inkMaxY < inkMinY) return "no-ink"
        val pipW = (inkMaxX - inkMinX + 1).toFloat()
        val pipH = (inkMaxY - inkMinY + 1).toFloat()
        fun pipBandWidth(y0: Int, y1: Int): Float {
            var left = grid
            var right = -1
            for (y in y0 until y1.coerceAtMost(grid)) {
                for (x in inkMinX..inkMaxX) {
                    if (mask[y][x]) {
                        left = min(left, x)
                        right = max(right, x)
                    }
                }
            }
            return if (right >= left) (right - left + 1).toFloat() / pipW else 0f
        }
        val tipY2 = (inkMinY + max(1, (pipH * 0.12f).toInt())).coerceAtMost(grid)
        val shoulderY2 = (inkMinY + max(2, (pipH * 0.38f).toInt())).coerceAtMost(grid)
        val peakWidth = pipBandWidth(inkMinY, tipY2)
        val shoulderWidth = pipBandWidth(tipY2, shoulderY2)
        val (upperValleys, valleys) = blackSuitValleyFeatures(badge)
        return "peak=%.2f shoulder=%.2f uV=$upperValleys v=$valleys".format(peakWidth, shoulderWidth)
    }

    fun guessBlackSuit(
        cardCrop: Bitmap,
        minMargin: Float = 0.40f,
        allowWidePeakClubRule: Boolean = true
    ): Guess? {
        val located = locateBadge(cardCrop) ?: return null
        return try {
            blackSuitGuess(located.bitmap, minMargin, allowWidePeakClubRule)
        } finally {
            if (!located.bitmap.isRecycled) located.bitmap.recycle()
        }
    }

    fun redSuitGuess(badge: Bitmap): Guess? {
        val w = badge.width
        val h = badge.height
        if (w < 8 || h < 8) return null

        val grid = 24
        val pixels = IntArray(w * h)
        badge.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = Array(grid) { BooleanArray(grid) }
        var ink = 0
        for (y in 0 until grid) {
            val sy = (((y + 0.5f) * h) / grid).toInt().coerceIn(0, h - 1)
            for (x in 0 until grid) {
                val sx = (((x + 0.5f) * w) / grid).toInt().coerceIn(0, w - 1)
                val on = isInk(pixels[sy * w + sx])
                mask[y][x] = on
                if (on) ink++
            }
        }
        if (ink < 10) return null

        var inkMinX = grid
        var inkMaxX = -1
        var inkMinY = grid
        var inkMaxY = -1
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                if (mask[y][x]) {
                    inkMinX = min(inkMinX, x)
                    inkMaxX = max(inkMaxX, x)
                    inkMinY = min(inkMinY, y)
                    inkMaxY = max(inkMaxY, y)
                }
            }
        }
        if (inkMaxX < inkMinX || inkMaxY < inkMinY) return null
        val pipW = (inkMaxX - inkMinX + 1).toFloat()
        val pipH = (inkMaxY - inkMinY + 1).toFloat()

        fun pipBandWidth(y0: Int, y1: Int): Float {
            var left = grid
            var right = -1
            for (y in y0 until y1.coerceAtMost(grid)) {
                for (x in inkMinX..inkMaxX) {
                    if (mask[y][x]) {
                        left = min(left, x)
                        right = max(right, x)
                    }
                }
            }
            return if (right >= left) (right - left + 1).toFloat() / pipW else 0f
        }

        val topY2 = (inkMinY + max(1, (pipH * 0.18f).toInt())).coerceAtMost(grid)
        val upperY2 = (inkMinY + max(2, (pipH * 0.40f).toInt())).coerceAtMost(grid)
        val midY0 = (inkMinY + (pipH * 0.32f).toInt()).coerceIn(0, grid - 1)
        val midY1 = (inkMinY + (pipH * 0.68f).toInt()).coerceIn(midY0 + 1, grid)
        val bottomY0 = (inkMinY + (pipH * 0.72f).toInt()).coerceIn(0, grid - 1)

        val topWidth = pipBandWidth(inkMinY, topY2)
        val upperWidth = pipBandWidth(inkMinY, upperY2)
        val midWidth = pipBandWidth(midY0, midY1)
        val bottomWidth = pipBandWidth(bottomY0, inkMaxY + 1)

        val topMidY = (inkMinY + (pipH * 0.22f).toInt()).coerceIn(2, grid - 3)
        val topProfile = FloatArray(grid)
        for (x in 0 until grid) {
            var sum = 0
            for (y in inkMinY until topMidY) {
                if (y in 0 until grid && mask[y][x]) sum++
            }
            topProfile[x] = sum.toFloat()
        }
        val topMax = topProfile.maxOrNull() ?: 0f
        var topValleys = 0
        if (topMax >= 1.5f) {
            val hi = topMax * 0.45f
            val lo = topMax * 0.12f
            var state = 0
            for (x in inkMinX..inkMaxX) {
                val value = topProfile[x]
                when {
                    value >= hi -> {
                        if (state == 2) topValleys++
                        state = 1
                    }
                    value <= lo && state == 1 -> state = 2
                }
            }
        }

        var heartScore = 0f
        var diamondScore = 0f

        val pointedTop = topWidth <= 0.46f
        val wideLobes = topWidth >= 0.50f && upperWidth >= 0.70f
        when {
            topValleys >= 1 && wideLobes -> heartScore += 0.46f
            topValleys >= 1 && !pointedTop -> heartScore += 0.28f
            wideLobes && !pointedTop && upperWidth >= 0.78f -> heartScore += 0.10f
        }
        // Diamonds also taper at the bottom. Only treat a pointed base as a
        // heart when the top is a cleft / two lobes, not a clipped diamond tip.
        if (bottomWidth <= midWidth * 0.62f &&
            upperWidth >= midWidth * 0.82f &&
            (topValleys >= 1 || wideLobes)
        ) {
            heartScore += 0.18f
        }

        when {
            midWidth >= topWidth + 0.12f && midWidth >= bottomWidth + 0.08f ->
                diamondScore += 0.52f
            pointedTop && bottomWidth <= 0.48f -> diamondScore += 0.28f
        }
        val aspect = pipW / pipH
        if (aspect in 0.62f..1.22f && midWidth >= 0.50f && topWidth <= 0.48f) {
            diamondScore += 0.16f
        }
        if (pointedTop && topValleys == 0) {
            diamondScore += 0.12f
        }

        val best = max(heartScore, diamondScore)
        val margin = abs(heartScore - diamondScore)
        if (best < 0.26f || margin < 0.08f) return null
        val suit = if (heartScore > diamondScore) Suit.Hearts else Suit.Diamonds
        val confidence = (0.45f + best * 0.4f + margin * 0.35f).coerceIn(0.45f, 0.95f)
        return Guess(suit, confidence, margin)
    }

    fun guessRedSuit(cardCrop: Bitmap): Guess? {
        val located = locateBadge(cardCrop) ?: return null
        return try {
            redSuitGuess(located.bitmap)
        } finally {
            if (!located.bitmap.isRecycled) located.bitmap.recycle()
        }
    }

    /**
     * Keeps only the top [topFraction] of ink-bbox rows in a 48×48 mask.
     * Spade tips vs club lobes differ mainly in this band; the lower stem is shared.
     */
    fun topHalfInkMask(
        mask: LongArray,
        topFraction: Float = 0.45f,
        gridSize: Int = INK_MASK_GRID
    ): LongArray {
        if (mask.size != gridSize) return mask.copyOf()
        var inkMinY = gridSize
        var inkMaxY = -1
        for (y in 0 until gridSize) {
            if (mask[y] != 0L) {
                inkMinY = min(inkMinY, y)
                inkMaxY = max(inkMaxY, y)
            }
        }
        if (inkMaxY < inkMinY) return LongArray(gridSize)
        val pipH = inkMaxY - inkMinY + 1
        val cutoffY = (inkMinY + topFraction * pipH).toInt().coerceIn(inkMinY, gridSize - 1)
        return LongArray(gridSize) { y -> if (y <= cutoffY) mask[y] else 0L }
    }

    private fun isInk(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val luma = (r * 30 + g * 59 + b * 11) / 100
        return SmashColorAnalyzer.isRedInk(r, g, b) ||
            SmashColorAnalyzer.isBlackInk(r, g, b) ||
            luma < 135
    }

    const val INK_MASK_GRID = 48
}
