package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WasteBlackSuitCorrectionTest {
    private val clubScores = mapOf(
        Suit.Clubs to 0.83f,
        Suit.Spades to 0.77f
    )

    @Test
    fun narrowClubLeadWithOcrTrustPrefersSpades() {
        val suit = WasteRankCorrections.correctBlackSuitOnWaste(
            rank = Rank.Six,
            currentSuit = Suit.Clubs,
            legacyCard = Card(Rank.Four, Suit.Clubs, faceUp = true),
            tightCard = Card(Rank.Six, Suit.Clubs, faceUp = true),
            exactSuitScores = clubScores,
            ocrRankTrusted = true
        )
        assertEquals(Suit.Spades, suit)
    }

    @Test
    fun wideClubLeadKeepsClubsEvenWithOcrTrust() {
        val suit = WasteRankCorrections.correctBlackSuitOnWaste(
            rank = Rank.Eight,
            currentSuit = Suit.Clubs,
            legacyCard = Card(Rank.Eight, Suit.Clubs, faceUp = true),
            tightCard = null,
            exactSuitScores = mapOf(Suit.Clubs to 0.90f, Suit.Spades to 0.72f),
            ocrRankTrusted = true
        )
        assertNull(suit)
    }

    @Test
    fun narrowClubLeadWithoutOcrTrustDoesNotFlip() {
        val suit = WasteRankCorrections.correctBlackSuitOnWaste(
            rank = Rank.Six,
            currentSuit = Suit.Clubs,
            legacyCard = Card(Rank.Four, Suit.Clubs, faceUp = true),
            tightCard = Card(Rank.Six, Suit.Clubs, faceUp = true),
            exactSuitScores = clubScores,
            ocrRankTrusted = false
        )
        assertNull(suit)
    }

    @Test
    fun eitherCropReadingSpadesWinsRegardlessOfOcrTrust() {
        val suit = WasteRankCorrections.correctBlackSuitOnWaste(
            rank = Rank.Three,
            currentSuit = Suit.Clubs,
            legacyCard = Card(Rank.Jack, Suit.Clubs, faceUp = true),
            tightCard = Card(Rank.Three, Suit.Spades, faceUp = true),
            exactSuitScores = clubScores,
            ocrRankTrusted = false
        )
        assertEquals(Suit.Spades, suit)
    }
}
