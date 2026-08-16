package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveSelectorTest {
    private fun c(rank: Rank, suit: Suit, up: Boolean = true) = Card(rank, suit, up)

    @Test
    fun prefersRevealOverNeutralRearrange() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Nine, Suit.Clubs, false), c(Rank.Eight, Suit.Hearts)),
                listOf(c(Rank.Nine, Suit.Spades)),
                listOf(c(Rank.Three, Suit.Diamonds)),
                listOf(c(Rank.Four, Suit.Clubs)),
                emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        val best = MoveSelector.bestMove(state)
        assertTrue(best != null)
        assertTrue(
            "Expected reveal move, got ${best!!.move}",
            best.move is Move.TableauToTableau &&
                (best.move as Move.TableauToTableau).fromColumn == 0
        )
    }

    @Test
    fun prefersSafeAceToFoundation() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Ace, Suit.Hearts)),
                listOf(c(Rank.Five, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Two, Suit.Spades, false)),
            waste = emptyList()
        )
        val best = MoveSelector.bestMove(state)!!
        assertTrue(best.move is Move.TableauToFoundation)
    }

    @Test
    fun prefersLowWasteCardBeforeIndependentTableauReveal() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Jack, Suit.Spades)),
                emptyList(),
                listOf(c(Rank.Three, Suit.Hearts), c(Rank.Two, Suit.Clubs)),
                listOf(c(Rank.Queen, Suit.Hearts, false), c(Rank.Ten, Suit.Clubs)),
                listOf(c(Rank.Seven, Suit.Hearts, false), c(Rank.Six, Suit.Clubs)),
                listOf(c(Rank.Eight, Suit.Spades, false), c(Rank.Nine, Suit.Diamonds)),
                listOf(c(Rank.Seven, Suit.Spades, false), c(Rank.Six, Suit.Hearts))
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Four, Suit.Spades, false)),
            waste = listOf(c(Rank.Ace, Suit.Diamonds))
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(Move.WasteToTableau(toColumn = 2), best.move)
    }

    @Test
    fun drawIsAvailableWhenStockPresent() {
        val state = GameState.empty().copy(
            stock = listOf(c(Rank.Ace, Suit.Clubs, false), c(Rank.Two, Suit.Clubs, false), c(Rank.Three, Suit.Clubs, false))
        )
        val moves = MoveGenerator.generate(state)
        assertTrue(moves.contains(Move.DrawStock))
    }

    @Test
    fun prefersDrawOverTableauMoveThatRevealsNothing() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Nine, Suit.Clubs), c(Rank.Eight, Suit.Hearts)),
                listOf(c(Rank.Nine, Suit.Spades)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Ace, Suit.Clubs, false)),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(Move.DrawStock, best.move)
    }

    @Test
    fun prefersDrawOverCreatingEmptyColumnWithNoUsableKing() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Queen, Suit.Diamonds)),
                listOf(c(Rank.King, Suit.Spades)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Ace, Suit.Clubs, false)),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(Move.DrawStock, best.move)
    }

    @Test
    fun prefersCreatingEmptyColumnWhenAnotherKingCanUseIt() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Queen, Suit.Diamonds)),
                listOf(c(Rank.King, Suit.Spades)),
                listOf(c(Rank.King, Suit.Hearts)),
                listOf(c(Rank.Five, Suit.Clubs)),
                listOf(c(Rank.Five, Suit.Spades)),
                listOf(c(Rank.Seven, Suit.Clubs)),
                listOf(c(Rank.Seven, Suit.Spades))
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Ace, Suit.Clubs, false)),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            Move.TableauToTableau(fromColumn = 0, startIndex = 0, toColumn = 1),
            best.move
        )
    }

    @Test
    fun avoidsReturningToRecentTableauState() {
        val original = GameState(
            tableau = listOf(
                listOf(
                    c(Rank.King, Suit.Spades),
                    c(Rank.Queen, Suit.Hearts),
                    c(Rank.Jack, Suit.Clubs)
                ),
                emptyList(),
                emptyList(),
                listOf(c(Rank.Six, Suit.Hearts)),
                emptyList(),
                listOf(c(Rank.Queen, Suit.Diamonds)),
                emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Ace, Suit.Spades, false)),
            waste = listOf(c(Rank.Five, Suit.Clubs))
        )
        val moved = requireNotNull(
            com.personal.solitaireassistant.game.KlondikeRules.apply(
                original,
                Move.TableauToTableau(fromColumn = 0, startIndex = 2, toColumn = 5)
            )
        )

        val best = requireNotNull(MoveSelector.bestMove(moved, avoidStates = listOf(original)))
        assertEquals(Move.WasteToTableau(toColumn = 3), best.move)
    }

    @Test
    fun prefersHighScoreRevealEvenWhenAvoidStatesWouldBlock() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Nine, Suit.Clubs, false), c(Rank.Eight, Suit.Hearts)),
                listOf(c(Rank.Nine, Suit.Spades)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Ace, Suit.Clubs, false)),
            waste = emptyList()
        )
        val afterReveal = requireNotNull(
            com.personal.solitaireassistant.game.KlondikeRules.apply(
                state,
                Move.TableauToTableau(fromColumn = 0, startIndex = 1, toColumn = 1)
            )
        )

        val best = requireNotNull(MoveSelector.bestMove(state, avoidStates = listOf(afterReveal)))
        assertEquals(
            Move.TableauToTableau(fromColumn = 0, startIndex = 1, toColumn = 1),
            best.move
        )
    }

    @Test
    fun prefersProductiveRevealEvenWhenResultIsInAvoidStates() {
        val beforeReveal = GameState(
            tableau = listOf(
                emptyList(), emptyList(),
                listOf(c(Rank.Nine, Suit.Clubs, false), c(Rank.Nine, Suit.Clubs)),
                emptyList(),
                listOf(c(Rank.Seven, Suit.Spades, false), c(Rank.Eight, Suit.Diamonds)),
                emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Ace, Suit.Clubs, false)),
            waste = emptyList()
        )
        val afterReveal = requireNotNull(
            com.personal.solitaireassistant.game.KlondikeRules.apply(
                beforeReveal,
                Move.TableauToTableau(fromColumn = 4, startIndex = 1, toColumn = 2)
            )
        )

        val best = requireNotNull(
            MoveSelector.bestMove(beforeReveal, avoidStates = listOf(afterReveal))
        )
        assertEquals(
            Move.TableauToTableau(fromColumn = 4, startIndex = 1, toColumn = 2),
            best.move
        )
    }

    @Test
    fun prefersDirectWasteStackOverTableauShuffle() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Jack, Suit.Clubs)),
                listOf(c(Rank.Nine, Suit.Hearts, false), c(Rank.Eight, Suit.Spades)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = listOf(c(Rank.Ten, Suit.Hearts))
        )
        val best = MoveSelector.bestMove(state)
        assertTrue(best != null)
        assertTrue(
            "Expected waste 10H onto JC, got ${best!!.move}",
            best.move is Move.WasteToTableau &&
                (best.move as Move.WasteToTableau).toColumn == 0
        )
    }
}
