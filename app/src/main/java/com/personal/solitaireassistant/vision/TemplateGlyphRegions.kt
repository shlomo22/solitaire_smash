package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Normalized crop regions within a face-up card bitmap for isolated
 * rank-corner and suit-badge template matching.
 */
object TemplateGlyphRegions {
    const val RANK_CORNER_WIDTH = 0.30f
    const val RANK_CORNER_HEIGHT = 0.24f
    const val SUIT_BADGE_LEFT = 0.52f
    const val SUIT_BADGE_WIDTH = 0.43f
    const val SUIT_BADGE_HEIGHT = 0.20f
    const val RANK_GLYPH_LEFT = 0.35f
    const val RANK_GLYPH_WIDTH = 0.30f
    const val RANK_GLYPH_TOP = 0.25f
    const val RANK_GLYPH_HEIGHT = 0.45f

    fun cropRankCorner(cardCrop: Bitmap): Bitmap? =
        cropFraction(cardCrop, 0f, 0f, RANK_CORNER_WIDTH, RANK_CORNER_HEIGHT)

    fun cropSuitBadge(cardCrop: Bitmap): Bitmap? =
        cropFraction(
            cardCrop,
            SUIT_BADGE_LEFT,
            0f,
            SUIT_BADGE_WIDTH,
            SUIT_BADGE_HEIGHT
        )

    fun cropRankGlyph(cardCrop: Bitmap): Bitmap? =
        cropFraction(
            cardCrop,
            RANK_GLYPH_LEFT,
            RANK_GLYPH_TOP,
            RANK_GLYPH_WIDTH,
            RANK_GLYPH_HEIGHT
        )

    fun cropFraction(
        source: Bitmap,
        leftFraction: Float,
        topFraction: Float,
        widthFraction: Float,
        heightFraction: Float
    ): Bitmap? {
        if (source.width < 8 || source.height < 8) return null
        val left = (source.width * leftFraction).roundToInt().coerceIn(0, source.width - 8)
        val top = (source.height * topFraction).roundToInt().coerceIn(0, source.height - 8)
        val width = (source.width * widthFraction).roundToInt()
            .coerceIn(8, source.width - left)
        val height = (source.height * heightFraction).roundToInt()
            .coerceIn(8, source.height - top)
        return Bitmap.createBitmap(source, left, top, width, height)
    }
}
