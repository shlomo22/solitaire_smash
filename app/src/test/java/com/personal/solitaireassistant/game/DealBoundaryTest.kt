package com.personal.solitaireassistant.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DealBoundaryTest {
    private fun c(rank: Rank, suit: Suit, up: Boolean = true, known: Boolean = true) =
        Card(rank, suit, faceUp = up, known = known)

    private fun emptyFoundations() = List(4) { emptyList<Card>() }

    private fun freshDeal(): GameState = GameState(
        tableau = listOf(
            listOf(c(Rank.King, Suit.Hearts)),
            listOf(c(Rank.Ace, Suit.Clubs, false), c(Rank.Queen, Suit.Spades)),
            listOf(
                c(Rank.Two, Suit.Clubs, false),
                c(Rank.Three, Suit.Clubs, false),
                c(Rank.Jack, Suit.Diamonds)
            ),
            listOf(
                c(Rank.Four, Suit.Clubs, false),
                c(Rank.Five, Suit.Clubs, false),
                c(Rank.Six, Suit.Clubs, false),
                c(Rank.Ten, Suit.Hearts)
            ),
            listOf(
                c(Rank.Seven, Suit.Clubs, false),
                c(Rank.Eight, Suit.Clubs, false),
                c(Rank.Nine, Suit.Clubs, false),
                c(Rank.Ten, Suit.Clubs, false),
                c(Rank.Nine, Suit.Hearts)
            ),
            listOf(
                c(Rank.Ace, Suit.Spades, false),
                c(Rank.Two, Suit.Spades, false),
                c(Rank.Three, Suit.Spades, false),
                c(Rank.Four, Suit.Spades, false),
                c(Rank.Five, Suit.Spades, false),
                c(Rank.Eight, Suit.Hearts)
            ),
            listOf(
                c(Rank.Six, Suit.Spades, false),
                c(Rank.Seven, Suit.Spades, false),
                c(Rank.Eight, Suit.Spades, false),
                c(Rank.Nine, Suit.Spades, false),
                c(Rank.Ten, Suit.Spades, false),
                c(Rank.Jack, Suit.Spades, false),
                c(Rank.Seven, Suit.Hearts)
            )
        ),
        foundations = emptyFoundations(),
        stock = emptyList(),
        waste = emptyList()
    )

    private fun midGame(): GameState = GameState(
        tableau = listOf(
            listOf(
                c(Rank.King, Suit.Hearts),
                c(Rank.Queen, Suit.Spades),
                c(Rank.Jack, Suit.Hearts),
                c(Rank.Ten, Suit.Clubs),
                c(Rank.Nine, Suit.Diamonds)
            ),
            listOf(c(Rank.Ace, Suit.Clubs, false), c(Rank.Seven, Suit.Diamonds)),
            listOf(
                c(Rank.Two, Suit.Clubs, false),
                c(Rank.Three, Suit.Clubs, false),
                c(Rank.Nine, Suit.Clubs)
            ),
            listOf(
                c(Rank.Four, Suit.Clubs, false),
                c(Rank.Queen, Suit.Hearts),
                c(Rank.Jack, Suit.Spades),
                c(Rank.Ten, Suit.Hearts),
                c(Rank.Nine, Suit.Spades)
            ),
            listOf(c(Rank.Five, Suit.Clubs, false), c(Rank.Six, Suit.Hearts)),
            listOf(
                c(Rank.Six, Suit.Clubs, false),
                c(Rank.Seven, Suit.Clubs, false),
                c(Rank.Eight, Suit.Hearts),
                c(Rank.Seven, Suit.Clubs),
                c(Rank.Six, Suit.Diamonds),
                c(Rank.Five, Suit.Spades),
                c(Rank.Four, Suit.Diamonds)
            ),
            listOf(
                c(Rank.Eight, Suit.Clubs, false),
                c(Rank.Nine, Suit.Hearts, false),
                c(Rank.Queen, Suit.Diamonds)
            )
        ),
        foundations = listOf(
            listOf(c(Rank.Ace, Suit.Diamonds), c(Rank.Two, Suit.Diamonds)),
            listOf(c(Rank.Ace, Suit.Spades)),
            emptyList(),
            emptyList()
        ),
        stock = emptyList(),
        waste = listOf(c(Rank.Nine, Suit.Hearts))
    )

    @Test
    fun freshDealLayoutIsDetected() {
        assertTrue(DealBoundary.looksLikeFreshDeal(freshDeal()))
        assertFalse(DealBoundary.looksLikeFreshDeal(midGame()))
    }

    @Test
    fun firstObservationOfFreshDealIsANewGame() {
        assertEquals("fresh-layout", DealBoundary.newGameReason(null, freshDeal()))
    }

    @Test
    fun sittingOnTheSameFreshDealIsNotANewGame() {
        val deal = freshDeal()
        assertNull(DealBoundary.newGameReason(deal, deal))
    }

    @Test
    fun overlayStartingMidGameIsNotANewGame() {
        assertNull(DealBoundary.newGameReason(null, midGame()))
    }

    @Test
    fun hiddenCountJumpMarksANewDeal() {
        val reason = DealBoundary.newGameReason(midGame(), freshDeal())
        assertEquals("fresh-layout", reason)
    }

    @Test
    fun hiddenCountJumpWithoutFreshLayoutStillMarksANewDeal() {
        val laterDeal = midGame().copy(
            tableau = freshDeal().tableau,
            foundations = emptyFoundations(),
            waste = listOf(c(Rank.Ace, Suit.Hearts))
        )
        val reason = DealBoundary.newGameReason(midGame(), laterDeal)
        assertTrue(reason!!.startsWith("hidden-jump+"))
    }

    @Test
    fun singleHiddenFlipIsNotANewGame() {
        val before = midGame()
        val undone = before.copy(
            tableau = before.tableau.mapIndexed { i, cards ->
                if (i != 5) {
                    cards
                } else {
                    cards.dropLast(1).toMutableList().also { remaining ->
                        remaining[remaining.lastIndex] =
                            remaining.last().copy(faceUp = false)
                    }
                }
            }
        )
        assertEquals(1, undone.hiddenTableauCount() - before.hiddenTableauCount())
        assertNull(DealBoundary.newGameReason(before, undone))
    }

    @Test
    fun foundationDropOfTwoMarksANewGame() {
        val emptyFoundations = midGame().copy(foundations = emptyFoundations())
        assertEquals("foundation-drop-3", DealBoundary.newGameReason(midGame(), emptyFoundations))
    }

    @Test
    fun pullingOneFoundationCardIsNotANewGame() {
        val pulled = midGame().copy(
            foundations = listOf(
                midGame().foundations[0],
                emptyList(),
                emptyList(),
                emptyList()
            )
        )
        assertNull(DealBoundary.newGameReason(midGame(), pulled))
    }

    @Test
    fun disjointKnownCardsMarkANewGame() {
        val otherGame = GameState(
            tableau = listOf(
                listOf(
                    c(Rank.King, Suit.Clubs),
                    c(Rank.Queen, Suit.Hearts),
                    c(Rank.Jack, Suit.Clubs),
                    c(Rank.Ten, Suit.Diamonds),
                    c(Rank.Nine, Suit.Hearts)
                ),
                listOf(c(Rank.Ace, Suit.Hearts)),
                listOf(c(Rank.Two, Suit.Hearts)),
                listOf(c(Rank.Three, Suit.Hearts)),
                listOf(c(Rank.Four, Suit.Hearts)),
                listOf(c(Rank.Five, Suit.Hearts)),
                listOf(c(Rank.Six, Suit.Hearts))
            ),
            foundations = emptyFoundations(),
            stock = emptyList(),
            waste = listOf(c(Rank.Seven, Suit.Hearts))
        )
        assertEquals("known-set-turnover", DealBoundary.newGameReason(midGame(), otherGame))
    }

    @Test
    fun movingATableauStackIsTheSameGame() {
        val stacked = midGame()
        val from = stacked.tableau[5]
        val start = from.indexOfFirst { it.rank == Rank.Eight && it.suit == Suit.Hearts }
        val moved = from.subList(start, from.size)
        val after = stacked.copy(
            tableau = stacked.tableau.mapIndexed { i, col ->
                when (i) {
                    5 -> from.take(start)
                    2 -> stacked.tableau[2] + moved
                    else -> col
                }
            }
        )
        assertNull(DealBoundary.newGameReason(stacked, after))
    }
}
