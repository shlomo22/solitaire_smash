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
                listOf(c(Rank.Four, Suit.Spades), c(Rank.Three, Suit.Hearts)),
                listOf(c(Rank.Queen, Suit.Hearts, false), c(Rank.Ten, Suit.Clubs)),
                listOf(c(Rank.Seven, Suit.Hearts, false), c(Rank.Six, Suit.Clubs)),
                listOf(c(Rank.Eight, Suit.Spades, false), c(Rank.Nine, Suit.Diamonds)),
                listOf(c(Rank.Seven, Suit.Spades, false), c(Rank.Six, Suit.Hearts))
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Four, Suit.Spades, false)),
            waste = listOf(c(Rank.Two, Suit.Clubs))
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

    @Test
    fun prefersRevealInDeeperColumn() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Seven, Suit.Spades, false), c(Rank.Eight, Suit.Hearts)),
                listOf(
                    c(Rank.Seven, Suit.Clubs, false),
                    c(Rank.Six, Suit.Hearts, false),
                    c(Rank.Eight, Suit.Spades)
                ),
                listOf(c(Rank.Nine, Suit.Diamonds)),
                listOf(c(Rank.Nine, Suit.Clubs)),
                emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            Move.TableauToTableau(fromColumn = 1, startIndex = 2, toColumn = 2),
            best.move
        )
    }

    @Test
    fun doesNotDeferAceToFoundationWhenTableauTwoExists() {
        val withSequence = GameState(
            tableau = listOf(
                listOf(c(Rank.Ace, Suit.Hearts)),
                listOf(c(Rank.Two, Suit.Spades)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        val alone = GameState(
            tableau = listOf(
                listOf(c(Rank.Ace, Suit.Hearts)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )

        val withSequenceScore = MoveSelector.scoreAll(withSequence)
            .first { it.move is Move.TableauToFoundation }
            .score
        val aloneScore = MoveSelector.scoreAll(alone)
            .first { it.move is Move.TableauToFoundation }
            .score

        assertTrue(withSequenceScore >= aloneScore)
        val best = requireNotNull(MoveSelector.bestMove(withSequence))
        assertTrue(best.move is Move.TableauToFoundation)
    }

    @Test
    fun prefersWasteAceToFoundationOverTableauPark() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Two, Suit.Spades)),
                listOf(c(Rank.Five, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = listOf(c(Rank.Ace, Suit.Diamonds))
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "Expected waste Ace to foundation, got ${best.move}",
            best.move is Move.WasteToFoundation
        )
    }

    @Test
    fun holdsUnbalancedTwoWhenDrawIsAvailable() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Two, Suit.Spades)),
                listOf(c(Rank.Five, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = listOf(
                listOf(c(Rank.Ace, Suit.Spades)),
                emptyList(), emptyList(), emptyList()
            ),
            stock = listOf(c(Rank.Four, Suit.Hearts, false)),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(Move.DrawStock, best.move)
    }

    @Test
    fun sendsUnbalancedTwoWhenItIsTheOnlyCardMove() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Two, Suit.Spades)),
                listOf(c(Rank.Five, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = listOf(
                listOf(c(Rank.Ace, Suit.Spades)),
                emptyList(), emptyList(), emptyList()
            ),
            stock = emptyList(),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "Expected Two to foundation as only card move, got ${best.move}",
            best.move is Move.TableauToFoundation
        )
    }

    @Test
    fun sendsUnbalancedTwoWhenItReveals() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Seven, Suit.Clubs, false), c(Rank.Two, Suit.Spades)),
                listOf(c(Rank.Five, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = listOf(
                listOf(c(Rank.Ace, Suit.Spades)),
                emptyList(), emptyList(), emptyList()
            ),
            stock = listOf(c(Rank.Four, Suit.Hearts, false)),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "Expected revealing Two to foundation, got ${best.move}",
            best.move is Move.TableauToFoundation
        )
    }

    @Test
    fun sendsBalancedTwoWhenNotABridge() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Two, Suit.Spades)),
                listOf(c(Rank.Five, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = listOf(
                listOf(c(Rank.Ace, Suit.Spades)),
                listOf(c(Rank.Ace, Suit.Hearts)),
                listOf(c(Rank.Ace, Suit.Diamonds)),
                emptyList()
            ),
            stock = listOf(c(Rank.Four, Suit.Hearts, false)),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "Expected balanced Two to foundation, got ${best.move}",
            best.move is Move.TableauToFoundation
        )
    }

    @Test
    fun prefersKingWithLargerHiddenFamily() {
        val state = GameState(
            tableau = listOf(
                emptyList(),
                listOf(c(Rank.King, Suit.Spades)),
                listOf(
                    c(Rank.Queen, Suit.Hearts, false),
                    c(Rank.Jack, Suit.Clubs, false),
                    c(Rank.Ten, Suit.Diamonds, false),
                    c(Rank.King, Suit.Hearts)
                ),
                listOf(c(Rank.Five, Suit.Clubs)),
                listOf(c(Rank.Five, Suit.Spades)),
                listOf(c(Rank.Seven, Suit.Clubs)),
                listOf(c(Rank.Seven, Suit.Spades))
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            Move.TableauToTableau(fromColumn = 2, startIndex = 3, toColumn = 0),
            best.move
        )
    }

    @Test
    fun defersDrawWhenWasteUnlocksTableau() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Jack, Suit.Clubs)),
                listOf(c(Rank.Nine, Suit.Hearts), c(Rank.Eight, Suit.Spades)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(c(Rank.Ace, Suit.Clubs, false)),
            waste = listOf(c(Rank.Ten, Suit.Hearts))
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(Move.WasteToTableau(toColumn = 0), best.move)
    }

    @Test
    fun recyclePenaltyIncreasesWithRecyclesUsed() {
        val state = GameState(
            tableau = List(7) { emptyList() },
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = listOf(c(Rank.King, Suit.Spades)),
            recyclesUsed = 2
        )

        val recycleScore = MoveSelector.scoreAll(state)
            .first { it.move == Move.RecycleWaste }
            .score
        val freshState = state.copy(recyclesUsed = 0)
        val freshRecycleScore = MoveSelector.scoreAll(freshState)
            .first { it.move == Move.RecycleWaste }
            .score

        assertTrue(recycleScore < freshRecycleScore)
    }

    @Test
    fun penalizesMovingExtraCardsOnReveal() {
        val state = GameState(
            tableau = listOf(
                listOf(
                    c(Rank.Nine, Suit.Clubs, false),
                    c(Rank.Ten, Suit.Hearts),
                    c(Rank.Nine, Suit.Spades),
                    c(Rank.Eight, Suit.Diamonds)
                ),
                listOf(c(Rank.Jack, Suit.Clubs)),
                emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )

        val fullRun = MoveSelector.scoreAll(state)
            .first { it.move == Move.TableauToTableau(fromColumn = 0, startIndex = 1, toColumn = 1) }
        assertTrue(fullRun.rationale.contains("minimal-move"))
    }

    /**
     * Screenshot late-game shape: waste Q♣ / recycle looks best under the
     * normal scorer because 3♦→4♣ does not flip a face-down card. After two
     * idle waste cycles, that peel must win so 4♠ can go to foundation 3♠.
     */
    @Test
    fun whenWasteCycleStuckPrefersPeelThatUnblocksFoundation() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Ten, Suit.Hearts)),
                listOf(c(Rank.Four, Suit.Clubs)),
                listOf(c(Rank.Two, Suit.Diamonds)),
                listOf(c(Rank.Six, Suit.Spades)),
                emptyList(),
                listOf(
                    c(Rank.King, Suit.Hearts, false),
                    c(Rank.Queen, Suit.Clubs, false),
                    c(Rank.Jack, Suit.Diamonds, false),
                    c(Rank.Four, Suit.Spades),
                    c(Rank.Three, Suit.Diamonds)
                ),
                listOf(c(Rank.Six, Suit.Diamonds))
            ),
            foundations = listOf(
                listOf(
                    c(Rank.Ace, Suit.Hearts),
                    c(Rank.Two, Suit.Hearts),
                    c(Rank.Three, Suit.Hearts),
                    c(Rank.Four, Suit.Hearts)
                ),
                listOf(
                    c(Rank.Ace, Suit.Spades),
                    c(Rank.Two, Suit.Spades),
                    c(Rank.Three, Suit.Spades)
                ),
                listOf(c(Rank.Ace, Suit.Clubs), c(Rank.Two, Suit.Clubs)),
                emptyList()
            ),
            stock = emptyList(),
            waste = listOf(c(Rank.Queen, Suit.Clubs))
        )

        val stuckBest = requireNotNull(
            MoveSelector.bestMove(state, wasteCycleStuck = true)
        )
        assertEquals(
            Move.TableauToTableau(fromColumn = 5, startIndex = 4, toColumn = 1),
            stuckBest.move
        )
        assertTrue(
            "expected unstuck peel rationale, got ${stuckBest.rationale}",
            stuckBest.rationale.contains("unstuck")
        )
        val recycleScore = MoveSelector.scoreAll(state, wasteCycleStuck = true)
            .first { it.move == Move.RecycleWaste }
            .score
        assertTrue(
            "peel ${stuckBest.score} should beat recycle $recycleScore",
            stuckBest.score > recycleScore
        )

        val notStuckBest = requireNotNull(
            MoveSelector.bestMove(state, wasteCycleStuck = false)
        )
        assertEquals(Move.RecycleWaste, notStuckBest.move)
    }

    @Test
    fun whenWasteCycleStuckPrefersFoundationPullThatReceivesHiddenColumnTop() {
        // 3♣ sits on a face-down card; the only 4 it can land on (4♥) is
        // already on foundation. Pull that 4 onto a black 5 so the 3 can follow.
        val state = GameState(
            tableau = listOf(
                listOf(
                    c(Rank.Nine, Suit.Diamonds, false),
                    c(Rank.Three, Suit.Clubs)
                ),
                listOf(c(Rank.Five, Suit.Spades)),
                listOf(c(Rank.Seven, Suit.Diamonds)),
                listOf(c(Rank.Seven, Suit.Hearts)),
                listOf(c(Rank.Nine, Suit.Spades)),
                listOf(c(Rank.Jack, Suit.Diamonds)),
                listOf(c(Rank.Jack, Suit.Hearts))
            ),
            foundations = listOf(
                listOf(
                    c(Rank.Ace, Suit.Hearts),
                    c(Rank.Two, Suit.Hearts),
                    c(Rank.Three, Suit.Hearts),
                    c(Rank.Four, Suit.Hearts)
                ),
                emptyList(),
                emptyList(),
                emptyList()
            ),
            stock = emptyList(),
            waste = listOf(c(Rank.Queen, Suit.Clubs))
        )

        val stuckBest = requireNotNull(
            MoveSelector.bestMove(state, wasteCycleStuck = true)
        )
        assertEquals(
            Move.FoundationToTableau(fromFoundation = 0, toColumn = 1),
            stuckBest.move
        )
        assertTrue(
            "expected unstuck foundation pull, got ${stuckBest.rationale}",
            stuckBest.rationale.contains("unstuck-foundation-pull")
        )

        val notStuckBest = requireNotNull(
            MoveSelector.bestMove(state, wasteCycleStuck = false)
        )
        assertEquals(Move.RecycleWaste, notStuckBest.move)
    }

    @Test
    fun prefersRevealThatUnlocksFoundationOverPlainReveal() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Nine, Suit.Clubs, false), c(Rank.Eight, Suit.Hearts)),
                listOf(c(Rank.Four, Suit.Spades, false), c(Rank.Three, Suit.Diamonds)),
                listOf(c(Rank.Nine, Suit.Spades)),
                listOf(c(Rank.Four, Suit.Clubs)),
                emptyList(), emptyList(), emptyList()
            ),
            foundations = listOf(
                emptyList(),
                listOf(
                    c(Rank.Ace, Suit.Spades),
                    c(Rank.Two, Suit.Spades),
                    c(Rank.Three, Suit.Spades)
                ),
                emptyList(),
                emptyList()
            ),
            stock = emptyList(),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            Move.TableauToTableau(fromColumn = 1, startIndex = 1, toColumn = 3),
            best.move
        )
        assertTrue(
            "expected open-unlock or lookahead on the 4S line, got ${best.rationale}",
            best.rationale.contains("open-unlock") || best.rationale.contains("lookahead")
        )
    }

    @Test
    fun amongNoRevealStacksPrefersTheOneThatUnlocksAKnownCard() {
        val state = GameState(
            tableau = listOf(
                listOf(c(Rank.Nine, Suit.Clubs), c(Rank.Eight, Suit.Hearts)),
                listOf(c(Rank.Four, Suit.Spades), c(Rank.Three, Suit.Diamonds)),
                listOf(c(Rank.Nine, Suit.Spades)),
                listOf(c(Rank.Four, Suit.Clubs)),
                emptyList(), emptyList(), emptyList()
            ),
            foundations = listOf(
                emptyList(),
                listOf(
                    c(Rank.Ace, Suit.Spades),
                    c(Rank.Two, Suit.Spades),
                    c(Rank.Three, Suit.Spades)
                ),
                emptyList(),
                emptyList()
            ),
            stock = emptyList(),
            waste = emptyList()
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            Move.TableauToTableau(fromColumn = 1, startIndex = 1, toColumn = 3),
            best.move
        )
        assertTrue(
            "expected open-unlock-foundation, got ${best.rationale}",
            best.rationale.contains("open-unlock-foundation")
        )
    }
}
