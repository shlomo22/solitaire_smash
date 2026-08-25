package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit

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
        inkGuess: RankInkHeuristics.Guess?,
        ocrRank: Rank? = null
    ): Rank? {
        val jackCandidate = legacyCard?.rank == Rank.Jack ||
            tightCard?.rank == Rank.Jack ||
            baseCard?.rank == Rank.Jack
        if (!jackCandidate) return null

        val fiveScore = rankScore(exactRankScores, Rank.Five)
        val jackScore = rankScore(exactRankScores, Rank.Jack)
        if (fiveScore > jackScore && fiveScore >= 0.50f) return Rank.Five
        if (tightCard?.rank == Rank.Five && fiveScore >= jackScore - 0.05f) return Rank.Five
        // Corner OCR reading Five is a real read of the actual glyph, not a
        // coarse shape guess — trust it at the same margin as a tight-crop
        // Five candidate, rather than requiring the absolute 0.50 floor above
        // (confirmed case: fiveScore 0.47 vs jackScore 0.40, five reads of
        // OCR "5" with zero competing reads).
        if (ocrRank == Rank.Five && fiveScore >= jackScore - 0.05f) return Rank.Five
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
     * Waste-only: tight/legacy fusion reads Four or Nine but corner OCR read Six.
     * Real device log: probe ocr='6'@0.62 while fused-Four-Clubs won (Six→Four bucket).
     */
    fun correctSixOnWaste(
        legacyCard: Card?,
        tightCard: Card?,
        baseCard: Card?,
        exactRankScores: Map<Rank, Float>,
        ocrRank: Rank?
    ): Rank? {
        val fourCandidate = legacyCard?.rank == Rank.Four ||
            tightCard?.rank == Rank.Four ||
            baseCard?.rank == Rank.Four
        val nineCandidate = legacyCard?.rank == Rank.Nine ||
            tightCard?.rank == Rank.Nine ||
            baseCard?.rank == Rank.Nine
        val sevenCandidate = legacyCard?.rank == Rank.Seven ||
            tightCard?.rank == Rank.Seven ||
            baseCard?.rank == Rank.Seven
        if (!fourCandidate && !nineCandidate && !sevenCandidate) return null

        val sixScore = rankScore(exactRankScores, Rank.Six)
        if (ocrRank == Rank.Six) return Rank.Six

        if (sevenCandidate) {
            val sevenScore = rankScore(exactRankScores, Rank.Seven)
            if (sixScore + 0.05f >= sevenScore && sixScore >= 0.38f) return Rank.Six
        }
        if (fourCandidate) {
            val fourScore = rankScore(exactRankScores, Rank.Four)
            if (sixScore >= fourScore - 0.05f && sixScore >= 0.38f) return Rank.Six
        }
        if (nineCandidate) {
            val nineScore = rankScore(exactRankScores, Rank.Nine)
            if (sixScore + 0.05f >= nineScore && sixScore >= 0.38f) return Rank.Six
        }
        return null
    }

    /**
     * Waste-only: when a legacy or tight crop already read Spades for [rank], trust
     * that over the fused Clubs pick. Do not guess Spades from a narrow C-vs-S
     * template margin alone — the C0.83/S0.77 cluster appears on genuine Clubs too.
     * Deck-uniqueness for ambiguous waste black suits is handled later in
     * [DeckConstraintPass.resolveWasteBlackSuitByDeckOccupancy].
     */
    fun correctBlackSuitOnWaste(
        rank: Rank,
        legacyCard: Card?,
        tightCard: Card?
    ): Suit? {
        val legacySpade = legacyCard?.takeIf { it.rank == rank && it.suit == Suit.Spades }
        val tightSpade = tightCard?.takeIf { it.rank == rank && it.suit == Suit.Spades }
        if (legacySpade != null || tightSpade != null) return Suit.Spades
        return null
    }

    /**
     * Prefer corner OCR over waste fusion when OCR reads a rank that disagrees with
     * the fused PNG pick on a known confusion pair (3/J, 5/J, 6/4, 6/7, 6/9, K/10, Q/K, Q/10).
     */
    fun ocrRankOverride(
        ocrRank: Rank?,
        legacyCard: Card?,
        tightCard: Card?,
        baseCard: Card?
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
        if (ocrRank == Rank.Six &&
            tightLegacyRanks.contains(Rank.Four) &&
            !tightLegacyRanks.contains(Rank.Six)
        ) {
            return Rank.Six
        }
        if (ocrRank == Rank.Six &&
            tightLegacyRanks.contains(Rank.Nine) &&
            !tightLegacyRanks.contains(Rank.Six)
        ) {
            return Rank.Six
        }
        if (ocrRank == Rank.Six &&
            tightLegacyRanks.contains(Rank.Seven) &&
            !tightLegacyRanks.contains(Rank.Six)
        ) {
            return Rank.Six
        }

        val fusionRank = baseRank ?: legacyRank ?: tightRank
        if (fusionRank != null && isConfusionPair(ocrRank, fusionRank)) {
            // King/Ten evidence (template score pattern + OCR plurality) was
            // confirmed genuinely ambiguous on real golden samples: the same
            // signal shape (Ten~0.54 template lead, King absent from top-4,
            // OCR split) occurred once with Ten as truth and once with King
            // as truth. No threshold on this evidence distinguishes them, so
            // trust OCR here as before rather than guess a direction.
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
            setOf(Rank.Five, Rank.Jack),
            setOf(Rank.Six, Rank.Four),
            setOf(Rank.Six, Rank.Nine),
            setOf(Rank.Six, Rank.Seven) -> true
            else -> false
        }
    }
}
