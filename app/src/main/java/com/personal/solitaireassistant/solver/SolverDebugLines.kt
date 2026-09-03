package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Move

/**
 * Extra analysis.log lines for "why wasn't that arrow suggested?"
 *
 * [GameState] ids omit [Card.inferred] / [Card.suitAmbiguous], and the
 * live logger only prints the top few ranked moves. The last 5♠→6♥ miss
 * was invisible for both reasons.
 */
object SolverDebugLines {
    fun cardToken(card: Card): String = when {
        !card.faceUp -> "D"
        !card.known -> "U" + inferredMark(card)
        else -> card.id + (if (card.suitAmbiguous) "~" else "") + inferredMark(card)
    }

    fun flags(state: GameState): String =
        state.tableau.joinToString(";") { col ->
            col.joinToString(",") { cardToken(it) }
        }

    fun inferredLine(state: GameState): String {
        val parts = mutableListOf<String>()
        state.tableau.forEachIndexed { col, cards ->
            cards.forEachIndexed { index, card ->
                if (card.faceUp && card.inferred) {
                    parts += "T${col + 1}[$index]${card.id}"
                }
            }
        }
        return if (parts.isEmpty()) "inferred=none" else "inferred=${parts.joinToString(",")}"
    }

    fun legalLine(moves: List<Move>): String =
        "legal=${moves.joinToString(" | ") { it.label }}"

    fun filteredLine(dropped: List<Move>): String =
        "filtered=${dropped.joinToString(" | ") { it.label }}"

    /**
     * Rank-adjacent reveal candidates only (hidden cards behind the mover,
     * target rank is exactly one above). Prints why [KlondikeRules] would
     * accept or reject that stack — the line the 5♠→6♥ pull lacked.
     */
    fun revealCheckLine(state: GameState): String {
        val parts = mutableListOf<String>()
        for (from in 0 until 7) {
            val column = state.tableau[from]
            if (column.none { !it.faceUp }) continue
            val start = column.indexOfFirst { it.faceUp }
            if (start < 0) continue
            val run = column.subList(start, column.size)
            val mover = run.first()
            if (!mover.known) continue
            val runWhy = runReason(run)
            for (to in 0 until 7) {
                if (to == from) continue
                val target = state.tableauTop(to) ?: continue
                if (!target.known) continue
                if (mover.rank.value + 1 != target.rank.value) continue
                parts += "T${from + 1}->T${to + 1} ${mover.id}->${target.id} " +
                    "run=$runWhy ${stackReason(mover, target)}"
            }
        }
        return if (parts.isEmpty()) {
            "reveal-check=none"
        } else {
            "reveal-check=${parts.joinToString("; ")}"
        }
    }

    private fun inferredMark(card: Card): String = if (card.inferred) "*" else ""

    private fun runReason(run: List<Card>): String {
        val inferred = run.filter { it.inferred }
        if (inferred.isNotEmpty()) {
            return "inferred:${inferred.joinToString("/") { it.id }}"
        }
        if (run.any { !it.faceUp }) return "face-down"
        for (i in 0 until run.lastIndex) {
            val upper = run[i]
            val lower = run[i + 1]
            if (!lower.canStackOnTableau(upper)) {
                return "broken:${lower.id}!/${upper.id}"
            }
        }
        return "ok"
    }

    private fun stackReason(mover: Card, target: Card): String = when {
        mover.inferred -> "mover-inferred"
        !target.known -> "target-unknown"
        !mover.suit.oppositeColor(target.suit) -> "same-color"
        !mover.rank.isOneBelow(target.rank) -> "rank"
        else -> "stack-ok"
    }
}
