package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.BoardRegion

/**
 * Color heuristics tuned for Solitaire Smash card art:
 * white faces, bright red / near-black ink, teal diamond backs, purple playfield.
 */
object SmashColorAnalyzer {
    /** Row sampling stride for [scanFaceDownBacks]; borders run 5-6px tall. */
    private const val FACE_DOWN_ROW_STEP = 2
    /** Sampled rows of non-teal needed before a new back is counted. */
    private const val MIN_BACK_GAP_ROWS = 2
    /** Teal fraction of a sampled row that counts as "inside a card back". */
    private const val TEAL_ROW_RATIO = 0.60f

    data class RegionStats(
        val whiteRatio: Float,
        val tealRatio: Float,
        val emptyTintRatio: Float,
        val redInkRatio: Float,
        val blackInkRatio: Float,
        val avgLuma: Float
    )

    fun analyze(bitmap: Bitmap, region: BoardRegion): RegionStats {
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val step = ((right - left).coerceAtLeast(8) / 16).coerceAtLeast(1)

        var white = 0
        var teal = 0
        var emptyTint = 0
        var red = 0
        var black = 0
        var lumaSum = 0L
        var total = 0

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                lumaSum += (r + g + b)
                total++
                when {
                    isTealBack(r, g, b) -> teal++
                    isCardWhite(r, g, b) -> white++
                    isEmptySlotTint(r, g, b) -> emptyTint++
                    isRedInk(r, g, b) -> red++
                    isBlackInk(r, g, b) -> black++
                }
                x += step
            }
            y += step
        }
        if (total == 0) {
            return RegionStats(0f, 0f, 0f, 0f, 0f, 0f)
        }
        val t = total.toFloat()
        return RegionStats(
            whiteRatio = white / t,
            tealRatio = teal / t,
            emptyTintRatio = emptyTint / t,
            redInkRatio = red / t,
            blackInkRatio = black / t,
            avgLuma = lumaSum / (t * 3f) / 255f
        )
    }

    /** Result of [scanFaceDownBacks]. */
    data class FaceDownBacks(
        /** Top y of each distinct face-down back, nearest the column top first. */
        val bandTops: List<Float>,
        /** First row below the last face-down back — where the exposed run starts. */
        val boundaryTop: Float
    )

    /**
     * Locates the face-down block of a tableau column by reading teal rows
     * directly, rather than stepping a fixed per-card overlap from the column
     * top.
     *
     * Why this exists: `BoardGeometryProfile.faceDownOverlap` (0.23, i.e.
     * 44.33px on a 192.76px card) is measurably too small — the real repeat
     * spacing between stacked backs is 48.68px, measured over 341 tableau
     * columns across the golden set with 310 of them within 1px of each other.
     * Because the face-up run used to start at
     * `columnRegion.top + faceDownCount * downStep`, that ~4.4px-per-card
     * shortfall accumulated: by the fifth or sixth hidden card the computed
     * boundary still sat inside the last teal back, so the column grew a
     * face-up card that does not exist. That phantom then shifted every
     * `distanceFromBottom` in [TableauCascadeSupport.geometricCascadeCard] by
     * one, flipping the inferred colour of the whole run — golden columns
     * carrying a phantom had 15.1% wrong-colour face-up slots against 1.9%
     * elsewhere.
     *
     * Simply raising the constant was tried before and net-regressed on device
     * (see the comment on `faceDownOverlap`): the count and the step are
     * coupled, so a larger step can drop the count by one and move the
     * boundary *up* into the teal instead of past it. Measuring the boundary
     * removes both the accumulation and the coupling — it cannot drift, and it
     * needs no per-card constant at all.
     *
     * Returns null when the column has no face-down cards, which the caller
     * treats the same way the old arithmetic did (`faceDownCount` 0, run
     * starting at the column top).
     */
    fun scanFaceDownBacks(
        bitmap: Bitmap,
        column: BoardRegion,
        limitBottom: Float
    ): FaceDownBacks? {
        val left = column.left.toInt().coerceIn(0, bitmap.width - 1)
        val right = column.right.toInt().coerceIn(left + 1, bitmap.width)
        val top = column.top.toInt().coerceIn(0, bitmap.height - 1)
        val bottom = limitBottom.toInt().coerceIn(top + 1, bitmap.height)
        // Sample the middle half only: every card carries a rounded white
        // border down both outer edges, face-down or not.
        val sampleLeft = left + (right - left) / 4
        val sampleRight = right - (right - left) / 4
        if (sampleRight - sampleLeft < 4) return null
        val xStep = ((sampleRight - sampleLeft) / 12).coerceAtLeast(1)

        val bandTops = mutableListOf<Float>()
        var lastTealRow = -1
        var gapRows = 0
        var y = top
        while (y < bottom) {
            var teal = 0
            var samples = 0
            var x = sampleLeft
            while (x < sampleRight) {
                val c = bitmap.getPixel(x, y)
                if (isTealBack((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)) {
                    teal++
                }
                samples++
                x += xStep
            }
            if (samples > 0 && teal.toFloat() / samples > TEAL_ROW_RATIO) {
                // A new back only starts after a real border gap; a single
                // noisy row inside one back must not split it in two.
                if (bandTops.isEmpty() || gapRows >= MIN_BACK_GAP_ROWS) {
                    bandTops += y.toFloat()
                }
                gapRows = 0
                lastTealRow = y
            } else {
                gapRows++
            }
            y += FACE_DOWN_ROW_STEP
        }
        if (bandTops.isEmpty() || lastTealRow < 0) return null
        return FaceDownBacks(
            bandTops = bandTops,
            boundaryTop = (lastTealRow + FACE_DOWN_ROW_STEP).toFloat()
        )
    }

    fun isTealBack(r: Int, g: Int, b: Int): Boolean =
        // Tableau face-down backs: vivid cyan/teal.
        r < 100 && g > 140 && b > 150 && g > r + 45 && b > r + 45

    fun isEmptySlotTint(r: Int, g: Int, b: Int): Boolean =
        // Empty foundation/tableau placeholders are light periwinkle.
        r in 100..190 && g in 130..210 && b in 180..245 && b > r + 30 && g > r

    fun isCardWhite(r: Int, g: Int, b: Int): Boolean =
        r > 205 && g > 205 && b > 205

    fun isRedInk(r: Int, g: Int, b: Int): Boolean =
        // Smash uses hot pink/magenta ranks, e.g. RGB(242,0,74).
        r > 150 && r > g + 45 && g < 140 && (r > b + 30 || b < 130)

    fun isBlackInk(r: Int, g: Int, b: Int): Boolean =
        // Smash black ranks are charcoal, e.g. RGB(56,68,82), not pure black.
        r < 95 && g < 105 && b < 120 &&
            (r + g + b) < 280 &&
            !(g > r + 40 && b > r + 40) // exclude teal backs

    /**
     * Generic "dark enough to be ink" fallback used when scoring suit/rank
     * badges, for glyph antialiasing that isn't quite red or charcoal.
     * Genuine ink stays close to neutral grey (e.g. RGB(56,68,82), b-r~26);
     * the card's top border bar is a saturated blue-purple (e.g. RGB(80,102,188),
     * b-r~108) that is dark enough to trip a bare luma check, corrupting
     * corner-badge ink masks whose crop starts right at the card edge. Require
     * near-neutral color so the border is excluded without excluding real ink.
     */
    fun isGenericDarkInk(r: Int, g: Int, b: Int): Boolean {
        val luma = (r * 30 + g * 59 + b * 11) / 100
        return luma < 135 && b < r + 60
    }

    fun looksFaceDown(stats: RegionStats): Boolean =
        // Teal backs include white borders and dark suit-pattern icons.
        stats.tealRatio > 0.20f &&
            stats.whiteRatio < 0.42f &&
            stats.redInkRatio < 0.08f

    fun looksEmpty(stats: RegionStats): Boolean =
        stats.emptyTintRatio > 0.35f ||
            (stats.whiteRatio < 0.16f && stats.tealRatio < 0.12f &&
                stats.blackInkRatio < 0.12f && stats.redInkRatio < 0.05f)

    fun looksFaceUp(stats: RegionStats): Boolean {
        if (looksFaceDown(stats)) return false
        val hasInk = stats.redInkRatio > 0.02f || stats.blackInkRatio > 0.03f
        if (stats.tealRatio > 0.15f) return false
        if (stats.whiteRatio > 0.22f && hasInk) return true
        return stats.whiteRatio > 0.12f && hasInk
    }

    /** Stock pile: vivid teal back (logo may dilute teal a bit). */
    fun looksLikeStockPile(stats: RegionStats): Boolean =
        !looksEmpty(stats) &&
            (stats.tealRatio > 0.18f ||
                (stats.tealRatio > 0.10f && stats.whiteRatio < 0.35f && !looksFaceUp(stats)))
}
