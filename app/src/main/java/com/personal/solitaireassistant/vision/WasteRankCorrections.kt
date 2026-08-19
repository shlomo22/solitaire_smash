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

    /**
     * Prefer corner OCR over waste fusion when OCR reads a rank that disagrees with
     * the fused PNG pick on a known confusion pair (3/J, 5/J, K/10, Q/K, Q/10).
     */
    fun ocrRankOverride(
        ocrRank: Rank?,
        legacyCard: Card?,
        tightCard: Card?,
        baseCard: Card?,
        exactRankScores: Map<Rank, Float> = emptyMap()
    ): Rank? {
        if (ocrRank == null) return null

        val legacyRank = legacyCard?.rank
        val tightRank = tightCard?.rank
        val baseRank = baseCard?.rank
        val candidateRanks = setOfNotNull(legacyRank, tightRank, baseRank)
        if (ocrRank in candidateRanks) return ocrRank

        val tightLegacyRanks = setOfNotNull(legacyRank, tightRank)
        if (ocrRank == Rank.Ten &&
            tightLegacyRanks.contains(Rank.King) &&
            tightLegacyRanks.contains(Rank.Ten)
        ) {
            return Rank.Ten
        }
        if (ocrRank == Rank.King &&
            tightLegacyRanks.contains(Rank.King) &&
            tightLegacyRanks.contains(Rank.Ten)
        ) {
            return Rank.King
        }
        if (ocrRank == Rank.Queen &&
            tightLegacyRanks.contains(Rank.Queen) &&
            tightLegacyRanks.contains(Rank.Ten)
        ) {
            return Rank.Queen
        }
        if (ocrRank == Rank.Queen &&
            tightLegacyRanks.contains(Rank.Queen) &&
            tightLegacyRanks.contains(Rank.King)
        ) {
            return Rank.Queen
        }
        if (ocrRank == Rank.Jack &&
            tightLegacyRanks.contains(Rank.Jack) &&
            tightLegacyRanks.contains(Rank.Three)
        ) {
            return Rank.Jack
        }
        if (ocrRank == Rank.Three &&
            tightLegacyRanks.contains(Rank.Jack) &&
            tightLegacyRanks.contains(Rank.Three)
        ) {
            return Rank.Three
        }

        val fusionRank = baseRank ?: legacyRank ?: tightRank
        if (fusionRank != null && isConfusionPair(ocrRank, fusionRank)) {
            // A single (possibly weak-plurality) OCR read on a known-confusable
            // pair should not overturn a fusion rank that template matching
            // already backs decisively, with near-zero support for the OCR
            // read. Confirmed case: OCR split 4-K/3-nine on a waste "10" whose
            // template score (0.54) clearly won and King wasn't even a
            // competitive template candidate.
            val fusionScore = exactRankScores[fusionRank] ?: 0f
            val ocrScore = exactRankScores[ocrRank] ?: 0f
            if (fusionScore >= 0.50f && fusionScore - ocrScore >= 0.30f) {
                return null
            }
            return ocrRank
        }

        return null
    }

    internal fun isConfusionPair(first: Rank, second: Rank): Boolean {
        if (first == second) return false
        return when (setOf(first, second)) {
            setOf(Rank.Ten, Rank.Queen),
            setOf(Rank.King, Rank.Ten),
            setOf(Rank.King, Rank.Queen),
            setOf(Rank.Jack, Rank.Three),
            setOf(Rank.Five, Rank.Jack) -> true
            else -> false
        }
    }
}
