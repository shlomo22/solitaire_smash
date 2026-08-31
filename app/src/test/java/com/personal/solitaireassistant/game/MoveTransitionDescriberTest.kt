package com.personal.solitaireassistant.game

import org.junit.Assert.assertEquals
import org.junit.Test

class MoveTransitionDescriberTest {
    private fun c(rank: Rank, suit: Suit, up: Boolean = true, known: Boolean = true, ambiguous: Boolean = false) =
        Card(rank, suit, faceUp = up, known = known, suitAmbiguous = ambiguous)

    private fun state(
        tableau: List<List<Card>> = List(7) { emptyList() },
        foundations: List<List<Card>> = List(4) { emptyList() },
        stock: List<Card> = emptyList(),
        waste: List<Card> = emptyList()
    ) = GameState(tableau = tableau, foundations = foundations, stock = stock, waste = waste)

    @Test
    fun nullPreviousIsOpeningDeal() {
        assertEquals("deal: opening layout", MoveTransitionDescriber.describe(null, state()))
    }

    @Test
    fun identicalStatesReportNoChange() {
        val s = state(waste = listOf(c(Rank.Five, Suit.Hearts)))
        assertEquals("no change", MoveTransitionDescriber.describe(s, s))
    }

    @Test
    fun stockDrawIsReportedAsDraw() {
        val previous = state(waste = listOf(c(Rank.Five, Suit.Hearts)))
        val current = state(waste = listOf(c(Rank.Nine, Suit.Clubs)))
        assertEquals("draw: Nine_Clubs", MoveTransitionDescriber.describe(previous, current))
    }

    @Test
    fun aceFromWasteToEmptyFoundationIsReported() {
        val previous = state(waste = listOf(c(Rank.Ace, Suit.Diamonds)))
        val current = state(
            foundations = listOf(emptyList(), emptyList(), emptyList(), listOf(c(Rank.Ace, Suit.Diamonds))),
            waste = emptyList()
        )
        assertEquals(
            "Ace_Diamonds: waste -> foundation3",
            MoveTransitionDescriber.describe(previous, current)
        )
    }

    @Test
    fun foundationGainFromTableauIsReported() {
        val previous = state(
            tableau = listOf(listOf(c(Rank.Four, Suit.Hearts))) + List(6) { emptyList() },
            foundations = listOf(emptyList(), listOf(c(Rank.Three, Suit.Hearts)), emptyList(), emptyList())
        )
        val current = state(
            tableau = List(7) { emptyList() },
            foundations = listOf(emptyList(), listOf(c(Rank.Three, Suit.Hearts), c(Rank.Four, Suit.Hearts)), emptyList(), emptyList())
        )
        assertEquals(
            "Four_Hearts: tableau0 -> foundation1",
            MoveTransitionDescriber.describe(previous, current)
        )
    }

    @Test
    fun ambiguousSuitCardIsNotTreatedAsAFreshFoundationGain() {
        // The recognizer flagged the new foundation top as suit-ambiguous -
        // don't confidently claim a specific card just moved there.
        val previous = state(foundations = listOf(emptyList(), emptyList(), emptyList(), emptyList()))
        val current = state(
            foundations = listOf(emptyList(), emptyList(), emptyList(), listOf(c(Rank.Ace, Suit.Diamonds, ambiguous = true)))
        )
        assertEquals("state changed (see snapshot)", MoveTransitionDescriber.describe(previous, current))
    }

    @Test
    fun singleCardTableauToTableauMoveIsReported() {
        val previous = state(
            tableau = listOf(
                listOf(c(Rank.Six, Suit.Hearts), c(Rank.Five, Suit.Clubs)),
                listOf(c(Rank.Six, Suit.Diamonds))
            ) + List(5) { emptyList() }
        )
        val current = state(
            tableau = listOf(
                listOf(c(Rank.Six, Suit.Hearts)),
                listOf(c(Rank.Six, Suit.Diamonds), c(Rank.Five, Suit.Clubs))
            ) + List(5) { emptyList() }
        )
        assertEquals(
            "Five_Clubs: tableau0 -> tableau1",
            MoveTransitionDescriber.describe(previous, current)
        )
    }

    @Test
    fun wasteToTableauMoveIsReported() {
        val previous = state(
            tableau = listOf(listOf(c(Rank.Six, Suit.Hearts))) + List(6) { emptyList() },
            waste = listOf(c(Rank.Five, Suit.Clubs))
        )
        val current = state(
            tableau = listOf(listOf(c(Rank.Six, Suit.Hearts), c(Rank.Five, Suit.Clubs))) + List(6) { emptyList() },
            waste = emptyList()
        )
        assertEquals(
            "Five_Clubs: waste -> tableau0",
            MoveTransitionDescriber.describe(previous, current)
        )
    }

    @Test
    fun multiCardRunMoveReportsCountWithoutGuessingSourceMidRun() {
        val previous = state(
            tableau = listOf(
                listOf(c(Rank.Eight, Suit.Spades), c(Rank.Seven, Suit.Hearts), c(Rank.Six, Suit.Clubs)),
                listOf(c(Rank.Nine, Suit.Diamonds))
            ) + List(5) { emptyList() }
        )
        val current = state(
            tableau = listOf(
                listOf(c(Rank.Eight, Suit.Spades)),
                listOf(
                    c(Rank.Nine, Suit.Diamonds),
                    c(Rank.Seven, Suit.Hearts),
                    c(Rank.Six, Suit.Clubs)
                )
            ) + List(5) { emptyList() }
        )
        assertEquals(
            "Six_Clubs (+1 more): tableau0 -> tableau1",
            MoveTransitionDescriber.describe(previous, current)
        )
    }

    @Test
    fun revealIsReportedDistinctFromAMove() {
        val previous = state(
            tableau = listOf(
                listOf(c(Rank.King, Suit.Hearts, up = false, known = false), c(Rank.Queen, Suit.Spades))
            ) + List(6) { emptyList() }
        )
        val current = state(
            tableau = listOf(
                listOf(c(Rank.King, Suit.Hearts), c(Rank.Queen, Suit.Spades))
            ) + List(6) { emptyList() }
        )
        assertEquals(
            "reveal: tableau0 -> King_Hearts",
            MoveTransitionDescriber.describe(previous, current)
        )
    }

    @Test
    fun multipleSimultaneousEventsAreJoined() {
        val previous = state(
            tableau = listOf(
                listOf(c(Rank.Four, Suit.Hearts)),
                listOf(c(Rank.King, Suit.Hearts, up = false, known = false), c(Rank.Queen, Suit.Spades))
            ) + List(5) { emptyList() },
            foundations = listOf(emptyList(), listOf(c(Rank.Three, Suit.Hearts)), emptyList(), emptyList())
        )
        val current = state(
            tableau = listOf(
                emptyList(),
                listOf(c(Rank.King, Suit.Hearts), c(Rank.Queen, Suit.Spades))
            ) + List(5) { emptyList() },
            foundations = listOf(
                emptyList(),
                listOf(c(Rank.Three, Suit.Hearts), c(Rank.Four, Suit.Hearts)),
                emptyList(),
                emptyList()
            )
        )
        assertEquals(
            "Four_Hearts: tableau0 -> foundation1; reveal: tableau1 -> King_Hearts",
            MoveTransitionDescriber.describe(previous, current)
        )
    }
}
