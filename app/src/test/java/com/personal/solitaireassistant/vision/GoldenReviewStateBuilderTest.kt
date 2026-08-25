package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenReviewStateBuilderTest {
    @Test
    fun fixingDuplicateMarksOriginallyFlaggedSlotResolved() {
        val duplicate = GoldenReviewStateBuilder.gameState(
            slots = listOf(
                slot("tableau:0", 0, Rank.King, Suit.Spades),
                slot("tableau:1", 0, Rank.King, Suit.Spades)
            ),
            truths = listOf(
                faceUp(Rank.King, Suit.Spades),
                faceUp(Rank.King, Suit.Spades)
            )
        )
        assertTrue(BoardRecognitionValidator.validate(duplicate).isNotEmpty())

        val fixed = GoldenReviewStateBuilder.gameState(
            slots = listOf(
                slot("tableau:0", 0, Rank.King, Suit.Spades),
                slot("tableau:1", 0, Rank.King, Suit.Spades)
            ),
            truths = listOf(
                faceUp(Rank.King, Suit.Spades),
                faceUp(Rank.King, Suit.Clubs)
            )
        )
        assertTrue(BoardRecognitionValidator.validate(fixed).isEmpty())

        val statuses = GoldenReviewStateBuilder.reviewStatuses(
            slots = listOf(
                slot("tableau:0", 0, Rank.King, Suit.Spades),
                slot("tableau:1", 0, Rank.King, Suit.Spades)
            ),
            truths = listOf(
                faceUp(Rank.King, Suit.Spades),
                faceUp(Rank.King, Suit.Clubs)
            ),
            originallyFlagged = setOf("tableau:0:0", "tableau:1:0")
        )

        assertTrue(statuses.getValue("tableau:0:0").resolved)
        assertTrue(statuses.getValue("tableau:1:0").resolved)
        assertTrue(statuses.getValue("tableau:0:0").stillBroken == null)
    }

    @Test
    fun unresolvedDuplicateStaysBroken() {
        val statuses = GoldenReviewStateBuilder.reviewStatuses(
            slots = listOf(
                slot("tableau:0", 0, Rank.King, Suit.Spades),
                slot("tableau:1", 0, Rank.King, Suit.Spades)
            ),
            truths = listOf(
                faceUp(Rank.King, Suit.Spades),
                faceUp(Rank.King, Suit.Spades)
            ),
            originallyFlagged = setOf("tableau:1:0")
        )

        val status = statuses.getValue("tableau:1:0")
        assertFalse(status.resolved)
        assertEquals("duplicate King_Spades", status.stillBroken?.reason)
    }

    private fun slot(
        pile: String,
        index: Int,
        rank: Rank,
        suit: Suit
    ): RecognizedSlot = RecognizedSlot(
        pile = parsePileRefKey(pile),
        index = index,
        bounds = com.personal.solitaireassistant.game.BoardRegion(0f, 0f, 1f, 1f),
        engine = SlotGuess(SlotKind.FaceUp, rank, suit),
        confidence = 0.8f,
        diagnostic = "test"
    )

    private fun faceUp(rank: Rank, suit: Suit): SlotGuess =
        SlotGuess(SlotKind.FaceUp, rank, suit)
}
