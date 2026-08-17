package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WasteRankAmbiguityTest {
    private fun card(rank: Rank) = Card(rank, Suit.Hearts, faceUp = true, known = true)

    @Test
    fun prefersQueenWhenTenReadIsAmbiguous() {
        val rank = WasteRankCorrections.correctQueenOnWaste(
            legacyCard = card(Rank.Ten),
            tightCard = card(Rank.Ten),
            exactRankScores = mapOf(
                Rank.Ten to 0.74f,
                Rank.Queen to 0.78f,
                Rank.King to 0.55f
            ),
            inkGuess = null
        )
        assertEquals(Rank.Queen, rank)
    }

    @Test
    fun prefersKingWhenTenReadIsAmbiguous() {
        val rank = WasteRankCorrections.correctKingTenOnWaste(
            legacyCard = card(Rank.Ten),
            tightCard = card(Rank.Ten),
            exactRankScores = mapOf(
                Rank.Ten to 0.74f,
                Rank.King to 0.84f,
                Rank.Queen to 0.55f
            ),
            inkGuess = RankInkHeuristics.Guess(Rank.King, 0.56f)
        )
        assertEquals(Rank.King, rank)
    }

    @Test
    fun prefersQueenWhenKingReadIsAmbiguous() {
        val rank = WasteRankCorrections.correctQueenOnWaste(
            legacyCard = card(Rank.King),
            tightCard = card(Rank.King),
            exactRankScores = mapOf(
                Rank.King to 0.74f,
                Rank.Queen to 0.79f,
                Rank.Ten to 0.58f
            ),
            inkGuess = null
        )
        assertEquals(Rank.Queen, rank)
    }

    @Test
    fun keepsTenWhenKingEvidenceIsWeak() {
        val rank = WasteRankCorrections.correctKingTenOnWaste(
            legacyCard = card(Rank.Ten),
            tightCard = card(Rank.Ten),
            exactRankScores = mapOf(
                Rank.Ten to 0.80f,
                Rank.King to 0.76f
            ),
            inkGuess = RankInkHeuristics.Guess(Rank.Ten, 0.60f)
        )
        assertNull(rank)
    }

    @Test
    fun prefersFiveWhenJackReadIsAmbiguous() {
        val rank = WasteRankCorrections.correctFiveJack(
            legacyCard = card(Rank.Jack),
            tightCard = card(Rank.Jack),
            baseCard = card(Rank.Jack),
            exactRankScores = mapOf(Rank.Five to 0.62f, Rank.Jack to 0.58f),
            inkGuess = null
        )
        assertEquals(Rank.Five, rank)
    }

    @Test
    fun keepsJackWhenFiveEvidenceIsWeak() {
        val rank = WasteRankCorrections.correctFiveJack(
            legacyCard = card(Rank.Jack),
            tightCard = card(Rank.Jack),
            baseCard = card(Rank.Jack),
            exactRankScores = mapOf(Rank.Five to 0.40f, Rank.Jack to 0.80f),
            inkGuess = RankInkHeuristics.Guess(Rank.Jack, 0.55f)
        )
        assertNull(rank)
    }
}
