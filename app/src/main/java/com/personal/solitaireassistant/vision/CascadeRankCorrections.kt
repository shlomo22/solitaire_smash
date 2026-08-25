package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Rank

object CascadeRankCorrections {
    const val MIN_OCR_SIX_CONFIDENCE = 0.55f

    fun shouldChallengeStrongSeven(
        trimmedToVisibleStrip: Boolean,
        bitmapHit: Pair<Rank, Float>?
    ): Boolean =
        trimmedToVisibleStrip &&
            bitmapHit != null &&
            bitmapHit.first == Rank.Seven &&
            bitmapHit.second >= 0.68f

    fun ocrSixOverridesStrongSeven(
        bitmapHit: Pair<Rank, Float>?,
        ocrGuess: RankCornerOcr.Guess?
    ): Pair<Rank, Float>? {
        if (bitmapHit == null || bitmapHit.first != Rank.Seven || bitmapHit.second < 0.68f) {
            return null
        }
        if (ocrGuess?.rank != Rank.Six || ocrGuess.confidence < MIN_OCR_SIX_CONFIDENCE) {
            return null
        }
        return Rank.Six to ocrGuess.confidence.coerceAtLeast(0.60f)
    }
}
