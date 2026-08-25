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
}
