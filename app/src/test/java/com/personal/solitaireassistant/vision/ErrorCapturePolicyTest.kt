package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ErrorCapturePolicyTest {
    @Test
    fun finalViolationsWinWhenBothFinalAndRawHaveErrors() {
        val duplicate = card(Rank.King, Suit.Spades)
        val duplicateState = gameState(
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

        val decision = ErrorCapturePolicy.decide(
            finalState = duplicateState,
            preConstraintState = duplicateState,
            captureRawReadErrors = true
        )

        requireNotNull(decision)
        assertEquals(ErrorCapturePolicy.VIOLATION_SOURCE_FINAL, decision.violationSource)
    }

    @Test
    fun rawViolationsCapturedWhenFinalStateIsCleanAndSettingEnabled() {
        val duplicate = card(Rank.King, Suit.Spades)
        val rawState = gameState(
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
        val finalState = gameState(
            tableau = listOf(
                listOf(card(Rank.King, Suit.Spades)),
                listOf(card(Rank.King, Suit.Clubs)),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn()
            )
        )

        val decision = ErrorCapturePolicy.decide(
            finalState = finalState,
            preConstraintState = rawState,
            captureRawReadErrors = true
        )

        requireNotNull(decision)
        assertEquals(ErrorCapturePolicy.VIOLATION_SOURCE_RAW, decision.violationSource)
        assertEquals(1, decision.violations.size)
    }

    @Test
    fun rawViolationsIgnoredWhenSettingDisabled() {
        val duplicate = card(Rank.King, Suit.Spades)
        val rawState = gameState(
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
        val finalState = gameState(
            tableau = listOf(
                listOf(card(Rank.King, Suit.Spades)),
                listOf(card(Rank.King, Suit.Clubs)),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn(),
                emptyColumn()
            )
        )

        assertNull(
            ErrorCapturePolicy.decide(
                finalState = finalState,
                preConstraintState = rawState,
                captureRawReadErrors = false
            )
        )
    }

    private fun gameState(tableau: List<List<Card>>): GameState = GameState(
        tableau = tableau,
        foundations = List(4) { emptyList() },
        stock = emptyList(),
        waste = emptyList()
    )

    private fun emptyColumn(): List<Card> = emptyList()

    private fun card(rank: Rank, suit: Suit): Card =
        Card(rank = rank, suit = suit, faceUp = true, known = true)
}
