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
        // Both crops already ranked: a later OCR 5 is the covered fan card
        // (v1.4.82: 032046 Jack+Four then whole@707 ocr='5' → JC vs 5S).
        if (ocrRank == Rank.Five &&
            fiveScore >= jackScore - 0.05f &&
            (legacyCard?.rank == null || tightCard?.rank == null)
        ) {
            return Rank.Five
        }
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

        // A Jack crop often templates as Four; Six then steals at the 0.38
        // floor (v1.4.81: JS vs 3C became JS vs 6C). Real Sixes that OCR as
        // "6" still take the ocrRank==Six path below.
        val jackCandidate = legacyCard?.rank == Rank.Jack ||
            tightCard?.rank == Rank.Jack ||
            baseCard?.rank == Rank.Jack
        if (jackCandidate) return null

        // Same magnet on a real Eight that tight-crops as Four
        // (20260824_202636: legacy=Eight, tight=Four → fused Six).
        val eightCandidate = legacyCard?.rank == Rank.Eight ||
            tightCard?.rank == Rank.Eight ||
            baseCard?.rank == Rank.Eight
        if (eightCandidate) return null

        val sixScore = rankScore(exactRankScores, Rank.Six)
        val eightScore = rankScore(exactRankScores, Rank.Eight)
        // Smash 8 and 6 share stacked loops. v1.4.44's Six-over-Seven path
        // was stealing real waste Eights (golden 20260825_131411 8♥, 132126 8♦).
        if (ocrRank == Rank.Eight) return null
        if (eightScore >= 0.45f && eightScore >= sixScore) return null
        if (ocrRank == Rank.Six) {
            // Fuller legacy crop already Nine: OCR "6" + tight Six is the
            // Smash nine false-positive cluster (114135/121738/121822).
            if (legacyCard?.rank == Rank.Nine) return null
            // Tight Nine with legacy Four (190337): OCR 6 is the Six magnet,
            // not a real Six. Keep Nine.
            if (tightCard?.rank == Rank.Nine && legacyCard?.rank == Rank.Four) {
                return null
            }
            // OCR "6" on a fused Nine elsewhere: only steal when Six templates
            // are actually in the race (documented Six-as-Nine gap ~0.03).
            if (nineCandidate) {
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
        // No silent Four→Six without OCR "6". The 0.38 floor magnet invents
        // Six on real Eights/Jacks whose tight crop templates as Four and
        // every OCR region misses (132126/132140/155538 8D, 205220 Jack).
        // Real Six-as-Four recoveries already arrive via ocrRank==Six above
        // (and via OCR on 040931-class boards).
        if (nineCandidate) {
            val nineScore = rankScore(exactRankScores, Rank.Nine)
            if (sixScore + 0.05f >= nineScore && sixScore >= 0.38f) return Rank.Six
        }
        return null
    }

    /**
     * Waste-only: tight/legacy Four with OCR empty, but center-glyph ink is a
     * tall narrow Jack. Covers the no-OCR Jack→Four magnet (220815/221831/
     * 230055) where every whole/face/corner probe returns `ocr=miss:empty`
     * and templates lock Four at ~0.57. Directed Four→Jack only; gated on
     * [RankInkHeuristics] Jack (aspect &lt; 0.70, dens ≤ 0.40, midCR &gt; 0.30)
     * which separates waste Jacks from waste Fours on the current golden set.
     */
    fun correctJackOverFourOnWaste(
        legacyCard: Card?,
        tightCard: Card?,
        baseCard: Card?,
        inkGuess: RankInkHeuristics.Guess?,
        ocrRank: Rank?
    ): Rank? {
        val fourCandidate = legacyCard?.rank == Rank.Four ||
            tightCard?.rank == Rank.Four ||
            baseCard?.rank == Rank.Four
        if (!fourCandidate) return null
        if (legacyCard?.rank == Rank.Jack || tightCard?.rank == Rank.Jack) {
            return Rank.Jack
        }
        // A non-Four OCR hit belongs to ocrRankOverride (Five/Six/Eight/…).
        if (ocrRank != null && ocrRank != Rank.Four) return null
        if (inkGuess?.rank == Rank.Jack && inkGuess.confidence >= 0.55f) {
            return Rank.Jack
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
        // Fuller legacy crop already read Eight while tight latched Four and
        // Six-steal won (20260824_202636 8D vs 6D). Trust the legacy Eight.
        if (legacyCard?.rank == Rank.Eight) return Rank.Eight

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
     * the fused PNG pick on a known confusion pair (3/J, 5/J, 5/4, 6/4, 6/7, 6/9,
     * K/10, Q/K, Q/10). Eight vs Six/Seven is directed (OCR Eight wins; OCR Six
     * does not steal Eight). Five vs Four is directed (OCR Five wins on a tight
     * Four; Evaluate 080754 OCR='5' still fused Four until v1.4.86).
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
        if (ocrRank in candidateRanks) {
            // OCR agreeing with a crop normally confirms. When crops disagree,
            // agreeing with the tight/wrong side alone forced Six over a correct
            // legacy Nine (114135: legacy=Nine, tight=Six, OCR=6 → 9H vs 6H).
            // Only early-confirm when OCR matches the fuller legacy crop (or
            // crops already agree / one side is missing).
            val cropsAgreeOrSingle =
                legacyRank == null || tightRank == null || legacyRank == tightRank
            if (cropsAgreeOrSingle || ocrRank == legacyRank) return ocrRank
        }

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
            // Nine+Four fan with OCR "6" (190337): keep Nine — do not auto-Six.
            if (tightLegacyRanks.contains(Rank.Nine)) return null
            return Rank.Six
        }
        if (ocrRank == Rank.Six &&
            tightLegacyRanks.contains(Rank.Nine) &&
            !tightLegacyRanks.contains(Rank.Six)
        ) {
            // Four+Nine with OCR 6 (190337): the Four gate above already
            // returned null when Nine is present. Same fan can hit this
            // Nine-only gate — keep Nine unless Six templates strictly win
            // and Four is not also on a crop.
            if (tightLegacyRanks.contains(Rank.Four)) return null
            // Dual-crop Nine (190358/190512): first OCR "6" is the covered
            // fan neighbor. sixCanStealNine must not override a pair that
            // already agreed on Nine — finishWaste never reaches the later
            // "keep legacy Nine" fallback once this returns Six.
            if (legacyRank == Rank.Nine && tightRank == Rank.Nine) return null
            return if (sixCanStealNine(exactRankScores)) Rank.Six else null
        }
        if (ocrRank == Rank.Eight &&
            (tightLegacyRanks.contains(Rank.Six) ||
                tightLegacyRanks.contains(Rank.Seven) ||
                tightLegacyRanks.contains(Rank.Four) ||
                baseRank == Rank.Six ||
                baseRank == Rank.Seven ||
                baseRank == Rank.Four)
        ) {
            return Rank.Eight
        }
        // Jack templates as Four on a tight waste crop. OCR "J" on that same
        // card is the playable glyph (205220, 230055, 143855). The old veto
        // was 143855, whose pixels are a Jack labeled Six.
        if (ocrRank == Rank.Jack &&
            (tightRank == Rank.Four ||
                legacyRank == Rank.Four ||
                baseRank == Rank.Four) &&
            Rank.Jack !in tightLegacyRanks
        ) {
            return Rank.Jack
        }
        // Five templates as Four on a tight waste crop (080754 5D vs 4D:
        // legacy=null, tight=Four, OCR='5'@0.62, fused Four). Same directed
        // override as Jack-over-Four. Skip when a crop already ranked Jack —
        // that is the Jack+Four fan case handled below.
        if (ocrRank == Rank.Five &&
            (tightRank == Rank.Four ||
                legacyRank == Rank.Four ||
                baseRank == Rank.Four) &&
            Rank.Five !in tightLegacyRanks &&
            Rank.Jack !in tightLegacyRanks
        ) {
            return Rank.Five
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

        // Jack templates as Four on the tight crop; a later left-fan OCR
        // then reads the covered 5 or 3 (032046 3/5/J, 230337 K/3/J).
        // Keep the crop Jack. Do not wait for isConfusionPair to return
        // null and then let correctFiveJack steal.
        if (legacyRank == Rank.Jack &&
            tightRank == Rank.Four &&
            (ocrRank == Rank.Five || ocrRank == Rank.Three)
        ) {
            return Rank.Jack
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
            setOf(Rank.Five, Rank.Four),
            setOf(Rank.Six, Rank.Four),
            setOf(Rank.Six, Rank.Nine),
            setOf(Rank.Six, Rank.Seven) -> true
            else -> false
        }
    }
}
