package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableauCascadeSupportTest {
    private val bottomTwoClubs = Card(Rank.Two, Suit.Clubs, faceUp = true, known = true)

    @Test
    fun prefersGeometricWhenDoublyAnchoredConsensusDisagrees() {
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomTwoClubs, 3)
        val direct = Card(Rank.Jack, Suit.Hearts, faceUp = true, known = true)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomTwoClubs,
                bottomReadConfidence = 0.87f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.62f,
                rankCountConsistent = true
            )
        )
    }

    @Test
    fun prefersGeometricForWeakJackWhenLeadingUnknown() {
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomTwoClubs, 5)
        val direct = Card(Rank.Jack, Suit.Hearts, faceUp = true, known = true)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomTwoClubs,
                bottomReadConfidence = 0.87f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.60f,
                rankCountConsistent = false
            )
        )
    }

    @Test
    fun prefersGeometricForAceFalsePositiveAgainstBottomAnchor() {
        val bottomSeven = Card(Rank.Seven, Suit.Spades, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomSeven, 2)
        val direct = Card(Rank.Ace, Suit.Hearts, faceUp = true, known = true)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomSeven,
                bottomReadConfidence = 0.85f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.58f,
                rankCountConsistent = false
            )
        )
    }

    @Test
    fun keepsStrongDirectReadThatAgreesWithGeometry() {
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomTwoClubs, 2)
        val direct = Card(Rank.Four, Suit.Hearts, faceUp = true, known = true)
        assertFalse(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomTwoClubs,
                bottomReadConfidence = 0.87f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.82f,
                rankCountConsistent = false
            )
        )
    }

    @Test
    fun prefersGeometricForLargeRankDeltaWithWeakBottomOnlyRead() {
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomTwoClubs, 4)
        val direct = Card(Rank.King, Suit.Spades, faceUp = true, known = true)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomTwoClubs,
                bottomReadConfidence = 0.87f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.78f,
                rankCountConsistent = false
            )
        )
    }

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
    fun prefersGeometricForFiveSixConfusionAgainstBottomAnchor() {
        val bottomFour = Card(Rank.Four, Suit.Hearts, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomFour, 1)
        val direct = Card(Rank.Six, Suit.Clubs, faceUp = true, known = true)
        assertEquals(Rank.Five, geometric.rank)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomFour,
                bottomReadConfidence = 0.88f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.78f,
                rankCountConsistent = false
            )
        )
    }

    @Test
    fun prefersGeometricForStrongishAceWhenGeometrySaysJack() {
        val bottomTen = Card(Rank.Ten, Suit.Hearts, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomTen, 1)
        val direct = Card(Rank.Ace, Suit.Spades, faceUp = true, known = true)
        assertEquals(Rank.Jack, geometric.rank)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomTen,
                bottomReadConfidence = 0.86f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.82f,
                rankCountConsistent = false
            )
        )
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
