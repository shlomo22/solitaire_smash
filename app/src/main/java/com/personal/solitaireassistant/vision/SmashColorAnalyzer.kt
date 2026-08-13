package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.BoardRegion

/**
 * Color heuristics tuned for Solitaire Smash card art:
 * white faces, bright red / near-black ink, teal diamond backs, purple playfield.
 */
object SmashColorAnalyzer {
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

    /** Overlay hint arrow — vivid lime green, not teal backs or card art. */
    fun isHintArrowGreen(r: Int, g: Int, b: Int): Boolean =
        g > 175 && r in 20..150 && b < 110 && g > r + 55 && g > b + 70

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
