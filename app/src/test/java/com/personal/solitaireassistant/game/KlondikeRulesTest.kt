package com.personal.solitaireassistant.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KlondikeRulesTest {
    private fun c(rank: Rank, suit: Suit, up: Boolean = true) = Card(rank, suit, up)

    @Test
    fun kingCanMoveToEmptyTableau() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.King, Suit.Spades)),
                emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        val move = Move.TableauToTableau(0, 0, 1)
        assertTrue(KlondikeRules.isLegal(state, move))
        val next = KlondikeRules.apply(state, move)!!
        assertTrue(next.tableau[0].isEmpty())
        assertEquals(Rank.King, next.tableau[1].single().rank)
    }

    @Test
    fun nonKingCannotMoveToEmptyTableau() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Queen, Suit.Hearts)),
                emptyList(),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        assertFalse(KlondikeRules.isLegal(state, Move.TableauToTableau(0, 0, 1)))
    }

    @Test
    fun alternatingDescendingRunIsRequired() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Ten, Suit.Clubs), c(Rank.Nine, Suit.Hearts)),
                listOf(c(Rank.Jack, Suit.Diamonds)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        assertTrue(KlondikeRules.isLegal(state, Move.TableauToTableau(0, 0, 1)))
    }

    @Test
    fun foundationAcceptsAceThenTwo() {
        var state = GameState.empty().copy(
            tableau = listOf(
                listOf(c(Rank.Ace, Suit.Hearts)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        state = KlondikeRules.apply(state, Move.TableauToFoundation(0, 0))!!
        assertEquals(Rank.Ace, state.foundations[0].single().rank)

        state = state.copy(
            tableau = listOf(
                listOf(c(Rank.Two, Suit.Hearts)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            )
        )
        state = KlondikeRules.apply(state, Move.TableauToFoundation(0, 0))!!
        assertEquals(Rank.Two, state.foundations[0].last().rank)
    }

    @Test
    fun drawThreeMovesStockToWaste() {
        val stock = listOf(
            c(Rank.Ace, Suit.Clubs, false),
            c(Rank.Two, Suit.Clubs, false),
            c(Rank.Three, Suit.Clubs, false),
            c(Rank.Four, Suit.Clubs, false)
        )
        val state = GameState.empty().copy(stock = stock)
        val next = KlondikeRules.apply(state, Move.DrawStock)!!
        assertEquals(1, next.stock.size)
        assertEquals(3, next.waste.size)
        assertEquals(Rank.Three, next.wasteTop()!!.rank)
    }

    @Test
    fun recycleReversesWasteIntoStock() {
        val waste = listOf(
            c(Rank.Ace, Suit.Spades),
            c(Rank.Two, Suit.Spades),
            c(Rank.Three, Suit.Spades)
        )
        val state = GameState.empty().copy(waste = waste)
        val next = KlondikeRules.apply(state, Move.RecycleWaste)!!
        assertTrue(next.waste.isEmpty())
        assertEquals(listOf(Rank.Three, Rank.Two, Rank.Ace), next.stock.map { it.rank })
        assertEquals(1, next.recyclesUsed)
    }

    @Test
    fun flippingHiddenCardAfterTableauMove() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Five, Suit.Clubs, false), c(Rank.Four, Suit.Hearts)),
                listOf(c(Rank.Five, Suit.Spades)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        val next = KlondikeRules.apply(state, Move.TableauToTableau(0, 1, 1))!!
        assertEquals(1, next.tableau[0].size)
        assertTrue(next.tableau[0].single().faceUp)
        assertEquals(Rank.Five, next.tableau[0].single().rank)
    }

    @Test
    fun wasteToTableauAndFoundation() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Eight, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = listOf(listOf(c(Rank.Ace, Suit.Hearts)), emptyList(), emptyList(), emptyList()),
            stock = emptyList(),
            waste = listOf(c(Rank.Seven, Suit.Diamonds), c(Rank.Two, Suit.Hearts))
        )
        assertNotNull(KlondikeRules.apply(state, Move.WasteToFoundation(0)))
        val toTableau = state.copy(waste = listOf(c(Rank.Seven, Suit.Hearts)))
        assertNotNull(KlondikeRules.apply(toTableau, Move.WasteToTableau(0)))
    }

    @Test
    fun illegalDrawWhenStockEmpty() {
        assertNull(KlondikeRules.apply(GameState.empty(), Move.DrawStock))
    }

    @Test
    fun ambiguousBlackSuitCannotMoveToFoundation() {
        val clubs = Card(Rank.Four, Suit.Clubs, suitAmbiguous = true)
        val spadeFoundation = Card(Rank.Three, Suit.Spades)
        assertFalse(clubs.canPlaceOnFoundation(spadeFoundation))

        val state = GameState(
            tableau = listOf(
                listOf(clubs),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = listOf(
                emptyList(),
                emptyList(),
                listOf(spadeFoundation),
                emptyList()
            ),
            stock = emptyList(),
            waste = emptyList()
        )
        assertFalse(KlondikeRules.isLegal(state, Move.TableauToFoundation(0, 2)))
        assertTrue(clubs.canStackOnTableau(Card(Rank.Five, Suit.Hearts)))
    }

    @Test
    fun inferredCascadeCardsCannotMove() {
        val inferred = Card(Rank.Queen, Suit.Hearts, inferred = true)
        val top = Card(Rank.Jack, Suit.Clubs)
        val state = GameState(
            tableau = listOf(
                listOf(inferred, top),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        assertTrue(
            KlondikeRules.legalMoves(state).none { it is Move.TableauToTableau }
        )
    }
}
