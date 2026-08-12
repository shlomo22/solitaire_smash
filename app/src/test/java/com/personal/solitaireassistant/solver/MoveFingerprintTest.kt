package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveFingerprintTest {
    private fun c(rank: Rank, suit: Suit) = Card(rank, suit)

    @Test
    fun fingerprintUsesCardIdsNotColumns() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Three, Suit.Spades)),
                listOf(c(Rank.Six, Suit.Hearts)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        assertEquals(
            "Three_Spades->Six_Hearts",
            MoveFingerprint.of(state, Move.TableauToTableau(0, 0, 1))
        )
    }
}
