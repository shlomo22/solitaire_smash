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

    /**
     * A throwaway tableau card, varied per call, standing in for the kind of
     * unrelated board jitter (a tableau slot's read settling/flipping) that
     * generates a fresh confirmed GameState even while the waste card itself
     * hasn't changed - real device logs show this happening every few
     * hundred ms, and it's what lets a genuinely stable waste card reach its
     * own second confirmation.
     */
    private fun jitter(n: Int): List<List<Card>> =
        listOf(listOf(c(Rank.entries[n % 13], Suit.Hearts))) + List(6) { emptyList() }

    @Test
    fun firstSightingIsNeverTrustedEvenWhenConfirmedElsewhereAlready() {
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val queenA = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(1))
        val queenB = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(2))

        assertNull(tracker.onConfirmedTransition(empty, queenA))
        assertEquals(0, tracker.seenSinceProgress)
        assertNull(tracker.onConfirmedTransition(queenA, queenB))
        assertEquals(1, tracker.seenSinceProgress)
        assertFalse(tracker.isStuck)
    }

    @Test
    fun singleUnconfirmedSightingIsDiscardedWhenReplaced() {
        // A one-frame OCR/template hiccup (or a DeckConstraintPass
        // coincidence forcing the same final identity as an earlier,
        // equally unconfirmed sighting) must never get recorded just
        // because the raw id happens to match something seen before -
        // neither instance was ever confirmed by a second observation.
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val queenClubs1 = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(1))
        val fourHearts = board(waste = listOf(c(Rank.Four, Suit.Hearts)), tableau = jitter(2))
        val queenClubs2 = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(3))

        assertNull(tracker.onConfirmedTransition(empty, queenClubs1))
        assertNull(tracker.onConfirmedTransition(queenClubs1, fourHearts))
        assertNull(tracker.onConfirmedTransition(fourHearts, queenClubs2))
        assertFalse(tracker.isStuck)
        assertEquals(0, tracker.seenSinceProgress)
    }

    @Test
    fun realLogShapeWeakBlipCorrectedThenSameBlipRecurringDoesNotFalselyRepeat() {
        // Precise shape from a real pull (ed5ca730-analysis.log, v1.4.132):
        // a weak one-frame read (Four_Diamonds) is immediately superseded by
        // a different, confirmed card (Eight_Diamonds). Much later, a
        // genuinely new draw is misread as Four_Diamonds again, but only for
        // one frame - it must not trigger a repeat just because that id was
        // floated once, long ago, and never actually recorded.
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val fourDiamondsBlip = board(waste = listOf(c(Rank.Four, Suit.Diamonds)), tableau = jitter(1))
        val eightDiamonds1 = board(waste = listOf(c(Rank.Eight, Suit.Diamonds)), tableau = jitter(2))
        val eightDiamonds2 = board(waste = listOf(c(Rank.Eight, Suit.Diamonds)), tableau = jitter(3))
        val fourDiamondsAgain = board(waste = listOf(c(Rank.Four, Suit.Diamonds)), tableau = jitter(4))

        assertNull(tracker.onConfirmedTransition(empty, fourDiamondsBlip))
        assertNull(tracker.onConfirmedTransition(fourDiamondsBlip, eightDiamonds1))
        assertNull(tracker.onConfirmedTransition(eightDiamonds1, eightDiamonds2))
        assertEquals(1, tracker.seenSinceProgress)
        assertFalse(tracker.isStuck)

        val note = tracker.onConfirmedTransition(eightDiamonds2, fourDiamondsAgain)
        assertNull(note)
        assertFalse(tracker.isStuck)
        assertEquals(1, tracker.seenSinceProgress)
    }

    @Test
    fun genuineRepeatOnlyFlagsOnceTheReturningCardIsItselfConfirmedTwice() {
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val queen1a = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(1))
        val queen1b = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(2))
        val king1a = board(waste = listOf(c(Rank.King, Suit.Hearts)), tableau = jitter(3))
        val king1b = board(waste = listOf(c(Rank.King, Suit.Hearts)), tableau = jitter(4))
        val queen2a = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(5))
        val queen2b = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(6))

        assertNull(tracker.onConfirmedTransition(empty, queen1a))
        assertNull(tracker.onConfirmedTransition(queen1a, queen1b))
        assertEquals(1, tracker.seenSinceProgress)
        assertNull(tracker.onConfirmedTransition(queen1b, king1a))
        assertNull(tracker.onConfirmedTransition(king1a, king1b))
        assertEquals(2, tracker.seenSinceProgress)

        assertNull(tracker.onConfirmedTransition(king1b, queen2a))
        assertFalse("first sighting of the repeat must not flag yet", tracker.isStuck)

        val note = tracker.onConfirmedTransition(queen2a, queen2b)
        assertEquals("stuck-repeat=Queen_Clubs seen=2", note)
        assertTrue(tracker.isStuck)
    }

    @Test
    fun confirmedCardMustReconfirmAfterAGenuineRecycleGapBeforeRepeatFlags() {
        // The exact mechanism that would otherwise let a card confirmed
        // just before a real recycle skip its repeat-check the instant it
        // reappears: pendingConfirmed must not survive a genuinely empty
        // waste (mid-recycle), or the second sighting after the gap would
        // be silently treated as "already accounted for."
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val queenA = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(1))
        val queenB = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(2))
        assertNull(tracker.onConfirmedTransition(empty, queenA))
        assertNull(tracker.onConfirmedTransition(queenA, queenB))
        assertEquals(1, tracker.seenSinceProgress)

        val recycled = board(stock = listOf(c(Rank.Queen, Suit.Clubs, false)), waste = emptyList())
        assertNull(tracker.onConfirmedTransition(queenB, recycled))

        val backAround1 = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(3))
        val backAround2 = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(4))
        assertNull(tracker.onConfirmedTransition(recycled, backAround1))
        assertFalse("first sighting after the recycle gap must not flag yet", tracker.isStuck)

        val note = tracker.onConfirmedTransition(backAround1, backAround2)
        assertEquals("stuck-repeat=Queen_Clubs seen=1", note)
        assertTrue(tracker.isStuck)
    }

    @Test
    fun cardExposedByPlayIsSeededAndCaughtIfItLaterRepeats() {
        // Six of Diamonds is drawn on top of a covered Queen of Clubs - the
        // recognizer only ever reports the exposed front card, so Queen of
        // Clubs is never itself a "fresh draw". Playing Six of Diamonds away
        // exposes Queen of Clubs as the new front card; that exposure is
        // seeded as a candidate immediately, but - like any other candidate
        // - still needs its own second confirmation before counting.
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val afterDraw1 = board(
            waste = listOf(c(Rank.Queen, Suit.Clubs), c(Rank.Six, Suit.Diamonds)),
            tableau = jitter(1)
        )
        val afterDraw2 = board(
            waste = listOf(c(Rank.Queen, Suit.Clubs), c(Rank.Six, Suit.Diamonds)),
            tableau = jitter(2)
        )
        assertNull(tracker.onConfirmedTransition(empty, afterDraw1))
        assertNull(tracker.onConfirmedTransition(afterDraw1, afterDraw2))
        assertEquals(1, tracker.seenSinceProgress)

        val beforePlay = afterDraw2.copy(
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
        assertEquals(0, tracker.seenSinceProgress)

        val afterPlayJitter = afterPlay.copy(
            tableau = listOf(
                afterPlay.tableau[0],
                listOf(c(Rank.Nine, Suit.Hearts)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        assertNull(tracker.onConfirmedTransition(afterPlay, afterPlayJitter))
        assertEquals(1, tracker.seenSinceProgress)

        val recycled = board(stock = listOf(c(Rank.Queen, Suit.Clubs, false)), waste = emptyList())
        val backAround1 = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(3))
        val backAround2 = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(4))
        assertNull(tracker.onConfirmedTransition(afterPlayJitter, recycled))
        assertNull(tracker.onConfirmedTransition(recycled, backAround1))
        val note = tracker.onConfirmedTransition(backAround1, backAround2)
        assertEquals("stuck-repeat=Queen_Clubs seen=1", note)
        assertTrue(tracker.isStuck)
    }

    @Test
    fun wastePlayResetsTrackingAndClearsStuck() {
        val tracker = WasteCycleStuckTracker()
        val empty = board(waste = emptyList())
        val queen1a = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(1))
        val queen1b = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(2))
        val king1a = board(waste = listOf(c(Rank.King, Suit.Hearts)), tableau = jitter(3))
        val king1b = board(waste = listOf(c(Rank.King, Suit.Hearts)), tableau = jitter(4))
        val queen2a = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(5))
        val queen2b = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(6))
        tracker.onConfirmedTransition(empty, queen1a)
        tracker.onConfirmedTransition(queen1a, queen1b)
        tracker.onConfirmedTransition(queen1b, king1a)
        tracker.onConfirmedTransition(king1a, king1b)
        tracker.onConfirmedTransition(king1b, queen2a)
        tracker.onConfirmedTransition(queen2a, queen2b)
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
        // Queen of Clubs is seeded as the pending candidate again (it's now
        // the exposed card), but not yet confirmed - a play-reveal deserves
        // the same scrutiny as an ordinary draw.
        assertEquals(0, tracker.seenSinceProgress)
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
        val queen1a = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(1))
        val queen1b = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(2))
        val king1a = board(waste = listOf(c(Rank.King, Suit.Hearts)), tableau = jitter(3))
        val king1b = board(waste = listOf(c(Rank.King, Suit.Hearts)), tableau = jitter(4))
        val queen2a = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(5))
        val queen2b = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(6))
        tracker.onConfirmedTransition(empty, queen1a)
        tracker.onConfirmedTransition(queen1a, queen1b)
        tracker.onConfirmedTransition(queen1b, king1a)
        tracker.onConfirmedTransition(king1a, king1b)
        tracker.onConfirmedTransition(king1b, queen2a)
        assertEquals(
            "stuck-repeat=Queen_Clubs seen=2",
            tracker.onConfirmedTransition(queen2a, queen2b)
        )
        assertTrue(tracker.isStuck)

        // Another confirmed frame while still stuck (waste unchanged, some
        // other jitter): no repeated "just became stuck" note.
        val queen2c = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(0))
        assertNull(tracker.onConfirmedTransition(queen2b, queen2c))
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
        val queen1a = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(1))
        val queen1b = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(2))
        val king1a = board(waste = listOf(c(Rank.King, Suit.Hearts)), tableau = jitter(3))
        val king1b = board(waste = listOf(c(Rank.King, Suit.Hearts)), tableau = jitter(4))
        val queen2a = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(5))
        val queen2b = board(waste = listOf(c(Rank.Queen, Suit.Clubs)), tableau = jitter(6))
        tracker.onConfirmedTransition(empty, queen1a)
        tracker.onConfirmedTransition(queen1a, queen1b)
        tracker.onConfirmedTransition(queen1b, king1a)
        tracker.onConfirmedTransition(king1a, king1b)
        tracker.onConfirmedTransition(king1b, queen2a)
        tracker.onConfirmedTransition(queen2a, queen2b)
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
