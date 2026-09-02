package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WasteCycleStuckTrackerTest {
    private fun c(rank: Rank, suit: Suit, up: Boolean = true) = Card(rank, suit, up)

    private fun board(
        stock: List<Card> = emptyList(),
        waste: List<Card> = emptyList(),
        tableau: List<List<Card>> = List(7) { emptyList() },
        foundations: List<List<Card>> = List(4) { emptyList() }
    ) = GameState(
        tableau = tableau,
        foundations = foundations,
        stock = stock,
        waste = waste
    )

    @Test
    fun twoIdleRecyclesMarkStuck() {
        val tracker = WasteCycleStuckTracker()
        val beforeRecycle = board(
            stock = emptyList(),
            waste = listOf(c(Rank.Queen, Suit.Clubs))
        )
        val afterRecycle = board(
            stock = listOf(c(Rank.Queen, Suit.Clubs, false)),
            waste = emptyList()
        )

        assertEquals("idle-recycle=1", tracker.onConfirmedTransition(beforeRecycle, afterRecycle))
        assertFalse(tracker.isStuck)
        assertEquals("idle-recycle=2", tracker.onConfirmedTransition(beforeRecycle, afterRecycle))
        assertTrue(tracker.isStuck)
    }

    @Test
    fun wastePlayResetsIdleRecycles() {
        val tracker = WasteCycleStuckTracker()
        val beforeRecycle = board(
            stock = emptyList(),
            waste = listOf(c(Rank.Queen, Suit.Clubs))
        )
        val afterRecycle = board(
            stock = listOf(c(Rank.Queen, Suit.Clubs, false)),
            waste = emptyList()
        )
        tracker.onConfirmedTransition(beforeRecycle, afterRecycle)
        tracker.onConfirmedTransition(beforeRecycle, afterRecycle)
        assertTrue(tracker.isStuck)

        val beforePlay = board(
            stock = emptyList(),
            waste = listOf(c(Rank.Five, Suit.Hearts)),
            tableau = listOf(
                listOf(c(Rank.Six, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        val afterPlay = board(
            stock = emptyList(),
            waste = emptyList(),
            tableau = listOf(
                listOf(c(Rank.Six, Suit.Clubs), c(Rank.Five, Suit.Hearts)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        assertEquals("waste-play-reset", tracker.onConfirmedTransition(beforePlay, afterPlay))
        assertFalse(tracker.isStuck)
        assertEquals(0, tracker.idleRecycles)
    }

    @Test
    fun drawThroughStockIsNotRecycle() {
        val tracker = WasteCycleStuckTracker()
        val before = board(
            stock = listOf(c(Rank.Ace, Suit.Clubs, false)),
            waste = listOf(c(Rank.King, Suit.Spades))
        )
        val after = board(
            stock = emptyList(),
            waste = listOf(c(Rank.King, Suit.Spades), c(Rank.Ace, Suit.Clubs))
        )
        assertEquals(null, tracker.onConfirmedTransition(before, after))
        assertEquals(0, tracker.idleRecycles)
    }

    @Test
    fun looksLikeRecycleRequiresEmptyStockThenRefill() {
        assertTrue(
            WasteCycleStuckTracker.looksLikeRecycle(
                board(stock = emptyList(), waste = listOf(c(Rank.Two, Suit.Hearts))),
                board(stock = listOf(c(Rank.Two, Suit.Hearts, false)), waste = emptyList())
            )
        )
        assertFalse(
            WasteCycleStuckTracker.looksLikeRecycle(
                board(stock = listOf(c(Rank.Ace, Suit.Clubs, false)), waste = listOf(c(Rank.Two, Suit.Hearts))),
                board(stock = emptyList(), waste = listOf(c(Rank.Two, Suit.Hearts), c(Rank.Ace, Suit.Clubs)))
            )
        )
    }
}
