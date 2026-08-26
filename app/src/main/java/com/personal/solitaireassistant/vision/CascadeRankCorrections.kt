package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Rank
import kotlin.math.abs

object CascadeRankCorrections {
    const val MIN_OCR_SIX_CONFIDENCE = 0.52f
    const val SIX_SEVEN_SCORE_MARGIN = 0.06f
    const val SIX_SEVEN_TEMPLATE_FLOOR = 0.48f
    private const val SIX_SEVEN_TEMPLATE_PREFER_DELTA = 0.03f
    private const val SCORE_EPSILON = 0.005f
    const val MIN_OCR_JACK_QUEEN_CONFIDENCE = 0.52f
    /** Full-card J vs Q — Evaluate JH→QH bottoms survive a 0.08 bitmap margin. */
    const val JACK_QUEEN_SCORE_MARGIN = 0.15f
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
     * Queen templates often beat a clear Jack by >0.08 on full bottom cards
     * (golden 20260814_125128 / 20260817_192510). Strong-bitmap early-return
     * then skips OCR; force the Jack/Queen OCR path whenever both score.
     */
    fun shouldChallengeStrongJackQueen(
        bitmapHit: Pair<Rank, Float>?,
        rankScoreMap: Map<Rank, Float> = emptyMap()
    ): Boolean {
        if (bitmapHit == null) return false
        if (bitmapHit.first != Rank.Queen && bitmapHit.first != Rank.Jack) return false
        val jack = rankScoreMap[Rank.Jack] ?: 0f
        val queen = rankScoreMap[Rank.Queen] ?: 0f
        if (jack < JACK_QUEEN_TEMPLATE_FLOOR || queen < JACK_QUEEN_TEMPLATE_FLOOR) {
            return false
        }
        return abs(queen - jack) <= JACK_QUEEN_SCORE_MARGIN + SCORE_EPSILON
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
        if (!shouldChallengeStrongJackQueen(bitmapHit, rankScoreMap)) return null
        val ocr = ocrGuess ?: return null
        if (ocr.rank != Rank.Jack && ocr.rank != Rank.Queen) return null
        if (ocr.confidence < MIN_OCR_JACK_QUEEN_CONFIDENCE) return null
        return ocr.rank to ocr.confidence.coerceAtLeast(0.60f)
    }
}
