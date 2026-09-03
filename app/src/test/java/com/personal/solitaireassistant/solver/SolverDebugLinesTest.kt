package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.KlondikeRules
import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolverDebugLinesTest {
    @Test
    fun flagsMarkInferredAndAmbiguousWithoutChangingId() {
        val five = Card(
            Rank.Five,
            Suit.Spades,
            suitAmbiguous = true,
            inferred = true
        )
        val four = Card(Rank.Four, Suit.Diamonds, suitAmbiguous = true)
        assertEquals("Five_Spades~*", SolverDebugLines.cardToken(five))
        assertEquals("Four_Diamonds~", SolverDebugLines.cardToken(four))
        assertEquals("D", SolverDebugLines.cardToken(Card(Rank.Ace, Suit.Clubs, faceUp = false)))
    }

    @Test
    fun revealCheckExplainsInferredFiveCannotMoveOntoSix() {
        val state = fiveOnSixBoard(fiveInferred = true)
        val line = SolverDebugLines.revealCheckLine(state)
        assertTrue(line, line.contains("T4->T6 Five_Spades->Six_Hearts"))
        assertTrue(line, line.contains("run=inferred:Five_Spades"))
        assertTrue(line, line.contains("mover-inferred"))
        assertTrue(
            KlondikeRules.legalMoves(state).none { it.label == "Tableau 4 -> Tableau 6" }
        )
    }

    @Test
    fun revealCheckShowsStackOkWhenFiveIsARealRead() {
        val state = fiveOnSixBoard(fiveInferred = false)
        val line = SolverDebugLines.revealCheckLine(state)
        assertTrue(line, line.contains("run=ok stack-ok"))
        assertTrue(
            KlondikeRules.legalMoves(state).any { it.label == "Tableau 4 -> Tableau 6" }
        )
        assertEquals("inferred=none", SolverDebugLines.inferredLine(state))
    }

    @Test
    fun legalLineListsTableauDump() {
        val state = fiveOnSixBoard(fiveInferred = false)
        val legal = KlondikeRules.legalMoves(state)
        val line = SolverDebugLines.legalLine(legal)
        assertTrue(line, line.contains("Tableau 4 -> Tableau 6"))
        assertTrue(line, line.startsWith("legal="))
    }

    @Test
    fun moveTrustSeparatesASingleExposedCardFromACarriedRun() {
        val state = fiveOnSixBoard(fiveInferred = false)
        // Column 4 is [down, Five, Four]; startIndex 1 carries the Five under
        // the Four, startIndex 2 moves only the exposed Four.
        val run = SolverDebugLines.moveTrustLine(
            state,
            Move.TableauToTableau(fromColumn = 3, startIndex = 1, toColumn = 5)
        )
        assertTrue(run, run.contains("tableau-run len=2"))
        // The mover is the run's deepest card, so it is itself covered - it
        // belongs in covered= rather than being excluded from it.
        assertTrue(run, run.contains("mover=Five_Spades~"))
        assertTrue(run, run.contains("covered=Five_Spades~"))
        assertTrue(run, run.contains("risk=mover-covered+mover-ambiguous"))

        val single = SolverDebugLines.moveTrustLine(
            state,
            Move.TableauToTableau(fromColumn = 3, startIndex = 2, toColumn = 5)
        )
        assertTrue(single, single.contains("tableau-run len=1"))
        assertTrue(single, single.contains("covered=none"))
        assertTrue(single, single.contains("risk=exposed-only"))
    }

    @Test
    fun moveTrustReportsTargetAndHandlesNonCardMoves() {
        val state = fiveOnSixBoard(fiveInferred = false)
        val line = SolverDebugLines.moveTrustLine(
            state,
            Move.TableauToTableau(fromColumn = 3, startIndex = 2, toColumn = 5)
        )
        assertTrue(line, line.contains("target=Six_Hearts~"))
        assertEquals("move-trust=none", SolverDebugLines.moveTrustLine(state, null))
        assertEquals(
            "move-trust=no-card-move",
            SolverDebugLines.moveTrustLine(state, Move.DrawStock)
        )
    }

    private fun fiveOnSixBoard(fiveInferred: Boolean): GameState {
        val down = Card(Rank.Ace, Suit.Clubs, faceUp = false, known = false)
        val five = Card(
            Rank.Five,
            Suit.Spades,
            suitAmbiguous = true,
            inferred = fiveInferred
        )
        val four = Card(Rank.Four, Suit.Diamonds, suitAmbiguous = true)
        val six = Card(Rank.Six, Suit.Hearts, suitAmbiguous = true)
        return GameState(
            tableau = listOf(
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(down, five, four),
                emptyList(),
                listOf(down, down, down, down, six),
                emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = listOf(Card(Rank.King, Suit.Clubs, faceUp = false, known = false)),
            waste = emptyList()
        )
    }
}
