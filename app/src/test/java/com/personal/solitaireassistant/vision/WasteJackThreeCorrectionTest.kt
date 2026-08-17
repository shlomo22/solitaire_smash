package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WasteJackThreeCorrectionTest {
    private fun card(rank: Rank) = Card(rank, Suit.Hearts, faceUp = true, known = true)

    @Test
    fun prefersThreeWhenTemplateScoresBeatJack() {
        val rank = WasteRankCorrections.correctJackThree(
            legacyCard = card(Rank.Jack),
            tightCard = card(Rank.Jack),
            baseCard = card(Rank.Jack),
            exactRankScores = mapOf(Rank.Three to 0.62f, Rank.Jack to 0.58f),
            inkGuess = null
        )
        assertEquals(Rank.Three, rank)
    }

    @Test
    fun prefersThreeWhenInkShapeIsConfident() {
        val rank = WasteRankCorrections.correctJackThree(
            legacyCard = card(Rank.Jack),
            tightCard = card(Rank.Jack),
            baseCard = card(Rank.Jack),
            exactRankScores = mapOf(Rank.Three to 0.44f, Rank.Jack to 0.71f),
            inkGuess = RankInkHeuristics.Guess(Rank.Three, 0.55f)
        )
        assertEquals(Rank.Three, rank)
    }

    @Test
    fun keepsJackWhenEvidenceIsClear() {
        val rank = WasteRankCorrections.correctJackThree(
            legacyCard = card(Rank.Jack),
            tightCard = card(Rank.Jack),
            baseCard = card(Rank.Jack),
            exactRankScores = mapOf(Rank.Three to 0.40f, Rank.Jack to 0.80f),
            inkGuess = RankInkHeuristics.Guess(Rank.Jack, 0.55f)
        )
        assertNull(rank)
    }
}
