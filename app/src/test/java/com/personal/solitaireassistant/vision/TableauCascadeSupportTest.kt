package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableauCascadeSupportTest {
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
