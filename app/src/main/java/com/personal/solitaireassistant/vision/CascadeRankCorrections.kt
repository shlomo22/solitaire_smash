package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Rank

object CascadeRankCorrections {
    const val MIN_OCR_SIX_CONFIDENCE = 0.52f
    const val SIX_SEVEN_SCORE_MARGIN = 0.06f
    const val SIX_SEVEN_TEMPLATE_FLOOR = 0.48f
    private const val SIX_SEVEN_TEMPLATE_PREFER_DELTA = 0.03f
    private const val SCORE_EPSILON = 0.005f
    const val MIN_OCR_JACK_QUEEN_CONFIDENCE = 0.52f
    const val JACK_QUEEN_TEMPLATE_FLOOR = 0.50f

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

    /**
     * Queen templates can crush Jack on full bottom cards with Jack's own
     * template below 0.50 (v1.4.57 required both ≥0.50 — Evaluate JH→QH
     * unchanged). Always OCR-challenge a strong Queen; challenge Jack when
     * Queen is also on the board as a scorer.
     */
    fun shouldChallengeStrongJackQueen(
        bitmapHit: Pair<Rank, Float>?,
        rankScoreMap: Map<Rank, Float> = emptyMap()
    ): Boolean {
        if (bitmapHit == null) return false
        if (bitmapHit.first == Rank.Queen) return true
        if (bitmapHit.first == Rank.Jack) {
            val queen = rankScoreMap[Rank.Queen] ?: 0f
            return queen >= JACK_QUEEN_TEMPLATE_FLOOR
        }
        return false
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

    fun ocrJackQueenOverridesStrongBitmap(
        bitmapHit: Pair<Rank, Float>?,
        ocrGuess: RankCornerOcr.Guess?,
        rankScoreMap: Map<Rank, Float> = emptyMap()
    ): Pair<Rank, Float>? {
        if (bitmapHit == null) return null
        if (bitmapHit.first != Rank.Queen && bitmapHit.first != Rank.Jack) return null
        val ocr = ocrGuess ?: return null
        if (ocr.rank != Rank.Jack && ocr.rank != Rank.Queen) return null
        if (ocr.confidence < MIN_OCR_JACK_QUEEN_CONFIDENCE) return null
        // OCR disagrees with strong bitmap — trust the letter read (J vs Q).
        if (ocr.rank == bitmapHit.first) return null
        return ocr.rank to ocr.confidence.coerceAtLeast(0.60f)
    }
}
