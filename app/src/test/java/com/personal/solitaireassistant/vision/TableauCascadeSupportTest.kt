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
        assertEquals(Suit.Diamonds, geometric.suit)
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
        assertFalse(enriched.suitAmbiguous)
    }

    @Test
    fun enrichGeometricMarksAmbiguousWhenColorFamilyScoresMissing() {
        val geometric = TableauCascadeSupport.geometricCascadeCard(
            bottomCard = Card(Rank.Five, Suit.Hearts, faceUp = true, known = true),
            distanceFromBottom = 1
        )
        assertEquals(Suit.Clubs, geometric.suit) // black placeholder
        // Wrong-color templates only — the Clubs→Diamonds failure mode.
        val hit = RecognitionHit(
            card = Card(Rank.Six, Suit.Diamonds, faceUp = true, known = true),
            confidence = 0.84f,
            isFaceDown = false,
            isEmpty = false,
            diagnostic = "match-Six-Diamonds",
            trace = RecognitionTrace(suitTemplates = "D:0.88 H:0.70")
        )
        val enriched = TableauCascadeSupport.enrichGeometricFromRejectedRead(geometric, hit)
        assertEquals(Rank.Six, enriched.rank)
        assertTrue(enriched.suitAmbiguous)
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
    fun prefersGeometricForFourFiveConfusionAgainstBottomAnchor() {
        val bottomThree = Card(Rank.Three, Suit.Spades, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomThree, 1)
        val direct = Card(Rank.Five, Suit.Diamonds, faceUp = true, known = true, suitAmbiguous = true)
        assertEquals(Rank.Four, geometric.rank)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomThree,
                bottomReadConfidence = 0.90f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.86f,
                rankCountConsistent = false
            )
        )
    }

    @Test
    fun repairBottomAgainstLeadingCountRecoversJackUnderKingRun() {
        val leadingKing = Card(Rank.King, Suit.Hearts, faceUp = true, known = true)
        val bottomQueen = Card(Rank.Queen, Suit.Hearts, faceUp = true, known = true)
        val hit = RecognitionHit(
            card = bottomQueen,
            confidence = 0.88f,
            isFaceDown = false,
            isEmpty = false,
            diagnostic = "match-Queen-Hearts@0.88",
            trace = RecognitionTrace(suitTemplates = "H:0.90 D:0.70")
        )
        val repaired = TableauCascadeSupport.repairBottomAgainstLeadingCount(
            leading = leadingKing,
            geometricFaceUpCount = 3,
            bottom = bottomQueen,
            bottomConfidence = 0.88f,
            bottomHit = hit
        )
        assertEquals(Rank.Jack, repaired.rank)
        assertEquals(Suit.Hearts, repaired.suit)
        assertFalse(repaired.inferred)
    }

    @Test
    fun repairBottomAgainstLeadingCountSkipsNonAdjacent() {
        val leadingKing = Card(Rank.King, Suit.Hearts, faceUp = true, known = true)
        val bottomTen = Card(Rank.Ten, Suit.Hearts, faceUp = true, known = true)
        val hit = RecognitionHit(
            card = bottomTen,
            confidence = 0.88f,
            isFaceDown = false,
            isEmpty = false,
            diagnostic = "match-Ten-Hearts@0.88",
            trace = RecognitionTrace.EMPTY
        )
        val repaired = TableauCascadeSupport.repairBottomAgainstLeadingCount(
            leading = leadingKing,
            geometricFaceUpCount = 3,
            bottom = bottomTen,
            bottomConfidence = 0.88f,
            bottomHit = hit
        )
        assertEquals(Rank.Ten, repaired.rank)
    }

    @Test
    fun prefersGeometricForJackQueenAgainstBottomAnchor() {
        val bottomTen = Card(Rank.Ten, Suit.Hearts, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomTen, 1)
        val direct = Card(Rank.Queen, Suit.Spades, faceUp = true, known = true)
        assertEquals(Rank.Jack, geometric.rank)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomTen,
                bottomReadConfidence = 0.88f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.85f,
                rankCountConsistent = false
            )
        )
    }

    @Test
    fun prefersGeometricForTwoThreeConfusionAgainstBottomAnchor() {
        val bottomTwo = Card(Rank.Two, Suit.Spades, faceUp = true, known = true)
        val geomThree = TableauCascadeSupport.geometricCascadeCard(bottomTwo, 1)
        val directTwo = Card(Rank.Two, Suit.Hearts, faceUp = true, known = true)
        assertEquals(Rank.Three, geomThree.rank)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomTwo,
                bottomReadConfidence = 0.90f,
                geometric = geomThree,
                directCard = directTwo,
                directConfidence = 0.80f,
                rankCountConsistent = true
            )
        )
    }

    @Test
    fun prefersGeometricForSixSevenConfusionWhenDoublyAnchored() {
        val bottomFive = Card(Rank.Five, Suit.Diamonds, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomFive, 1)
        val direct = Card(Rank.Seven, Suit.Clubs, faceUp = true, known = true)
        assertEquals(Rank.Six, geometric.rank)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomFive,
                bottomReadConfidence = 0.90f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.80f,
                rankCountConsistent = true
            )
        )
    }

    @Test
    fun prefersGeometricForColorMismatchOnConsistentRun() {
        val bottomTwo = Card(Rank.Two, Suit.Hearts, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomTwo, 1)
        // Geometry: Three of black. Direct: Three of Diamonds (wrong color family).
        val direct = Card(Rank.Three, Suit.Diamonds, faceUp = true, known = true)
        assertEquals(Rank.Three, geometric.rank)
        assertTrue(geometric.suit.isRed != direct.suit.isRed)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomTwo,
                bottomReadConfidence = 0.90f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.83f,
                rankCountConsistent = true
            )
        )
    }

    @Test
    fun prefersGeometricForSameRankColorFlipWithoutRankCountLock() {
        // Unlocked color-only overrides were reverted after v1.4.54 — they
        // invented C↔S placeholders. Same-rank color flips need a consistent run.
        val bottomFive = Card(Rank.Five, Suit.Hearts, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomFive, 1)
        val direct = Card(Rank.Six, Suit.Diamonds, faceUp = true, known = true)
        assertEquals(Rank.Six, geometric.rank)
        assertTrue(!geometric.suit.isRed)
        assertFalse(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomFive,
                bottomReadConfidence = 0.88f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.84f,
                rankCountConsistent = false
            )
        )
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomFive,
                bottomReadConfidence = 0.88f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.84f,
                rankCountConsistent = true
            )
        )
    }

    @Test
    fun doesNotPreferTwoThreeGeometryFromAceBottomWithoutConsistentRun() {
        // Misread Ace bottom invents geometric Two; a correct Three direct read
        // must not be stolen when rank-count isn't locked.
        val bottomAce = Card(Rank.Ace, Suit.Spades, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomAce, 1)
        val direct = Card(Rank.Three, Suit.Hearts, faceUp = true, known = true)
        assertEquals(Rank.Two, geometric.rank)
        assertFalse(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomAce,
                bottomReadConfidence = 0.90f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.80f,
                rankCountConsistent = false
            )
        )
    }

    @Test
    fun prefersGeometricForEightNineWhenDoublyAnchored() {
        val bottomSeven = Card(Rank.Seven, Suit.Diamonds, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomSeven, 2)
        val direct = Card(Rank.Eight, Suit.Hearts, faceUp = true, known = true, suitAmbiguous = true)
        assertEquals(Rank.Nine, geometric.rank)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomSeven,
                bottomReadConfidence = 0.90f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.81f,
                rankCountConsistent = true
            )
        )
    }

    @Test
    fun prefersGeometricWhenAmbiguousRankDisagreesWithConsistentRun() {
        val bottomThree = Card(Rank.Three, Suit.Spades, faceUp = true, known = true)
        val geometric = TableauCascadeSupport.geometricCascadeCard(bottomThree, 5)
        val direct = Card(Rank.Nine, Suit.Hearts, faceUp = true, known = true, suitAmbiguous = true)
        assertEquals(Rank.Eight, geometric.rank)
        assertTrue(
            TableauCascadeSupport.prefersGeometricOverDirectRead(
                bottomCard = bottomThree,
                bottomReadConfidence = 0.90f,
                geometric = geometric,
                directCard = direct,
                directConfidence = 0.88f,
                rankCountConsistent = true
            )
        )
    }

    @Test
    fun repairIllegalBottomRecoversThreeUnderFour() {
        val above = Card(Rank.Four, Suit.Spades, faceUp = true, known = true)
        val bottom = Card(Rank.Eight, Suit.Diamonds, faceUp = true, known = true)
        val hit = RecognitionHit(
            card = bottom,
            confidence = 0.72f,
            isFaceDown = false,
            isEmpty = false,
            diagnostic = "match-Eight-Diamonds",
            trace = RecognitionTrace(suitTemplates = "D:0.88 H:0.70")
        )
        val repaired = TableauCascadeSupport.repairIllegalBottom(
            cardAbove = above,
            bottom = bottom,
            bottomConfidence = 0.72f,
            bottomHit = hit
        )
        assertEquals(Rank.Three, repaired.rank)
        assertEquals(Suit.Diamonds, repaired.suit)
        assertFalse(repaired.inferred)
    }

    @Test
    fun repairIllegalBottomKeepsLegalBottom() {
        val above = Card(Rank.Four, Suit.Spades, faceUp = true, known = true)
        val bottom = Card(Rank.Three, Suit.Diamonds, faceUp = true, known = true)
        val hit = RecognitionHit(
            card = bottom,
            confidence = 0.72f,
            isFaceDown = false,
            isEmpty = false,
            diagnostic = "match-Three-Diamonds"
        )
        val repaired = TableauCascadeSupport.repairIllegalBottom(
            cardAbove = above,
            bottom = bottom,
            bottomConfidence = 0.72f,
            bottomHit = hit
        )
        assertEquals(bottom, repaired)
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
