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
        val eightScore = rankScore(exactRankScores, Rank.Eight)
        // Smash 8 and 6 share stacked loops. v1.4.44's Six-over-Seven path
        // was stealing real waste Eights (golden 20260825_131411 8♥, 132126 8♦).
        if (ocrRank == Rank.Eight) return null
        if (eightScore >= 0.45f && eightScore >= sixScore) return null
        if (ocrRank == Rank.Six) {
            // OCR "6" on a real Nine is common. Only steal a fused Nine when
            // Six templates are actually in the race (the documented Six-as-Nine
            // gap is ~0.03). A leading Nine with a lone OCR 6 stays Nine —
            // otherwise 9D→10S vanishes and the arrow snaps to Draw Stock.
            if (nineCandidate && !fourCandidate && !sevenCandidate) {
                val nineScore = rankScore(exactRankScores, Rank.Nine)
                if (sixScore + 0.05f >= nineScore && sixScore >= 0.38f) return Rank.Six
                return null
            }
            return Rank.Six
        }

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
     * Waste-only: fused Six/Seven on a real Eight. Directed — never the reverse —
     * so it cannot re-break genuine Sixes the way a new 6/8 confusion pair would.
     */
    fun correctEightOnWaste(
        legacyCard: Card?,
        tightCard: Card?,
        baseCard: Card?,
        exactRankScores: Map<Rank, Float>,
        inkGuess: RankInkHeuristics.Guess?,
        ocrRank: Rank?
    ): Rank? {
        val sixOrSeven = legacyCard?.rank == Rank.Six ||
            tightCard?.rank == Rank.Six ||
            baseCard?.rank == Rank.Six ||
            legacyCard?.rank == Rank.Seven ||
            tightCard?.rank == Rank.Seven ||
            baseCard?.rank == Rank.Seven
        if (!sixOrSeven) return null

        val eightScore = rankScore(exactRankScores, Rank.Eight)
        val sixScore = rankScore(exactRankScores, Rank.Six)
        val sevenScore = rankScore(exactRankScores, Rank.Seven)

        if (ocrRank == Rank.Eight) return Rank.Eight
        if (inkGuess?.rank == Rank.Eight && inkGuess.confidence >= 0.48f) return Rank.Eight
        if (tightCard?.rank == Rank.Eight && eightScore >= sixScore - 0.05f) return Rank.Eight
        if (eightScore >= 0.48f && eightScore + 0.02f >= maxOf(sixScore, sevenScore)) {
            return Rank.Eight
        }
        return null
    }

    /**
     * Waste-only: when a legacy or tight crop already read Spades for [rank], trust
     * that over the fused Clubs pick. Do not guess Spades from a narrow C-vs-S
     * template margin alone — the C0.83/S0.77 cluster appears on genuine Clubs too.
     * Deck-uniqueness for ambiguous waste black suits is handled later in
     * [DeckConstraintPass].
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
     * Waste C↔S when neither crop already read the partner suit: trust a strong
     * exact-template leader instead of the fused pick. Shape-based Spade
     * overrides (v1.4.64) flipped genuine Clubs (8C/4C); this only uses the
     * same suitTemplateScores already computed for waste fusion.
     *
     * Ambiguous fused reads may use the standard 0.80 / 0.04 exact bar.
     * Confident fused reads need a wider 0.82 / 0.08 gap so a 0.03 C-vs-S
     * cluster cannot invent Spades.
     */
    fun preferWasteExactBlackSuit(
        fusedSuit: Suit?,
        fusedAmbiguous: Boolean,
        exactBest: Suit?,
        exactBestScore: Float,
        exactSecondScore: Float
    ): Suit? {
        if (fusedSuit == null || fusedSuit.isRed) return null
        if (exactBest == null || exactBest.isRed) return null
        if (exactBest == fusedSuit) return null
        val margin = exactBestScore - exactSecondScore
        val strongExact = exactBestScore >= 0.82f && margin >= 0.08f
        val ambiguousExact = fusedAmbiguous &&
            exactBestScore >= 0.80f &&
            margin >= 0.04f
        if (!strongExact && !ambiguousExact) return null
        return exactBest
    }

    /**
     * Prefer corner OCR over waste fusion when OCR reads a rank that disagrees with
     * the fused PNG pick on a known confusion pair (3/J, 5/J, 6/4, 6/7, 6/9, K/10, Q/K, Q/10).
     * Eight vs Six/Seven is directed (OCR Eight wins; OCR Six does not steal Eight).
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
            return if (sixCanStealNine(exactRankScores)) Rank.Six else null
        }
        if (ocrRank == Rank.Eight &&
            (tightLegacyRanks.contains(Rank.Six) ||
                tightLegacyRanks.contains(Rank.Seven) ||
                baseRank == Rank.Six ||
                baseRank == Rank.Seven)
        ) {
            return Rank.Eight
        }
        if (ocrRank == Rank.Six &&
            tightLegacyRanks.contains(Rank.Seven) &&
            !tightLegacyRanks.contains(Rank.Six)
        ) {
            val eightScore = rankScore(exactRankScores, Rank.Eight)
            val sixScore = rankScore(exactRankScores, Rank.Six)
            if (eightScore >= 0.48f && eightScore + 0.02f >= sixScore) return Rank.Eight
            return Rank.Six
        }

        val fusionRank = baseRank ?: legacyRank ?: tightRank
        if (fusionRank != null && isConfusionPair(ocrRank, fusionRank)) {
            // Both waste crops already produced a rank. A later OCR hit is
            // often the covered fan card (ink-anchored region sits left of
            // the playable face): Q♥ vs 10♥, Q♣ vs 10♣, J♠ vs 3♣.
            // Keep the pair override only when one crop is missing, so OCR
            // can still break a single-sided Jack/Three or Jack/Five read.
            if (legacyRank != null && tightRank != null) return null
            if (ocrRank == Rank.Six && fusionRank == Rank.Nine &&
                !sixCanStealNine(exactRankScores)
            ) {
                return null
            }
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

    private fun sixCanStealNine(exactRankScores: Map<Rank, Float>): Boolean {
        val sixScore = rankScore(exactRankScores, Rank.Six)
        val nineScore = rankScore(exactRankScores, Rank.Nine)
        return sixScore + 0.05f >= nineScore && sixScore >= 0.38f
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
