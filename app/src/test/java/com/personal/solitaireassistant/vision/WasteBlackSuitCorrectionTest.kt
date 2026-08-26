package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WasteBlackSuitCorrectionTest {
    @Test
    fun narrowClubLeadWithOcrTrustDoesNotGuessSpades() {
        val suit = WasteRankCorrections.correctBlackSuitOnWaste(
            rank = Rank.Six,
            legacyCard = Card(Rank.Four, Suit.Clubs, faceUp = true),
            tightCard = Card(Rank.Six, Suit.Clubs, faceUp = true)
        )
        assertNull(suit)
    }

    @Test
    fun eitherCropReadingSpadesWinsRegardlessOfOcrTrust() {
        val suit = WasteRankCorrections.correctBlackSuitOnWaste(
            rank = Rank.Three,
            legacyCard = Card(Rank.Jack, Suit.Clubs, faceUp = true),
            tightCard = Card(Rank.Three, Suit.Spades, faceUp = true)
        )
        assertEquals(Suit.Spades, suit)
    }

    @Test
    fun legacyCropReadingSpadesWins() {
        val suit = WasteRankCorrections.correctBlackSuitOnWaste(
            rank = Rank.Eight,
            legacyCard = Card(Rank.Eight, Suit.Spades, faceUp = true),
            tightCard = Card(Rank.Eight, Suit.Clubs, faceUp = true)
        )
        assertEquals(Suit.Spades, suit)
    }

    @Test
    fun neitherCropReadingSpadesDoesNotFlip() {
        val suit = WasteRankCorrections.correctBlackSuitOnWaste(
            rank = Rank.Six,
            legacyCard = Card(Rank.Four, Suit.Clubs, faceUp = true),
            tightCard = null
        )
        assertNull(suit)
    }
}
