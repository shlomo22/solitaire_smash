package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Rank

object CascadeRankCorrections {
    const val MIN_OCR_SIX_CONFIDENCE = 0.52f
    const val SIX_SEVEN_SCORE_MARGIN = 0.06f
    const val SIX_SEVEN_TEMPLATE_FLOOR = 0.48f
    private const val SIX_SEVEN_TEMPLATE_PREFER_DELTA = 0.03f
    private const val SCORE_EPSILON = 0.005f

    fun shouldChallengeStrongSeven(
        trimmedToVisibleStrip: Boolean,
        bitmapHit: Pair<Rank, Float>?,
        rankScoreMap: Map<Rank, Float> = emptyMap()
    ): Boolean {
        if (!trimmedToVisibleStrip || bitmapHit == null || bitmapHit.first != Rank.Seven) {
            return false
        }
        if (bitmapHit.second >= 0.68f) return true
        val six = rankScoreMap[Rank.Six] ?: 0f
        val seven = rankScoreMap[Rank.Seven] ?: bitmapHit.second
        return seven >= SIX_SEVEN_TEMPLATE_FLOOR &&
            seven - six <= SIX_SEVEN_SCORE_MARGIN + SCORE_EPSILON
    }

    fun ocrSixOverridesStrongSeven(
        bitmapHit: Pair<Rank, Float>?,
        ocrGuess: RankCornerOcr.Guess?,
        rankScoreMap: Map<Rank, Float> = emptyMap()
    ): Pair<Rank, Float>? {
        if (bitmapHit == null || bitmapHit.first != Rank.Seven) return null
        val six = rankScoreMap[Rank.Six] ?: 0f
        val seven = rankScoreMap[Rank.Seven] ?: bitmapHit.second
        val closeCall = seven >= SIX_SEVEN_TEMPLATE_FLOOR &&
            seven - six <= SIX_SEVEN_SCORE_MARGIN
        if (bitmapHit.second < 0.68f && !closeCall) return null
        if (ocrGuess?.rank == Rank.Six && ocrGuess.confidence >= MIN_OCR_SIX_CONFIDENCE) {
            return Rank.Six to ocrGuess.confidence.coerceAtLeast(0.60f)
        }
        if (bitmapHit.second < 0.68f &&
            closeCall &&
            seven - six <= SIX_SEVEN_TEMPLATE_PREFER_DELTA + SCORE_EPSILON &&
            six >= 0.50f
        ) {
            return Rank.Six to six.coerceAtLeast(0.52f)
        }
        return null
    }
}
