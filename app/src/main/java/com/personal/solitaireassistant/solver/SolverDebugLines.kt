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

    /**
     * The trust profile of the move actually on screen, so a false arrow can be
     * attributed from the log alone instead of having to be caught live.
     *
     * Golden-set measurement (169 samples): every cross-color suit error but one
     * sits on a *covered* cascade card, and 33 of 37 have the rank wrong at the
     * same time — the signature of a shifted slot reading its neighbour, since
     * [com.personal.solitaireassistant.vision.TableauCascadeSupport.geometricCascadeCard]
     * derives color from `distanceFromBottom % 2`. Exposed bottom cards and waste
     * cards are essentially never wrong about color. So a false tableau arrow
     * whose moved run is a single exposed card is *not* explained by that
     * measurement, and a multi-card run is. `carried=` marks the covered cards
     * that only [com.personal.solitaireassistant.game.KlondikeRules] run
     * validation depends on.
     */
    fun moveTrustLine(state: GameState, move: Move?): String {
        if (move == null) return "move-trust=none"
        val detail = when (move) {
            is Move.TableauToTableau -> {
                val column = state.tableau.getOrNull(move.fromColumn) ?: return "move-trust=no-column"
                val run = column.drop(move.startIndex)
                val carried = run.dropLast(1)
                val target = state.tableauTop(move.toColumn)
                "tableau-run len=${run.size}" +
                    " mover=${run.firstOrNull()?.let { cardToken(it) } ?: "?"}" +
                    " target=${target?.let { cardToken(it) } ?: "empty"}" +
                    " carried=${if (carried.isEmpty()) "none" else carried.joinToString("/") { cardToken(it) }}" +
                    " risk=${runRisk(run)}"
            }
            is Move.WasteToTableau -> {
                val target = state.tableauTop(move.toColumn)
                "waste-to-tableau mover=${state.wasteTop()?.let { cardToken(it) } ?: "?"}" +
                    " target=${target?.let { cardToken(it) } ?: "empty"}"
            }
            is Move.TableauToFoundation -> {
                val mover = state.tableauTop(move.fromColumn)
                "tableau-to-foundation mover=${mover?.let { cardToken(it) } ?: "?"}"
            }
            is Move.WasteToFoundation ->
                "waste-to-foundation mover=${state.wasteTop()?.let { cardToken(it) } ?: "?"}"
            is Move.FoundationToTableau -> {
                val target = state.tableauTop(move.toColumn)
                "foundation-to-tableau target=${target?.let { cardToken(it) } ?: "empty"}"
            }
            else -> "no-card-move"
        }
        return "move-trust=$detail"
    }

    /**
     * Whether this run's legality rests on any card the golden set says is
     * unreliable — a covered cascade card, an ambiguous suit, or an inferred
     * guess. `exposed-only` means every card in it is directly read.
     */
    private fun runRisk(run: List<Card>): String {
        if (run.size <= 1) return "exposed-only"
        val carried = run.dropLast(1)
        val reasons = mutableListOf<String>()
        if (carried.any { it.inferred }) reasons += "carried-inferred"
        if (carried.any { it.suitAmbiguous }) reasons += "carried-ambiguous"
        if (carried.any { !it.known }) reasons += "carried-unknown"
        if (reasons.isEmpty()) reasons += "carried-covered"
        return reasons.joinToString("+")
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
