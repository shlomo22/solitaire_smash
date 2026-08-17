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
    /** Productive moves at or above this score bypass [avoidStates] draw fallback. */
    const val PRODUCTIVE_MOVE_MIN_SCORE = 80.0

    private const val REVEAL_DEPTH_BONUS = 12.0
    private const val KING_FAMILY_BONUS = 8.0
    private const val WASTE_UNLOCK_THRESHOLD = 50.0
    private const val MINIMAL_MOVE_PENALTY = 15.0

    fun rankedMoves(
        state: GameState,
        rejectedFingerprints: Set<String> = emptySet(),
        moveFilter: (Move) -> Boolean = { true }
    ): List<ScoredMove> {
        val rejectedCardMoves = rejectedFingerprints.filterNot {
            MoveFingerprint.isStockFallback(it)
        }.toSet()
        return scoreAll(state)
            .filter { MoveFingerprint.of(state, it.move) !in rejectedCardMoves }
            .filter { moveFilter(it.move) }
            .sortedWith(
                compareBy<ScoredMove> { it.score }
                    .thenBy { it.move.label }
                    .reversed()
            )
    }

    fun bestMove(
        state: GameState,
        avoidStates: Collection<GameState> = emptyList(),
        rejectedFingerprints: Set<String> = emptySet(),
        moveFilter: (Move) -> Boolean = { true }
    ): ScoredMove? = pickBestFromRanked(
        ranked = rankedMoves(state, rejectedFingerprints, moveFilter),
        state = state,
        avoidStates = avoidStates
    )

    fun pickBestFromRanked(
        ranked: List<ScoredMove>,
        state: GameState,
        avoidStates: Collection<GameState> = emptyList()
    ): ScoredMove? {
        if (avoidStates.isEmpty()) return ranked.firstOrNull()

        val best = ranked.firstOrNull() ?: return null
        val bestNext = KlondikeRules.apply(state, best.move)
        if (bestNext != null &&
            bestNext in avoidStates &&
            best.score >= PRODUCTIVE_MOVE_MIN_SCORE &&
            !MoveFingerprint.isStockFallback(MoveFingerprint.of(state, best.move))
        ) {
            return best
        }

        ranked.firstOrNull { candidate ->
            val next = KlondikeRules.apply(state, candidate.move) ?: return@firstOrNull false
            next !in avoidStates
        }?.let { return it }

        return ranked.firstOrNull { candidate ->
            candidate.score >= PRODUCTIVE_MOVE_MIN_SCORE &&
                !MoveFingerprint.isStockFallback(MoveFingerprint.of(state, candidate.move))
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
            revealedColumn(before, after)?.let { col ->
                val depthBonus = before.hiddenInColumn(col) * REVEAL_DEPTH_BONUS
                score += depthBonus
                reasons += "deep-col+$depthBonus"
            }
        }

        val foundationDelta = after.foundationCount() - before.foundationCount()
        if (foundationDelta > 0) {
            val card = movedFoundationCard(before, move)
            val safe = card != null && KlondikeRules.isSafeFoundationMove(before, card)
            var foundationScore = if (safe) 80.0 else 15.0
            if (card != null && isTableauUsefulLowCard(before, card)) {
                foundationScore = if (safe) 25.0 else 5.0
                reasons += "defer-low-foundation"
            }
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
            val kingFamily = bestKingFamilyForEmpty(after, move.fromColumn)
            if (kingFamily > 0) {
                score += kingFamily * KING_FAMILY_BONUS
                reasons += "king-family+$kingFamily"
            }
        } else if (emptyAfter < emptyBefore) {
            score += 10.0
            reasons += "use-empty"
            if (move is Move.TableauToTableau && before.tableau[move.toColumn].isEmpty()) {
                val family = hiddenFamilyBehindKing(before, move.fromColumn, move.startIndex)
                if (family > 0) {
                    score += family * KING_FAMILY_BONUS
                    reasons += "king-family+$family"
                } else if (hasKingWithHiddenFamily(before, move.fromColumn)) {
                    score -= 20.0
                    reasons += "wrong-king"
                }
            }
        }

        when (move) {
            is Move.WasteToTableau -> {
                score += 25.0
                reasons += "clear-waste"
                val wasteCard = before.wasteTop()
                val target = before.tableauTop(move.toColumn)
                if (wasteCard != null &&
                    target != null &&
                    wasteCard.rank.isOneBelow(target.rank)
                ) {
                    score += 130.0
                    reasons += "direct-stack"
                }
                if (wasteCard?.rank == Rank.Ace || wasteCard?.rank == Rank.Two) {
                    score += 125.0
                    reasons += "unlock-low-waste"
                }
            }
            is Move.WasteToFoundation -> {
                score += 25.0
                reasons += "clear-waste"
            }
            Move.DrawStock -> {
                if (hasProductiveWasteMove(before)) {
                    score -= 30.0
                    reasons += "defer-draw-waste"
                } else {
                    score += 5.0
                    reasons += "draw"
                }
            }
            Move.RecycleWaste -> {
                score -= 5.0 + before.recyclesUsed * 12.0
                reasons += "recycle"
            }
            is Move.TableauToTableau -> {
                if (createsUsefulEmpty) {
                    score += 35.0
                    reasons += "king-setup"
                }
                if (revealed == 0 && foundationDelta == 0 && !createsUsefulEmpty) {
                    score -= 180.0
                    reasons += "defer-no-reveal-stack"
                }
                if (revealed > 0) {
                    val cardsMoved = before.tableau[move.fromColumn].size - move.startIndex
                    if (cardsMoved > 1) {
                        score -= (cardsMoved - 1) * MINIMAL_MOVE_PENALTY
                        reasons += "minimal-move-$cardsMoved"
                    }
                    val column = before.tableau[move.fromColumn]
                    val topOnlyIndex = column.lastIndex
                    if (move.startIndex < topOnlyIndex) {
                        val topOnlyMove = Move.TableauToTableau(
                            move.fromColumn,
                            topOnlyIndex,
                            move.toColumn
                        )
                        val topOnlyNext = KlondikeRules.apply(before, topOnlyMove)
                        if (topOnlyNext != null) {
                            val topOnlyReveal =
                                before.hiddenTableauCount() - topOnlyNext.hiddenTableauCount()
                            if (topOnlyReveal >= revealed) {
                                score -= 25.0
                                reasons += "split-run"
                            }
                        }
                    }
                }
            }
            else -> Unit
        }

        score += after.tableau.count { col -> col.lastOrNull()?.faceUp == true } * 0.5

        val followUpBonus = MoveGenerator.generate(after).maxOfOrNull { follow ->
            val followState = KlondikeRules.apply(after, follow) ?: return@maxOfOrNull 0.0
            val moreReveal = after.hiddenTableauCount() - followState.hiddenTableauCount()
            val moreFound = followState.foundationCount() - after.foundationCount()
            moreReveal * 20.0 + moreFound * 10.0
        } ?: 0.0
        if (followUpBonus > 0) {
            score += followUpBonus
            reasons += "lookahead+$followUpBonus"
            if (move is Move.WasteToTableau && followUpBonus >= 20.0) {
                score += 40.0
                reasons += "waste-unlocks"
            }
        }

        if (isTrivialReversible(before, after, move)) {
            score -= 40.0
            reasons += "reversible"
        }

        if (reasons.isEmpty()) reasons += "neutral"
        return score to reasons.joinToString(",")
    }

    private fun revealedColumn(before: GameState, after: GameState): Int? {
        for (col in before.tableau.indices) {
            if (after.hiddenInColumn(col) < before.hiddenInColumn(col)) return col
        }
        return null
    }

    private fun isTableauUsefulLowCard(state: GameState, card: Card): Boolean {
        if (card.rank != Rank.Ace && card.rank != Rank.Two) return false

        val onTableau = state.tableau.indexOfFirst { it.lastOrNull() == card }
        if (onTableau >= 0) {
            val stacksOnto = MoveGenerator.generate(state).any { m ->
                when (m) {
                    is Move.WasteToTableau ->
                        state.tableauTop(m.toColumn) == card &&
                            state.wasteTop()?.canStackOnTableau(card) == true
                    is Move.TableauToTableau ->
                        state.tableauTop(m.toColumn) == card &&
                            state.tableau[m.fromColumn][m.startIndex].canStackOnTableau(card)
                    else -> false
                }
            }
            if (stacksOnto) return true

            return MoveGenerator.generate(state).any { m ->
                m is Move.TableauToTableau &&
                    m.fromColumn == onTableau &&
                    state.tableau[m.fromColumn].getOrNull(m.startIndex) == card &&
                    card.canStackOnTableau(state.tableauTop(m.toColumn))
            }
        }

        if (state.wasteTop() == card) {
            return MoveGenerator.generate(state).any { it is Move.WasteToTableau }
        }

        return false
    }

    private fun hiddenFamilyBehindKing(state: GameState, col: Int, startIndex: Int): Int =
        state.tableau[col].take(startIndex).count { !it.faceUp }

    private fun bestKingFamilyForEmpty(state: GameState, emptyColumn: Int): Int {
        return MoveGenerator.generate(state).mapNotNull { follow ->
            when (follow) {
                is Move.TableauToTableau -> {
                    if (follow.toColumn != emptyColumn) return@mapNotNull null
                    if (state.tableau[follow.fromColumn].getOrNull(follow.startIndex)?.rank != Rank.King) {
                        return@mapNotNull null
                    }
                    hiddenFamilyBehindKing(state, follow.fromColumn, follow.startIndex)
                }
                else -> null
            }
        }.maxOrNull() ?: 0
    }

    private fun hasKingWithHiddenFamily(state: GameState, excludeColumn: Int): Boolean {
        for (col in state.tableau.indices) {
            if (col == excludeColumn) continue
            val column = state.tableau[col]
            val firstFaceUp = column.indexOfFirst { it.faceUp }
            if (firstFaceUp < 0) continue
            for (start in firstFaceUp until column.size) {
                if (column[start].rank == Rank.King &&
                    hiddenFamilyBehindKing(state, col, start) > 0
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasProductiveWasteMove(state: GameState): Boolean {
        return MoveGenerator.generate(state).any { move ->
            if (move !is Move.WasteToTableau) return@any false
            var estimate = 25.0
            val wasteCard = state.wasteTop()
            val target = state.tableauTop(move.toColumn)
            if (wasteCard != null &&
                target != null &&
                wasteCard.rank.isOneBelow(target.rank)
            ) {
                estimate += 130.0
            }
            if (wasteCard?.rank == Rank.Ace || wasteCard?.rank == Rank.Two) {
                estimate += 125.0
            }
            estimate >= WASTE_UNLOCK_THRESHOLD
        }
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
