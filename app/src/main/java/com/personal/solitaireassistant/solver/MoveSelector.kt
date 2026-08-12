package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.KlondikeRules
import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.ScoredMove

/**
 * Bounded one-ply scorer with light look-ahead bonuses.
 * Deterministic tie-breaking by move label.
 */
object MoveSelector {
    fun bestMove(
        state: GameState,
        avoidStates: Collection<GameState> = emptyList()
    ): ScoredMove? {
        val ranked = scoreAll(state).sortedWith(
            compareBy<ScoredMove> { it.score }
                .thenBy { it.move.label }
                .reversed()
        )
        if (avoidStates.isEmpty()) return ranked.firstOrNull()

        return ranked.firstOrNull { candidate ->
            val next = KlondikeRules.apply(state, candidate.move) ?: return@firstOrNull false
            next !in avoidStates
        } ?: ranked.firstOrNull { it.move is Move.DrawStock || it.move is Move.RecycleWaste }
    }

    fun scoreAll(state: GameState): List<ScoredMove> {
        return MoveGenerator.generate(state).map { move ->
            val next = KlondikeRules.apply(state, move)
                ?: return@map ScoredMove(move, Double.NEGATIVE_INFINITY, "illegal")
            val (score, why) = scoreTransition(state, next, move)
            ScoredMove(move, score, why)
        }
    }

    private fun scoreTransition(
        before: GameState,
        after: GameState,
        move: Move
    ): Pair<Double, String> {
        var score = 0.0
        val reasons = mutableListOf<String>()

        val revealed = before.hiddenTableauCount() - after.hiddenTableauCount()
        if (revealed > 0) {
            score += 120.0 * revealed
            reasons += "reveal+$revealed"
        }

        val foundationDelta = after.foundationCount() - before.foundationCount()
        if (foundationDelta > 0) {
            val card = movedFoundationCard(before, move)
            val safe = card != null && KlondikeRules.isSafeFoundationMove(before, card)
            val foundationScore = if (safe) 80.0 else 15.0
            score += foundationScore * foundationDelta
            reasons += if (safe) "safe-foundation" else "risky-foundation"
        }

        val emptyBefore = before.tableau.count { it.isEmpty() }
        val emptyAfter = after.tableau.count { it.isEmpty() }
        val createsUsefulEmpty =
            move is Move.TableauToTableau &&
                emptyAfter > emptyBefore &&
                createsKingOpportunity(before, after, move)
        if (createsUsefulEmpty) {
            score += 45.0
            reasons += "create-empty"
        } else if (emptyAfter < emptyBefore) {
            // Prefer filling empties with kings only; already enforced by rules.
            score += 10.0
            reasons += "use-empty"
        }

        when (move) {
            is Move.WasteToTableau -> {
                score += 25.0
                reasons += "clear-waste"
                val wasteCard = before.wasteTop()
                if (wasteCard?.rank == Rank.Ace || wasteCard?.rank == Rank.Two) {
                    // In draw-3, immediately parking a low waste card exposes the
                    // next waste card while preserving tableau reveal moves.
                    score += 125.0
                    reasons += "unlock-low-waste"
                }
            }
            is Move.WasteToFoundation -> {
                score += 25.0
                reasons += "clear-waste"
            }
            Move.DrawStock -> {
                score += 5.0
                reasons += "draw"
                // Prefer drawing when no productive play exists; already relative.
            }
            Move.RecycleWaste -> {
                score -= 5.0
                reasons += "recycle"
            }
            is Move.TableauToTableau -> {
                if (createsUsefulEmpty) {
                    score += 35.0
                    reasons += "king-setup"
                }
                // A tableau shuffle that exposes no card is usually busywork.
                // Defer it behind drawing from stock or any productive move.
                if (revealed == 0 && foundationDelta == 0 &&
                    !createsUsefulEmpty
                ) {
                    score -= 180.0
                    reasons += "defer-no-reveal-stack"
                }
            }
            else -> Unit
        }

        // Prefer states with more face-up cards playable.
        score += after.tableau.count { col -> col.lastOrNull()?.faceUp == true } * 0.5

        // Tiny look-ahead: reward moves that unlock another reveal/foundation.
        val followUpBonus = MoveGenerator.generate(after).maxOfOrNull { follow ->
            val followState = KlondikeRules.apply(after, follow) ?: return@maxOfOrNull 0.0
            val moreReveal = after.hiddenTableauCount() - followState.hiddenTableauCount()
            val moreFound = followState.foundationCount() - after.foundationCount()
            moreReveal * 20.0 + moreFound * 10.0
        } ?: 0.0
        if (followUpBonus > 0) {
            score += followUpBonus
            reasons += "lookahead+$followUpBonus"
        }

        // Strongly discourage immediate undo cycles of pure tableau swaps.
        if (isTrivialReversible(before, after, move)) {
            score -= 40.0
            reasons += "reversible"
        }

        if (reasons.isEmpty()) reasons += "neutral"
        return score to reasons.joinToString(",")
    }

    private fun movedFoundationCard(before: GameState, move: Move): Card? = when (move) {
        is Move.TableauToFoundation -> before.tableau[move.fromColumn].lastOrNull()
        is Move.WasteToFoundation -> before.wasteTop()
        else -> null
    }

    private fun createsKingOpportunity(
        before: GameState,
        after: GameState,
        move: Move.TableauToTableau
    ): Boolean {
        val emptied = before.tableau[move.fromColumn].isNotEmpty() &&
            after.tableau[move.fromColumn].isEmpty()
        if (!emptied) return false
        val emptyColumn = move.fromColumn
        return MoveGenerator.generate(after).any { follow ->
            when (follow) {
                is Move.TableauToTableau ->
                    follow.toColumn == emptyColumn &&
                        follow.fromColumn != move.toColumn &&
                        after.tableau[follow.fromColumn]
                            .getOrNull(follow.startIndex)
                            ?.rank == Rank.King
                is Move.WasteToTableau ->
                    follow.toColumn == emptyColumn &&
                        after.wasteTop()?.rank == Rank.King
                else -> false
            }
        }
    }

    private fun isTrivialReversible(
        before: GameState,
        after: GameState,
        move: Move
    ): Boolean {
        if (move !is Move.TableauToTableau) return false
        val undoCandidates = MoveGenerator.generate(after).filterIsInstance<Move.TableauToTableau>()
        return undoCandidates.any { undo ->
            KlondikeRules.apply(after, undo)?.tableau == before.tableau
        }
    }
}
