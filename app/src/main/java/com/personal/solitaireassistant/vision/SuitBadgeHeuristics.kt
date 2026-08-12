package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.Suit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Locates the upper-right suit badge and classifies black suits by shape.
 * Spades are pointed at the top; clubs have a wider three-lobe silhouette.
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

        val searchLeft = (w * 0.48f).toInt()
        val searchTop = 0
        val searchRight = (w * 0.98f).toInt().coerceAtMost(w)
        val searchBottom = (h * 0.38f).toInt().coerceAtMost(h)
        val sw = (searchRight - searchLeft).coerceAtLeast(8)
        val sh = (searchBottom - searchTop).coerceAtLeast(8)
        val step = max(1, min(sw, sh) / 36)

        var minX = sw
        var maxX = 0
        var minY = sh
        var maxY = 0
        var ink = 0
        var yy = searchTop
        while (yy < searchBottom) {
            var xx = searchLeft
            while (xx < searchRight) {
                if (isInk(cardCrop.getPixel(xx, yy))) {
                    val lx = xx - searchLeft
                    val ly = yy - searchTop
                    minX = min(minX, lx)
                    maxX = max(maxX, lx)
                    minY = min(minY, ly)
                    maxY = max(maxY, ly)
                    ink++
                }
                xx += step
            }
            yy += step
        }
        if (ink < 4) return null

        val padX = max(1, ((maxX - minX + 1) * 0.18f).toInt())
        val padY = max(1, ((maxY - minY + 1) * 0.18f).toInt())
        val left = (searchLeft + minX - padX).coerceIn(0, w - 8)
        val top = (searchTop + minY - padY).coerceIn(0, h - 8)
        val right = (searchLeft + maxX + padX + 1).coerceIn(left + 8, w)
        val bottom = (searchTop + maxY + padY + 1).coerceIn(top + 8, h)
        val bw = right - left
        val bh = bottom - top
        if (bw < 8 || bh < 8) return null
        return LocatedBadge(
            bitmap = Bitmap.createBitmap(cardCrop, left, top, bw, bh),
            left = left,
            top = top,
            width = bw,
            height = bh
        )
    }

    fun blackSuitGuess(badge: Bitmap): Guess? {
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

        fun bandWidth(y0: Int, y1: Int): Float {
            var left = grid
            var right = -1
            for (y in y0 until y1.coerceAtMost(grid)) {
                for (x in 0 until grid) {
                    if (mask[y][x]) {
                        left = min(left, x)
                        right = max(right, x)
                    }
                }
            }
            return if (right >= left) (right - left + 1).toFloat() / grid else 0f
        }

        fun bandInk(y0: Int, y1: Int): Int {
            var count = 0
            for (y in y0 until y1.coerceAtMost(grid)) {
                for (x in 0 until grid) if (mask[y][x]) count++
            }
            return count
        }

        val tipWidth = bandWidth(0, (grid * 0.22f).toInt().coerceAtLeast(2))
        val midWidth = bandWidth((grid * 0.30f).toInt(), (grid * 0.62f).toInt())
        val topInk = bandInk(0, (grid * 0.28f).toInt().coerceAtLeast(3))
        val midInk = bandInk((grid * 0.28f).toInt(), (grid * 0.70f).toInt())
        val botInk = bandInk((grid * 0.70f).toInt(), grid)

        // Horizontal lobe peaks near mid-height: clubs often show 2–3, spades 1–2.
        val midY = (grid * 0.42f).toInt().coerceIn(2, grid - 3)
        val profile = FloatArray(grid)
        for (x in 0 until grid) {
            var sum = 0
            for (y in (midY - 2)..(midY + 2)) {
                if (y in 0 until grid && mask[y][x]) sum++
            }
            profile[x] = sum.toFloat()
        }
        var peaks = 0
        for (x in 1 until grid - 1) {
            if (profile[x] >= 1.5f &&
                profile[x] >= profile[x - 1] &&
                profile[x] >= profile[x + 1]
            ) {
                peaks++
            }
        }

        // Tip centeredness: spades concentrate ink near the center tip.
        var tipMassX = 0f
        var tipMass = 0
        for (y in 0 until (grid * 0.24f).toInt().coerceAtLeast(2)) {
            for (x in 0 until grid) {
                if (mask[y][x]) {
                    tipMassX += x
                    tipMass++
                }
            }
        }
        val tipCenter = if (tipMass > 0) abs((tipMassX / tipMass) - (grid - 1) * 0.5f) / grid else 1f

        var spadeScore = 0f
        var clubScore = 0f

        // Pointy top vs rounded/wide top lobe.
        if (tipWidth in 0.08f..0.42f && midWidth > tipWidth + 0.12f) spadeScore += 0.34f
        if (tipWidth >= 0.38f) clubScore += 0.30f
        if (tipWidth <= 0.30f && tipCenter < 0.16f) spadeScore += 0.22f
        if (tipWidth >= 0.34f && tipCenter < 0.22f) clubScore += 0.12f

        // Multi-lobe mid silhouette favors clubs.
        when {
            peaks >= 3 -> clubScore += 0.28f
            peaks == 2 -> clubScore += 0.10f
            peaks <= 1 -> spadeScore += 0.16f
        }

        // Relative ink mass: clubs push more ink into upper lobes.
        val upper = (topInk + midInk).coerceAtLeast(1).toFloat()
        val topShare = topInk / upper
        if (topShare < 0.28f && midWidth > 0.45f) spadeScore += 0.12f
        if (topShare >= 0.30f && tipWidth > 0.32f) clubScore += 0.14f
        if (botInk > 0 && botInk < ink * 0.28f) {
            // both have stems; slight preference already covered
        }

        val best = max(spadeScore, clubScore)
        val margin = abs(spadeScore - clubScore)
        if (best < 0.28f) return null
        val suit = if (spadeScore >= clubScore) Suit.Spades else Suit.Clubs
        val confidence = (0.45f + best * 0.4f + margin * 0.35f).coerceIn(0.45f, 0.95f)
        return Guess(suit, confidence, margin)
    }

    fun guessBlackSuit(cardCrop: Bitmap): Guess? {
        val located = locateBadge(cardCrop) ?: return null
        return try {
            blackSuitGuess(located.bitmap)
        } finally {
            if (!located.bitmap.isRecycled) located.bitmap.recycle()
        }
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
}
