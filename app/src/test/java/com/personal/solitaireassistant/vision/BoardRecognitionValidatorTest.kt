package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardRecognitionValidatorTest {
    @Test
    fun validBoardHasNoViolations() {
        val state = gameState(
            tableau = listOf(
                listOf(faceDown(), card(Rank.King, Suit.Spades)),
                listOf(faceDown(), card(Rank.Queen, Suit.Hearts), card(Rank.Jack, Suit.Spades)),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn()
            ),
            foundations = listOf(
                listOf(card(Rank.Ace, Suit.Hearts)),
                emptyList(),
                emptyList(),
                emptyList()
            ),
            waste = listOf(card(Rank.Five, Suit.Diamonds))
        )
        assertTrue(BoardRecognitionValidator.validate(state).isEmpty())
    }

    @Test
    fun duplicateKnownCardsAreReported() {
        val duplicate = card(Rank.King, Suit.Spades)
        val state = gameState(
            tableau = listOf(
                listOf(duplicate),
                listOf(duplicate),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn()
            )
        )
        val violations = BoardRecognitionValidator.validate(state)
        assertEquals(1, violations.size)
        val duplicateViolation = violations.single() as RecognitionViolation.DuplicateCard
        assertEquals("King_Spades", duplicateViolation.cardId)
        assertEquals(listOf("tableau:0:0", "tableau:1:0"), duplicateViolation.locations)
    }

    @Test
    fun unknownCardsDoNotTriggerDuplicateFalsePositive() {
        val unknown = Card(
            rank = Rank.Ace,
            suit = Suit.Hearts,
            faceUp = true,
            known = false
        )
        val state = gameState(
            tableau = listOf(
                listOf(unknown),
                listOf(unknown),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn()
            )
        )
        assertTrue(
            BoardRecognitionValidator.validate(state).none { it is RecognitionViolation.DuplicateCard }
        )
    }

    @Test
    fun validFiveClubsFourHeartsRunHasNoCascadeBreak() {
        val state = gameState(
            tableau = listOf(
                listOf(
                    faceDown(),
                    card(Rank.Five, Suit.Clubs),
                    card(Rank.Four, Suit.Hearts)
                ),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn()
            )
        )
        assertTrue(
            BoardRecognitionValidator.validate(state).none { it is RecognitionViolation.CascadeBreak }
        )
    }

    @Test
    fun cascadeBreakIsReportedForInvalidPair() {
        val state = gameState(
            tableau = listOf(
                listOf(
                    faceDown(),
                    card(Rank.Ten, Suit.Spades),
                    card(Rank.Queen, Suit.Hearts)
                ),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn()
            )
        )
        val violations = BoardRecognitionValidator.validate(state)
        assertEquals(1, violations.size)
        val breakViolation = violations.single() as RecognitionViolation.CascadeBreak
        assertEquals("tableau:0", breakViolation.pile)
        assertEquals(1, breakViolation.lowerIndex)
        assertEquals(2, breakViolation.upperIndex)
        assertEquals("Ten_Spades", breakViolation.lowerCard)
        assertEquals("Queen_Hearts", breakViolation.upperCard)
    }

    @Test
    fun inferredCardsStillCheckedForCascadeBreak() {
        val state = gameState(
            tableau = listOf(
                listOf(
                    card(Rank.King, Suit.Clubs, inferred = true),
                    card(Rank.Queen, Suit.Clubs, inferred = true)
                ),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn()
            )
        )
        val violations = BoardRecognitionValidator.validate(state)
        assertEquals(1, violations.filterIsInstance<RecognitionViolation.CascadeBreak>().size)
    }

    @Test
    fun unknownPairInCascadeIsSkipped() {
        val state = gameState(
            tableau = listOf(
                listOf(
                    Card(Rank.Ace, Suit.Spades, faceUp = true, known = false),
                    card(Rank.King, Suit.Hearts)
                ),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn()
            )
        )
        assertTrue(BoardRecognitionValidator.validate(state).isEmpty())
    }

    private fun gameState(
        tableau: List<List<Card>>,
        foundations: List<List<Card>> = List(4) { emptyList() },
        waste: List<Card> = emptyList(),
        stock: List<Card> = emptyList()
    ): GameState = GameState(
        tableau = tableau,
        foundations = foundations,
        stock = stock,
        waste = waste
    )

    private fun emptyColumn(): List<Card> = emptyList()

    private fun card(rank: Rank, suit: Suit, inferred: Boolean = false): Card =
        Card(rank = rank, suit = suit, faceUp = true, known = true, inferred = inferred)

    private fun faceDown(): Card =
        Card(rank = Rank.Ace, suit = Suit.Spades, faceUp = false, known = false)
}
