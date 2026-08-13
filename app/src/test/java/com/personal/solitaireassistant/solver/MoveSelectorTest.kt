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
    fun prefersDrawOverSlidingFourFromFiveFourOntoAnotherFive() {
        val state = GameState(
            tableau = listOf(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(c(Rank.Five, Suit.Clubs)),
                listOf(c(Rank.Five, Suit.Spades), c(Rank.Four, Suit.Diamonds)),
                listOf(c(Rank.King, Suit.Spades))
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Ace, Suit.Clubs, false)),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            "4 onto another 5 is legal but useless; expected draw, got ${best.move.label}",
            Move.DrawStock,
            best.move
        )
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
    fun prefersCreatingEmptyColumnOverDrawEvenWithoutImmediateKing() {
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
        assertEquals(
            Move.TableauToTableau(fromColumn = 0, startIndex = 0, toColumn = 1),
            best.move
        )
    }

    @Test
    fun prefersRevealOverDrawWhenHiddenCardCanBeExposed() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Ten, Suit.Clubs, false), c(Rank.Nine, Suit.Hearts)),
                listOf(c(Rank.Ten, Suit.Spades)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Ace, Suit.Clubs, false)),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "expected reveal move, got ${best.move}",
            best.move is Move.TableauToTableau &&
                (best.move as Move.TableauToTableau).fromColumn == 0
        )
    }

    @Test
    fun skipsRejectedFingerprint() {
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
        val rejected = setOf(MoveFingerprint.of(state, Move.TableauToTableau(0, 1, 1)))
        val best = requireNotNull(MoveSelector.bestMove(state, rejectedFingerprints = rejected))
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
    fun prefersDrawOverJackOntoQueenWhenNothingIsRevealed() {
        val state = GameState(
            tableau = listOf(
                listOf(
                    c(Rank.King, Suit.Spades),
                    c(Rank.Queen, Suit.Hearts),
                    c(Rank.Jack, Suit.Spades)
                ),
                listOf(c(Rank.King, Suit.Diamonds)),
                listOf(c(Rank.Queen, Suit.Diamonds, false), c(Rank.Queen, Suit.Diamonds)),
                listOf(c(Rank.Four, Suit.Spades, false), c(Rank.Four, Suit.Spades, false), c(Rank.Four, Suit.Spades)),
                listOf(c(Rank.Four, Suit.Diamonds, false), c(Rank.Four, Suit.Diamonds, false), c(Rank.Four, Suit.Diamonds, false), c(Rank.Four, Suit.Diamonds)),
                listOf(c(Rank.Six, Suit.Diamonds, false), c(Rank.Six, Suit.Diamonds, false), c(Rank.Six, Suit.Diamonds, false), c(Rank.Six, Suit.Diamonds, false), c(Rank.Six, Suit.Diamonds)),
                listOf(c(Rank.Eight, Suit.Hearts, false), c(Rank.Eight, Suit.Hearts, false), c(Rank.Eight, Suit.Hearts, false), c(Rank.Eight, Suit.Hearts, false), c(Rank.Eight, Suit.Hearts, false), c(Rank.Eight, Suit.Hearts))
            ),
            foundations = listOf(
                listOf(c(Rank.Ace, Suit.Clubs)),
                listOf(c(Rank.Two, Suit.Diamonds)),
                emptyList(),
                emptyList()
            ),
            stock = listOf(c(Rank.Three, Suit.Clubs, false)),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            "J onto Q reveals nothing; expected draw, got ${best.move.label}",
            Move.DrawStock,
            best.move
        )
    }

    @Test
    fun returnsNullWhenOnlyNonProductiveTableauMovesExistAndStockIsEmpty() {
        val state = GameState(
            tableau = listOf(
                listOf(
                    c(Rank.King, Suit.Spades),
                    c(Rank.Queen, Suit.Hearts),
                    c(Rank.Jack, Suit.Spades)
                ),
                listOf(c(Rank.King, Suit.Diamonds)),
                listOf(c(Rank.Queen, Suit.Diamonds)),
                emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = listOf(
                listOf(c(Rank.Ace, Suit.Clubs)),
                listOf(c(Rank.Two, Suit.Diamonds)),
                emptyList(),
                emptyList()
            ),
            stock = emptyList(),
            waste = emptyList()
        )

        assertEquals(null, MoveSelector.bestMove(state))
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
    fun fallsBackToDrawWhenCardHintsRejected() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.King, Suit.Spades)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Ace, Suit.Clubs, false)),
            waste = emptyList()
        )
        val rejected = setOf("King_Spades->EMPTY", "DRAW")
        val best = requireNotNull(MoveSelector.bestMove(state, rejectedFingerprints = rejected))
        assertEquals(Move.DrawStock, best.move)
    }

    @Test
    fun prefersFoundationTwoOntoTableauWhenUseful() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Three, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = listOf(
                listOf(c(Rank.Ace, Suit.Hearts), c(Rank.Two, Suit.Hearts)),
                emptyList(), emptyList(), emptyList()
            ),
            stock = emptyList(),
            waste = emptyList()
        )
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(Move.FoundationToTableau(0, 0), best.move)
    }
}
