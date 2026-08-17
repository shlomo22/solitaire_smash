package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank

internal object WasteRankCorrections {
    private fun rankScore(exactRankScores: Map<Rank, Float>, rank: Rank): Float =
        exactRankScores[rank] ?: 0f

    /**
     * Waste-only: Ten read on a fanned top card but template/ink evidence favors King.
     */
    fun correctKingTenOnWaste(
        legacyCard: Card?,
        tightCard: Card?,
        exactRankScores: Map<Rank, Float>,
        inkGuess: RankInkHeuristics.Guess?
    ): Rank? {
        val tenRead = legacyCard?.rank == Rank.Ten || tightCard?.rank == Rank.Ten
        if (!tenRead) return null

        val tenScore = rankScore(exactRankScores, Rank.Ten)
        val kingScore = rankScore(exactRankScores, Rank.King)
        if (kingScore <= tenScore + 0.04f || kingScore < 0.75f) return null
        if (inkGuess?.rank == Rank.Ten && inkGuess.confidence >= 0.58f) return null
        if (inkGuess?.rank == Rank.King && inkGuess.confidence >= 0.54f) return Rank.King
        if (kingScore >= tenScore + 0.08f) return Rank.King
        return null
    }

    /**
     * Waste-only: King/Ten read on a fanned top card but template/ink evidence favors Queen.
     */
    fun correctQueenOnWaste(
        legacyCard: Card?,
        tightCard: Card?,
        exactRankScores: Map<Rank, Float>,
        inkGuess: RankInkHeuristics.Guess?
    ): Rank? {
        val ambiguousRead = legacyCard?.rank == Rank.Ten ||
            tightCard?.rank == Rank.Ten ||
            legacyCard?.rank == Rank.King ||
            tightCard?.rank == Rank.King
        if (!ambiguousRead) return null

        val tenScore = rankScore(exactRankScores, Rank.Ten)
        val queenScore = rankScore(exactRankScores, Rank.Queen)
        val kingScore = rankScore(exactRankScores, Rank.King)
        if (tightCard?.rank == Rank.Queen) return Rank.Queen
        if (queenScore < 0.72f || queenScore < maxOf(tenScore, kingScore) - 0.08f) return null
        if (inkGuess?.rank == Rank.Queen && inkGuess.confidence >= 0.54f) return Rank.Queen
        if (queenScore >= maxOf(tenScore, kingScore) + 0.04f) return Rank.Queen
        return null
    }

    /**
     * Smash "5" and "J" center glyphs collide on fanned waste crops. Prefer Five
     * when template or ink shape evidence beats the Jack read.
     */
    fun correctFiveJack(
        legacyCard: Card?,
        tightCard: Card?,
        baseCard: Card?,
        exactRankScores: Map<Rank, Float>,
        inkGuess: RankInkHeuristics.Guess?
    ): Rank? {
        val jackCandidate = legacyCard?.rank == Rank.Jack ||
            tightCard?.rank == Rank.Jack ||
            baseCard?.rank == Rank.Jack
        if (!jackCandidate) return null

        val fiveScore = rankScore(exactRankScores, Rank.Five)
        val jackScore = rankScore(exactRankScores, Rank.Jack)
        if (fiveScore > jackScore && fiveScore >= 0.50f) return Rank.Five
        if (tightCard?.rank == Rank.Five && fiveScore >= jackScore - 0.05f) return Rank.Five
        if (inkGuess?.rank == Rank.Five &&
            inkGuess.confidence >= 0.48f &&
            fiveScore >= jackScore - 0.10f
        ) {
            return Rank.Five
        }
        if (inkGuess?.rank == Rank.Five && inkGuess.confidence >= 0.52f) return Rank.Five
        return null
    }

    /**
     * Smash "3" and "J" center glyphs collide on fanned waste crops. Prefer Three
     * when template or ink shape evidence beats the Jack read.
     */
    fun correctJackThree(
        legacyCard: Card?,
        tightCard: Card?,
        baseCard: Card?,
        exactRankScores: Map<Rank, Float>,
        inkGuess: RankInkHeuristics.Guess?
    ): Rank? {
        val jackCandidate = legacyCard?.rank == Rank.Jack ||
            tightCard?.rank == Rank.Jack ||
            baseCard?.rank == Rank.Jack
        if (!jackCandidate) return null

        val threeScore = exactRankScores[Rank.Three] ?: 0f
        val jackScore = exactRankScores[Rank.Jack] ?: 0f
        if (threeScore > jackScore && threeScore >= 0.50f) return Rank.Three
        if (tightCard?.rank == Rank.Three &&
            threeScore >= jackScore - 0.05f
        ) {
            return Rank.Three
        }
        if (inkGuess?.rank == Rank.Three &&
            inkGuess.confidence >= 0.48f &&
            threeScore >= jackScore - 0.10f
        ) {
            return Rank.Three
        }
        // Center-glyph shape is more reliable than templates on clipped waste fans.
        if (inkGuess?.rank == Rank.Three && inkGuess.confidence >= 0.52f) {
            return Rank.Three
        }
        return null
    }
}
