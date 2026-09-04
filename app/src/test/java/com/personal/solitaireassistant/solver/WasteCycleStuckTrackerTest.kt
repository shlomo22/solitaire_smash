package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun sameWasteTopReturningMarksStuckImmediately() {
        // A full lap through the stock: Queen shows up as waste top, then a
        // different card, then Queen comes back around with nothing having
        // left waste in between - stuck the instant that repeat is seen, not
        // after a second full recycle.
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val start = board(waste = listOf(c(Rank.Queen, Suit.Clubs)))
        val next = board(waste = listOf(c(Rank.Queen, Suit.Clubs), c(Rank.Four, Suit.Hearts)))
        val recycled = board(stock = listOf(c(Rank.Queen, Suit.Clubs, false)), waste = emptyList())
        val backAround = board(waste = listOf(c(Rank.Queen, Suit.Clubs)))

        assertNull(tracker.onConfirmedTransition(empty, start))
        assertFalse(tracker.isStuck)
        assertNull(tracker.onConfirmedTransition(start, next))
        assertFalse(tracker.isStuck)
        assertNull(tracker.onConfirmedTransition(next, recycled))
        assertFalse(tracker.isStuck)

        val note = tracker.onConfirmedTransition(recycled, backAround)
        assertEquals("stuck-repeat=Queen_Clubs seen=2", note)
        assertTrue(tracker.isStuck)
    }

    @Test
    fun cardExposedByPlayIsTrackedAndLaterCaughtIfItRepeats() {
        // Six of Diamonds is drawn on top of a covered Queen of Clubs - the
        // recognizer only ever reports the exposed front card, so Queen of
        // Clubs is never itself "seen" as a fresh draw. Playing Six of
        // Diamonds away exposes Queen of Clubs as the new front card; that
        // exposure must be tracked immediately (not only on the next actual
        // draw), or a later repeat of it would be silently missed.
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val afterDraw = board(waste = listOf(c(Rank.Queen, Suit.Clubs), c(Rank.Six, Suit.Diamonds)))
        assertNull(tracker.onConfirmedTransition(empty, afterDraw))
        assertEquals(1, tracker.seenSinceProgress)

        val beforePlay = afterDraw.copy(
            tableau = listOf(
                listOf(c(Rank.Seven, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        val afterPlay = board(
            waste = listOf(c(Rank.Queen, Suit.Clubs)),
            tableau = listOf(
                listOf(c(Rank.Seven, Suit.Clubs), c(Rank.Six, Suit.Diamonds)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        assertEquals("waste-play-reset", tracker.onConfirmedTransition(beforePlay, afterPlay))
        assertFalse(tracker.isStuck)
        // Queen of Clubs (newly exposed by the play, never a fresh draw) is
        // already tracked - not forgotten.
        assertEquals(1, tracker.seenSinceProgress)

        val recycled = board(stock = listOf(c(Rank.Queen, Suit.Clubs, false)), waste = emptyList())
        val backAround = board(waste = listOf(c(Rank.Queen, Suit.Clubs)))
        assertNull(tracker.onConfirmedTransition(afterPlay, recycled))
        val note = tracker.onConfirmedTransition(recycled, backAround)
        assertEquals("stuck-repeat=Queen_Clubs seen=1", note)
        assertTrue(tracker.isStuck)
    }

    @Test
    fun doesNotFlagStuckOnFirstSightingOfEachCard() {
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val a = board(waste = listOf(c(Rank.Two, Suit.Hearts)))
        val b = board(waste = listOf(c(Rank.Two, Suit.Hearts), c(Rank.Nine, Suit.Clubs)))
        val cState = board(
            waste = listOf(c(Rank.Two, Suit.Hearts), c(Rank.Nine, Suit.Clubs), c(Rank.King, Suit.Diamonds))
        )

        assertNull(tracker.onConfirmedTransition(empty, a))
        assertNull(tracker.onConfirmedTransition(a, b))
        assertFalse(tracker.isStuck)
        assertNull(tracker.onConfirmedTransition(b, cState))
        assertFalse(tracker.isStuck)
        assertEquals(3, tracker.seenSinceProgress)
    }

    @Test
    fun wastePlayResetsTrackingAndClearsStuck() {
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val start = board(waste = listOf(c(Rank.Queen, Suit.Clubs)))
        val recycled = board(stock = listOf(c(Rank.Queen, Suit.Clubs, false)), waste = emptyList())
        val backAround = board(waste = listOf(c(Rank.Queen, Suit.Clubs)))
        tracker.onConfirmedTransition(empty, start)
        tracker.onConfirmedTransition(start, recycled)
        tracker.onConfirmedTransition(recycled, backAround)
        assertTrue(tracker.isStuck)

        val beforePlay = board(
            waste = listOf(c(Rank.Queen, Suit.Clubs), c(Rank.Five, Suit.Hearts)),
            tableau = listOf(
                listOf(c(Rank.Six, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        val afterPlay = board(
            waste = listOf(c(Rank.Queen, Suit.Clubs)),
            tableau = listOf(
                listOf(c(Rank.Six, Suit.Clubs), c(Rank.Five, Suit.Hearts)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        assertEquals("waste-play-reset(was-stuck)", tracker.onConfirmedTransition(beforePlay, afterPlay))
        assertFalse(tracker.isStuck)
        // The newly exposed Queen of Clubs (now the front of the fan again)
        // is tracked immediately, not forgotten just because it happens to
        // be the same card that was stuck a moment ago.
        assertEquals(1, tracker.seenSinceProgress)
    }

    @Test
    fun wastePlayWithNothingToResetReportsNoChange() {
        val tracker = WasteCycleStuckTracker()
        val beforePlay = board(
            waste = listOf(c(Rank.Five, Suit.Hearts)),
            tableau = listOf(
                listOf(c(Rank.Six, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        val afterPlay = board(
            waste = emptyList(),
            tableau = listOf(
                listOf(c(Rank.Six, Suit.Clubs), c(Rank.Five, Suit.Hearts)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        // Nothing was ever tracked yet - a routine early-game waste play
        // shouldn't spam a reset note.
        assertNull(tracker.onConfirmedTransition(beforePlay, afterPlay))
        assertFalse(tracker.isStuck)
    }

    @Test
    fun stuckStaysTrueAndDoesNotReemitNoteEveryFrame() {
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val start = board(waste = listOf(c(Rank.Queen, Suit.Clubs)))
        val recycled = board(stock = listOf(c(Rank.Queen, Suit.Clubs, false)), waste = emptyList())
        val backAround = board(waste = listOf(c(Rank.Queen, Suit.Clubs)))
        tracker.onConfirmedTransition(empty, start)
        tracker.onConfirmedTransition(start, recycled)
        assertEquals("stuck-repeat=Queen_Clubs seen=1", tracker.onConfirmedTransition(recycled, backAround))
        assertTrue(tracker.isStuck)

        // Another no-progress lap while already stuck: still stuck, but no
        // repeated "just became stuck" note every time it recurs.
        val recycledAgain = board(stock = listOf(c(Rank.Queen, Suit.Clubs, false)), waste = emptyList())
        val backAroundAgain = board(waste = listOf(c(Rank.Queen, Suit.Clubs)))
        assertNull(tracker.onConfirmedTransition(backAround, recycledAgain))
        assertNull(tracker.onConfirmedTransition(recycledAgain, backAroundAgain))
        assertTrue(tracker.isStuck)
    }

    @Test
    fun unknownWasteTopCardsAreIgnored() {
        // Two unrecognized red cards would otherwise share the same
        // color-only id ("U_R") and falsely look like a repeat.
        val tracker = WasteCycleStuckTracker()
        val a = board(waste = listOf(Card(Rank.Ace, Suit.Hearts, faceUp = true, known = false)))
        val b = board(
            waste = listOf(
                Card(Rank.Ace, Suit.Hearts, faceUp = true, known = false),
                Card(Rank.Two, Suit.Diamonds, faceUp = true, known = false)
            )
        )
        assertNull(tracker.onConfirmedTransition(a, b))
        assertFalse(tracker.isStuck)
        assertEquals(0, tracker.seenSinceProgress)
    }

    @Test
    fun resetClearsTrackingAndStuckFlag() {
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val start = board(waste = listOf(c(Rank.Queen, Suit.Clubs)))
        val recycled = board(stock = listOf(c(Rank.Queen, Suit.Clubs, false)), waste = emptyList())
        val backAround = board(waste = listOf(c(Rank.Queen, Suit.Clubs)))
        tracker.onConfirmedTransition(empty, start)
        tracker.onConfirmedTransition(start, recycled)
        tracker.onConfirmedTransition(recycled, backAround)
        assertTrue(tracker.isStuck)

        tracker.reset()
        assertFalse(tracker.isStuck)
        assertEquals(0, tracker.seenSinceProgress)
    }

    @Test
    fun wastePlayedToBoardDetectsTableauAndFoundationDestinations() {
        val wasteTop = c(Rank.Five, Suit.Hearts)
        val beforeTableau = board(
            waste = listOf(wasteTop),
            tableau = listOf(
                listOf(c(Rank.Six, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        val afterTableau = board(
            waste = emptyList(),
            tableau = listOf(
                listOf(c(Rank.Six, Suit.Clubs), wasteTop),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        assertTrue(WasteCycleStuckTracker.wastePlayedToBoard(beforeTableau, afterTableau))

        val beforeFoundation = board(
            waste = listOf(c(Rank.Two, Suit.Spades)),
            foundations = listOf(listOf(c(Rank.Ace, Suit.Spades)), emptyList(), emptyList(), emptyList())
        )
        val afterFoundation = board(
            waste = emptyList(),
            foundations = listOf(
                listOf(c(Rank.Ace, Suit.Spades), c(Rank.Two, Suit.Spades)),
                emptyList(), emptyList(), emptyList()
            )
        )
        assertTrue(WasteCycleStuckTracker.wastePlayedToBoard(beforeFoundation, afterFoundation))

        val beforeDraw = board(waste = listOf(c(Rank.Nine, Suit.Clubs)))
        val afterDraw = board(waste = listOf(c(Rank.Nine, Suit.Clubs), c(Rank.Three, Suit.Diamonds)))
        assertFalse(WasteCycleStuckTracker.wastePlayedToBoard(beforeDraw, afterDraw))
    }
}
