package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WasteOcrRankOverrideTest {
    private val jackHearts = Card(Rank.Jack, Suit.Hearts, faceUp = true)

    @Test
    fun ocrThreeOverridesStrongJackFusion() {
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Three,
            legacyCard = jackHearts,
            tightCard = null,
            baseCard = jackHearts
        )
        assertEquals(Rank.Three, override)
    }

    @Test
    fun ocrFiveOverridesStrongJackFusion() {
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Five,
            legacyCard = jackHearts,
            tightCard = null,
            baseCard = jackHearts
        )
        assertEquals(Rank.Five, override)
    }

    @Test
    fun ocrKingOverridesTenFusion() {
        val tenHearts = Card(Rank.Ten, Suit.Hearts, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.King,
            legacyCard = tenHearts,
            tightCard = Card(Rank.King, Suit.Hearts, faceUp = true),
            baseCard = tenHearts
        )
        assertEquals(Rank.King, override)
    }

    @Test
    fun ocrMissDoesNotOverrideUnrelatedFusion() {
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = null,
            legacyCard = jackHearts,
            tightCard = null,
            baseCard = jackHearts
        )
        assertNull(override)
    }

    @Test
    fun ocrSixOverridesFourFusion() {
        val fourClubs = Card(Rank.Four, Suit.Clubs, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Six,
            legacyCard = null,
            tightCard = fourClubs,
            baseCard = fourClubs
        )
        assertEquals(Rank.Six, override)
    }

    @Test
    fun ocrSixOverridesNineFusionWhenSixIsCompetitive() {
        val nineDiamonds = Card(Rank.Nine, Suit.Diamonds, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Six,
            legacyCard = null,
            tightCard = nineDiamonds,
            baseCard = nineDiamonds,
            exactRankScores = mapOf(Rank.Nine to 0.44f, Rank.Six to 0.41f)
        )
        assertEquals(Rank.Six, override)
    }

    @Test
    fun ocrSixDoesNotStealWhenBothCropsReadNine() {
        // Evaluate 190358/190512: legacy=Nine, tight=Nine, first OCR='6'@0.62.
        val nineClubs = Card(Rank.Nine, Suit.Clubs, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Six,
            legacyCard = nineClubs,
            tightCard = nineClubs,
            baseCard = nineClubs,
            exactRankScores = mapOf(Rank.Nine to 0.44f, Rank.Six to 0.41f)
        )
        assertNull(override)
    }

    @Test
    fun ocrSixDoesNotStealLeadingNine() {
        val nineDiamonds = Card(Rank.Nine, Suit.Diamonds, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Six,
            legacyCard = null,
            tightCard = nineDiamonds,
            baseCard = nineDiamonds,
            exactRankScores = mapOf(Rank.Nine to 0.62f, Rank.Six to 0.40f)
        )
        assertNull(override)
    }

    @Test
    fun ocrSixMatchingTightDoesNotBeatLegacyNine() {
        // Evaluate 114135/121738/121822: legacy=Nine, tight=Six, OCR=6.
        val nineHearts = Card(Rank.Nine, Suit.Hearts, faceUp = true)
        val sixHearts = Card(Rank.Six, Suit.Hearts, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Six,
            legacyCard = nineHearts,
            tightCard = sixHearts,
            baseCard = nineHearts,
            exactRankScores = mapOf(Rank.Nine to 0.55f, Rank.Six to 0.40f)
        )
        assertNull(override)
    }

    @Test
    fun correctSixOnWasteDoesNotStealWhenLegacyIsNine() {
        val rank = WasteRankCorrections.correctSixOnWaste(
            legacyCard = Card(Rank.Nine, Suit.Hearts, faceUp = true, known = true),
            tightCard = Card(Rank.Six, Suit.Hearts, faceUp = true, known = true),
            baseCard = Card(Rank.Six, Suit.Hearts, faceUp = true, known = true),
            exactRankScores = mapOf(Rank.Nine to 0.45f, Rank.Six to 0.44f),
            ocrRank = Rank.Six
        )
        assertNull(rank)
    }

    @Test
    fun ocrSixDoesNotStealNineWhenLegacyIsFour() {
        // Evaluate 190337: legacy=Four, tight=Nine, OCR=6.
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Six,
            legacyCard = Card(Rank.Four, Suit.Spades, faceUp = true),
            tightCard = Card(Rank.Nine, Suit.Spades, faceUp = true),
            baseCard = Card(Rank.Nine, Suit.Spades, faceUp = true),
            exactRankScores = mapOf(Rank.Nine to 0.45f, Rank.Six to 0.44f, Rank.Four to 0.42f)
        )
        assertNull(override)
    }

    @Test
    fun correctSixOnWasteDoesNotStealNineWhenFourAlsoPresent() {
        // Evaluate 190337: legacy=Four, tight=Nine, OCR=6.
        val rank = WasteRankCorrections.correctSixOnWaste(
            legacyCard = Card(Rank.Four, Suit.Spades, faceUp = true, known = true),
            tightCard = Card(Rank.Nine, Suit.Spades, faceUp = true, known = true),
            baseCard = Card(Rank.Nine, Suit.Spades, faceUp = true, known = true),
            exactRankScores = mapOf(Rank.Nine to 0.55f, Rank.Six to 0.40f, Rank.Four to 0.42f),
            ocrRank = Rank.Six
        )
        assertNull(rank)
    }

    @Test
    fun correctSixOnWasteDoesNotStealWhenLegacyIsEight() {
        // Evaluate 202636: legacy=Eight, tight=Four → was fused Six.
        val rank = WasteRankCorrections.correctSixOnWaste(
            legacyCard = Card(Rank.Eight, Suit.Diamonds, faceUp = true, known = true),
            tightCard = Card(Rank.Four, Suit.Diamonds, faceUp = true, known = true),
            baseCard = Card(Rank.Four, Suit.Diamonds, faceUp = true, known = true),
            exactRankScores = mapOf(Rank.Four to 0.50f, Rank.Six to 0.45f, Rank.Eight to 0.42f),
            ocrRank = Rank.Three
        )
        assertNull(rank)
    }

    @Test
    fun correctEightOnWasteTrustsLegacyEightOverFour() {
        val rank = WasteRankCorrections.correctEightOnWaste(
            legacyCard = Card(Rank.Eight, Suit.Diamonds, faceUp = true, known = true),
            tightCard = Card(Rank.Four, Suit.Diamonds, faceUp = true, known = true),
            baseCard = Card(Rank.Four, Suit.Diamonds, faceUp = true, known = true),
            exactRankScores = mapOf(Rank.Four to 0.50f, Rank.Six to 0.45f, Rank.Eight to 0.42f),
            inkGuess = null,
            ocrRank = Rank.Three
        )
        assertEquals(Rank.Eight, rank)
    }

    @Test
    fun correctSixOnWasteDoesNotStealLeadingNine() {
        val nineDiamonds = Card(Rank.Nine, Suit.Diamonds, faceUp = true, known = true)
        val rank = WasteRankCorrections.correctSixOnWaste(
            legacyCard = nineDiamonds,
            tightCard = nineDiamonds,
            baseCard = nineDiamonds,
            exactRankScores = mapOf(Rank.Nine to 0.62f, Rank.Six to 0.40f),
            ocrRank = Rank.Six
        )
        assertNull(rank)
    }

    @Test
    fun correctSixOnWastePrefersSixOverSevenScores() {
        val rank = WasteRankCorrections.correctSixOnWaste(
            legacyCard = Card(Rank.Seven, Suit.Hearts, faceUp = true, known = true),
            tightCard = Card(Rank.Seven, Suit.Hearts, faceUp = true, known = true),
            baseCard = Card(Rank.Seven, Suit.Hearts, faceUp = true, known = true),
            exactRankScores = mapOf(Rank.Seven to 0.59f, Rank.Six to 0.55f),
            ocrRank = null
        )
        assertEquals(Rank.Six, rank)
    }

    @Test
    fun ocrSixOverridesSevenFusion() {
        val sevenHearts = Card(Rank.Seven, Suit.Hearts, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Six,
            legacyCard = null,
            tightCard = sevenHearts,
            baseCard = sevenHearts
        )
        assertEquals(Rank.Six, override)
    }

    @Test
    fun correctSixOnWastePrefersOcrSixOverFour() {
        val rank = WasteRankCorrections.correctSixOnWaste(
            legacyCard = Card(Rank.Four, Suit.Clubs, faceUp = true, known = true),
            tightCard = Card(Rank.Four, Suit.Clubs, faceUp = true, known = true),
            baseCard = Card(Rank.Four, Suit.Clubs, faceUp = true, known = true),
            exactRankScores = mapOf(Rank.Four to 0.56f, Rank.Six to 0.47f),
            ocrRank = Rank.Six
        )
        assertEquals(Rank.Six, rank)
    }

    @Test
    fun correctSixOnWasteDoesNotStealFourWithoutOcr() {
        // Evaluate 132126/132140: tight Four, OCR miss, was fused Six at 0.38.
        val rank = WasteRankCorrections.correctSixOnWaste(
            legacyCard = null,
            tightCard = Card(Rank.Four, Suit.Diamonds, faceUp = true, known = true),
            baseCard = Card(Rank.Four, Suit.Diamonds, faceUp = true, known = true),
            exactRankScores = mapOf(Rank.Four to 0.42f, Rank.Six to 0.40f, Rank.Eight to 0.38f),
            ocrRank = null
        )
        assertNull(rank)
    }

    @Test
    fun correctEightOnWasteDoesNotStealFourViaInkAlone() {
        // v1.4.90 Evaluate: ink Eight on real Fours → Four→Eight (16) net −30.
        val rank = WasteRankCorrections.correctEightOnWaste(
            legacyCard = null,
            tightCard = Card(Rank.Four, Suit.Diamonds, faceUp = true, known = true),
            baseCard = Card(Rank.Four, Suit.Diamonds, faceUp = true, known = true),
            exactRankScores = mapOf(Rank.Four to 0.42f, Rank.Six to 0.40f, Rank.Eight to 0.38f),
            inkGuess = RankInkHeuristics.Guess(Rank.Eight, 0.52f),
            ocrRank = null
        )
        assertNull(rank)
    }

    @Test
    fun ocrTenDoesNotOverrideWhenBothCropsReadQueen() {
        val queen = Card(Rank.Queen, Suit.Hearts, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Ten,
            legacyCard = queen,
            tightCard = queen,
            baseCard = queen
        )
        assertNull(override)
    }

    @Test
    fun ocrFiveKeepsJackWhenCropsAreJackAndFour() {
        val jack = Card(Rank.Jack, Suit.Clubs, faceUp = true)
        val four = Card(Rank.Four, Suit.Clubs, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Five,
            legacyCard = jack,
            tightCard = four,
            baseCard = jack
        )
        assertEquals(Rank.Jack, override)
    }

    @Test
    fun correctFiveJackDoesNotAdoptOcrFiveWhenBothCropsHaveRanks() {
        val jack = Card(Rank.Jack, Suit.Clubs, faceUp = true, known = true)
        val four = Card(Rank.Four, Suit.Clubs, faceUp = true, known = true)
        val rank = WasteRankCorrections.correctFiveJack(
            legacyCard = jack,
            tightCard = four,
            baseCard = jack,
            exactRankScores = mapOf(Rank.Five to 0.41f, Rank.Jack to 0.36f),
            inkGuess = null,
            ocrRank = Rank.Five
        )
        assertNull(rank)
    }

    @Test
    fun ocrThreeKeepsJackWhenCropsDisagreeJackAndFour() {
        val jack = Card(Rank.Jack, Suit.Spades, faceUp = true)
        val four = Card(Rank.Four, Suit.Clubs, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Three,
            legacyCard = jack,
            tightCard = four,
            baseCard = jack
        )
        assertEquals(Rank.Jack, override)
    }

    @Test
    fun ocrMatchingCandidateStillWins() {
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Jack,
            legacyCard = jackHearts,
            tightCard = Card(Rank.Three, Suit.Hearts, faceUp = true),
            baseCard = jackHearts
        )
        assertEquals(Rank.Jack, override)
    }

    @Test
    fun ocrJackOverridesFourFusion() {
        val fourClubs = Card(Rank.Four, Suit.Clubs, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Jack,
            legacyCard = null,
            tightCard = fourClubs,
            baseCard = fourClubs
        )
        assertEquals(Rank.Jack, override)
    }

    @Test
    fun ocrFiveOverridesFourFusion() {
        // Evaluate 20260824_080754: tight Four, OCR '5'@0.62, fused Four.
        val fourDiamonds = Card(Rank.Four, Suit.Diamonds, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Five,
            legacyCard = null,
            tightCard = fourDiamonds,
            baseCard = fourDiamonds
        )
        assertEquals(Rank.Five, override)
    }

    @Test
    fun ocrEightOverridesFourFusion() {
        val fourHearts = Card(Rank.Four, Suit.Hearts, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Eight,
            legacyCard = null,
            tightCard = fourHearts,
            baseCard = fourHearts
        )
        assertEquals(Rank.Eight, override)
    }

    @Test
    fun correctSixOnWasteDoesNotStealFourWhenJackIsOnACrop() {
        val rank = WasteRankCorrections.correctSixOnWaste(
            legacyCard = Card(Rank.Jack, Suit.Clubs, faceUp = true, known = true),
            tightCard = Card(Rank.Four, Suit.Clubs, faceUp = true, known = true),
            baseCard = Card(Rank.Jack, Suit.Clubs, faceUp = true, known = true),
            exactRankScores = mapOf(Rank.Six to 0.38f, Rank.Four to 0.36f, Rank.Jack to 0.42f),
            ocrRank = Rank.Three
        )
        assertNull(rank)
    }

    @Test
    fun ocrEightOverridesSixFusion() {
        val sixHearts = Card(Rank.Six, Suit.Hearts, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Eight,
            legacyCard = sixHearts,
            tightCard = sixHearts,
            baseCard = sixHearts
        )
        assertEquals(Rank.Eight, override)
    }

    @Test
    fun ocrEightOverridesSevenFusion() {
        val sevenHearts = Card(Rank.Seven, Suit.Hearts, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Eight,
            legacyCard = sevenHearts,
            tightCard = sevenHearts,
            baseCard = sevenHearts
        )
        assertEquals(Rank.Eight, override)
    }

    @Test
    fun ocrSixDoesNotStealSevenWhenEightTemplatesLead() {
        val sevenHearts = Card(Rank.Seven, Suit.Hearts, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Six,
            legacyCard = sevenHearts,
            tightCard = sevenHearts,
            baseCard = sevenHearts,
            exactRankScores = mapOf(Rank.Eight to 0.62f, Rank.Six to 0.51f, Rank.Seven to 0.59f)
        )
        assertEquals(Rank.Eight, override)
    }

    @Test
    fun correctEightOnWasteRecoversFromSixFusion() {
        val sixHearts = Card(Rank.Six, Suit.Hearts, faceUp = true, known = true)
        val rank = WasteRankCorrections.correctEightOnWaste(
            legacyCard = sixHearts,
            tightCard = sixHearts,
            baseCard = sixHearts,
            exactRankScores = mapOf(Rank.Six to 0.52f, Rank.Eight to 0.54f, Rank.Seven to 0.40f),
            inkGuess = null,
            ocrRank = null
        )
        assertEquals(Rank.Eight, rank)
    }

    @Test
    fun correctEightOnWasteTrustsOcrEight() {
        val sixHearts = Card(Rank.Six, Suit.Hearts, faceUp = true, known = true)
        val rank = WasteRankCorrections.correctEightOnWaste(
            legacyCard = sixHearts,
            tightCard = sixHearts,
            baseCard = sixHearts,
            exactRankScores = mapOf(Rank.Six to 0.60f, Rank.Eight to 0.40f),
            inkGuess = null,
            ocrRank = Rank.Eight
        )
        assertEquals(Rank.Eight, rank)
    }

    @Test
    fun correctSixOnWasteDoesNotStealCompetitiveEight() {
        val rank = WasteRankCorrections.correctSixOnWaste(
            legacyCard = Card(Rank.Seven, Suit.Hearts, faceUp = true, known = true),
            tightCard = Card(Rank.Seven, Suit.Hearts, faceUp = true, known = true),
            baseCard = Card(Rank.Seven, Suit.Hearts, faceUp = true, known = true),
            exactRankScores = mapOf(Rank.Seven to 0.59f, Rank.Six to 0.55f, Rank.Eight to 0.57f),
            ocrRank = Rank.Six
        )
        assertNull(rank)
    }

    @Test
    fun inkJackOverridesTightFourWhenOcrMisses() {
        val fourClubs = Card(Rank.Four, Suit.Clubs, faceUp = true)
        val rank = WasteRankCorrections.correctJackOverFourOnWaste(
            legacyCard = null,
            tightCard = fourClubs,
            baseCard = fourClubs,
            inkGuess = RankInkHeuristics.Guess(Rank.Jack, 0.55f),
            ocrRank = null
        )
        assertEquals(Rank.Jack, rank)
    }

    @Test
    fun inkJackDoesNotOverrideWhenOcrAlreadyNamedFive() {
        val fourClubs = Card(Rank.Four, Suit.Clubs, faceUp = true)
        val rank = WasteRankCorrections.correctJackOverFourOnWaste(
            legacyCard = null,
            tightCard = fourClubs,
            baseCard = fourClubs,
            inkGuess = RankInkHeuristics.Guess(Rank.Jack, 0.55f),
            ocrRank = Rank.Five
        )
        assertNull(rank)
    }

    @Test
    fun inkJackDoesNotFireWithoutFourCandidate() {
        val sixClubs = Card(Rank.Six, Suit.Clubs, faceUp = true)
        val rank = WasteRankCorrections.correctJackOverFourOnWaste(
            legacyCard = null,
            tightCard = sixClubs,
            baseCard = sixClubs,
            inkGuess = RankInkHeuristics.Guess(Rank.Jack, 0.55f),
            ocrRank = null
        )
        assertNull(rank)
    }
}
