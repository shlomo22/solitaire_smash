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
        avoidStates: Collection<GameState> = emptyList(),
        rejectedFingerprints: Set<String> = emptySet()
    ): ScoredMove? {
        val rejectedCardMoves = rejectedFingerprints.filterNot {
            MoveFingerprint.isStockFallback(it)
        }.toSet()
        val ranked = scoreAll(state)
            .filter { MoveFingerprint.of(state, it.move) !in rejectedCardMoves }
            .sortedWith(
                compareBy<ScoredMove> { it.score }
                    .thenBy { it.move.label }
                    .reversed()
            )
        if (ranked.isEmpty()) return null

        val productive = ranked.filter { candidate ->
            when (val move = candidate.move) {
                is Move.DrawStock, Move.RecycleWaste -> false
                else -> {
                    val next = KlondikeRules.apply(state, move) ?: return@filter false
                    isProductiveMove(state, next, move)
                }
            }
        }
        val stockMoves = ranked.filter {
            it.move is Move.DrawStock || it.move is Move.RecycleWaste
        }
        val candidates = when {
            productive.isNotEmpty() -> productive
            stockMoves.isNotEmpty() -> stockMoves
            else -> emptyList()
        }
        if (candidates.isEmpty()) return null

        val pick = { list: List<ScoredMove> ->
            if (avoidStates.isEmpty()) {
                list.firstOrNull()
            } else {
                list.firstOrNull { candidate ->
                    val next = KlondikeRules.apply(state, candidate.move) ?: return@firstOrNull false
                    next !in avoidStates
                } ?: list.firstOrNull {
                    it.move is Move.DrawStock || it.move is Move.RecycleWaste
                }
            }
        }
        val chosen = pick(candidates)
        if (chosen != null) return chosen
        // If every card hint was rejected/avoided, still point at the stock.
        return ranked.firstOrNull {
            it.move is Move.DrawStock || it.move is Move.RecycleWaste
        }
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

        val uselessTopShuffle =
            move is Move.TableauToTableau && isUselessTopShuffle(before, move)
        val revealed = if (uselessTopShuffle) {
            0
        } else {
            before.hiddenTableauCount() - after.hiddenTableauCount()
        }
        if (revealed > 0) {
            score += 120.0 * revealed
            reasons += "reveal+$revealed"
        }
        if (uselessTopShuffle) {
            score -= 280.0
            reasons += "useless-top-shuffle"
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
            is Move.FoundationToTableau -> {
                // Useful when a low foundation card unlocks tableau play; otherwise soft.
                score += 12.0
                reasons += "foundation-to-tableau"
                val card = before.foundations[move.fromFoundation].lastOrNull()
                if (card?.rank == Rank.Two || card?.rank == Rank.Ace) {
                    score += 20.0
                    reasons += "park-low-foundation"
                }
            }
            Move.DrawStock -> {
                score += 5.0
                reasons += "draw"
                if (hasTableauRevealMove(before)) {
                    score -= 300.0
                    reasons += "defer-draw-for-reveal"
                } else if (hasProductiveTableauMove(before)) {
                    score -= 120.0
                    reasons += "defer-draw-for-tableau"
                } else if (hasWastePlay(before)) {
                    score -= 100.0
                    reasons += "defer-draw-for-waste"
                }
            }
            Move.RecycleWaste -> {
                score -= 15.0
                reasons += "recycle"
                if (hasProductiveTableauMove(before) ||
                    hasTableauRevealMove(before) ||
                    hasWastePlay(before)
                ) {
                    score -= 80.0
                    reasons += "defer-recycle-for-tableau"
                }
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
        if (!uselessTopShuffle) {
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

    private fun hasTableauRevealMove(state: GameState): Boolean =
        MoveGenerator.generate(state).any { move ->
            if (move !is Move.TableauToTableau) return@any false
            val next = KlondikeRules.apply(state, move) ?: return@any false
            next.hiddenTableauCount() < state.hiddenTableauCount()
        }

    private fun hasProductiveTableauMove(state: GameState): Boolean =
        MoveGenerator.generate(state).any { move ->
            when (move) {
                is Move.TableauToTableau -> {
                    val next = KlondikeRules.apply(state, move) ?: return@any false
                    next.hiddenTableauCount() < state.hiddenTableauCount() ||
                        next.foundationCount() > state.foundationCount() ||
                        (next.tableau.count { it.isEmpty() } >
                            state.tableau.count { it.isEmpty() })
                }
                is Move.TableauToFoundation -> true
                else -> false
            }
        }

    private fun hasWastePlay(state: GameState): Boolean =
        MoveGenerator.generate(state).any {
            it is Move.WasteToTableau || it is Move.WasteToFoundation
        }

    /**
     * A hint-worthy move exposes a hidden card, clears a tableau column, or
     * advances / clears waste or foundation piles.
     */
    private fun isProductiveMove(
        before: GameState,
        after: GameState,
        move: Move
    ): Boolean = when (move) {
        is Move.TableauToFoundation, is Move.WasteToFoundation, is Move.WasteToTableau,
        is Move.FoundationToTableau -> true
        is Move.TableauToTableau ->
            after.hiddenTableauCount() < before.hiddenTableauCount() ||
                after.tableau.count { it.isEmpty() } > before.tableau.count { it.isEmpty() }
        else -> false
    }

    /**
     * Moving the top card off a column whose card below is already face-up never
     * reveals anything — e.g. sliding 4♦ from 5♣,4♦ onto another 5♣.
     */
    private fun isUselessTopShuffle(before: GameState, move: Move.TableauToTableau): Boolean {
        val column = before.tableau[move.fromColumn]
        if (column.isEmpty()) return false
        if (move.startIndex != column.lastIndex) return false
        if (move.startIndex <= 0) return false
        if (!column[move.startIndex - 1].faceUp) return false
        if (before.tableau[move.toColumn].isEmpty()) return false
        return true
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
