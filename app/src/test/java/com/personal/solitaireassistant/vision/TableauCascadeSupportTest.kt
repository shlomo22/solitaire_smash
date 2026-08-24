package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableauCascadeSupportTest {
    @Test
    fun enrichGeometricFromRejectedReadPicksDiamondOverDefaultHearts() {
        val geometric = TableauCascadeSupport.geometricCascadeCard(
            bottomCard = Card(Rank.Three, Suit.Spades, faceUp = true, known = true),
            distanceFromBottom = 1
        )
        assertEquals(Suit.Hearts, geometric.suit)
        val hit = RecognitionHit(
            card = null,
            confidence = 0.40f,
            isFaceDown = false,
            isEmpty = false,
            diagnostic = "face-up-color-red-rank=null",
            trace = RecognitionTrace(
                suitTemplates = "D:0.86 H:0.76"
            )
        )
        val enriched = TableauCascadeSupport.enrichGeometricFromRejectedRead(geometric, hit)
        assertEquals(Rank.Four, enriched.rank)
        assertEquals(Suit.Diamonds, enriched.suit)
    }

    @Test
    fun promoteTrustedRunUnlocksGeometricCascade() {
        val bottom = Card(Rank.Three, Suit.Diamonds, faceUp = true, known = true)
        val run = listOf(
            Card(Rank.Six, Suit.Spades, faceUp = true, known = true, inferred = true),
            Card(Rank.Five, Suit.Hearts, faceUp = true, known = true, inferred = true),
            Card(Rank.Four, Suit.Spades, faceUp = true, known = true, inferred = true),
            bottom
        )

        val promoted = TableauCascadeSupport.promoteTrustedRun(run)

        assertFalse(promoted.any { it.inferred })
    }

    @Test
    fun promoteTrustedRunKeepsMismatchingCardsInferred() {
        val bottom = Card(Rank.Seven, Suit.Spades, faceUp = true, known = true)
        val run = listOf(
            Card(Rank.Queen, Suit.Hearts, faceUp = true, known = true, inferred = true),
            Card(Rank.Jack, Suit.Clubs, faceUp = true, known = true, inferred = true),
            bottom
        )

        val promoted = TableauCascadeSupport.promoteTrustedRun(
            run.dropLast(1) + Card(Rank.Eight, Suit.Hearts, faceUp = true, known = true)
        )

        assertTrue(promoted.any { it.inferred })
    }
}
