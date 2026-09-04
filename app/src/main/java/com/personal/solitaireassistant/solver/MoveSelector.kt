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
 *
 * [wasteCycleStuck] is a live-play session flag (two idle stock/waste cycles
 * with no waste card played — see [WasteCycleStuckTracker]). While true, the
 * scorer prefers tableau peels and foundation-to-tableau pulls that unlock a
 * receiver over another empty recycle, and stops discounting an
 * already-exposed tableau/waste card's straight trip to foundation just
 * because it wouldn't also reveal a hidden card or isn't Baker's-rule
 * "safe" yet ([HOLD_UNBALANCED_FOUNDATION], [isTableauUsefulLowCard]) — both
 * cautions exist to keep options open for later progress, which isn't worth
 * paying for once nothing else is progressing. Default false keeps unit
 * tests and early-game play on the original one-ply weights.
 *
 * Among several legal card moves, [exposedOpenUnlock] and a 2-ply card
 * follow-up use the currently known face-up cards plus [KlondikeRules] to
 * prefer the line that unlocks a foundation or stack next. Those bonuses
 * stay small enough that a no-reveal rearrange still loses to draw.
 */
object MoveSelector {
    /** Productive moves at or above this score bypass [avoidStates] draw fallback. */
    const val PRODUCTIVE_MOVE_MIN_SCORE = 80.0

    private const val REVEAL_DEPTH_BONUS = 12.0
    private const val KING_FAMILY_BONUS = 8.0
    private const val WASTE_UNLOCK_THRESHOLD = 50.0
    private const val MINIMAL_MOVE_PENALTY = 15.0
    private const val ACE_ON_TABLEAU_PENALTY = 150.0
    private const val HOLD_UNBALANCED_FOUNDATION = 2.0
    /** Enough to beat recycle (~0 to −20) while stuck; same order as a real reveal. */
    private const val UNSTUCK_PEEL_BONUS = 120.0
    private const val UNSTUCK_FOUNDATION_PULL_BONUS = 120.0
    /** Ranking-only: newly exposed known card can go to foundation. Cannot beat draw vs −180. */
    private const val OPEN_UNLOCK_FOUNDATION = 25.0
    /** Ranking-only: newly exposed known card can stack on another open top. */
    private const val OPEN_UNLOCK_STACK = 15.0
    private const val FOLLOW_REVEAL = 20.0
    private const val FOLLOW_FOUNDATION = 10.0
    private const val LOOKAHEAD2_SCALE = 0.5

    fun rankedMoves(
        state: GameState,
        rejectedFingerprints: Set<String> = emptySet(),
        wasteCycleStuck: Boolean = false,
        moveFilter: (Move) -> Boolean = { true }
    ): List<ScoredMove> {
        val rejectedCardMoves = rejectedFingerprints.filterNot {
            MoveFingerprint.isStockFallback(it)
        }.toSet()
        return scoreAll(state, wasteCycleStuck)
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
        wasteCycleStuck: Boolean = false,
        moveFilter: (Move) -> Boolean = { true }
    ): ScoredMove? = pickBestFromRanked(
        ranked = rankedMoves(state, rejectedFingerprints, wasteCycleStuck, moveFilter),
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

    fun scoreAll(state: GameState, wasteCycleStuck: Boolean = false): List<ScoredMove> {
        return MoveGenerator.generate(state).map { move ->
            val next = KlondikeRules.apply(state, move)
                ?: return@map ScoredMove(move, Double.NEGATIVE_INFINITY, "illegal")
            val (score, why) = scoreTransition(state, next, move, wasteCycleStuck)
            ScoredMove(move, score, why)
        }
    }

    private fun scoreTransition(
        before: GameState,
        after: GameState,
        move: Move,
        wasteCycleStuck: Boolean
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
            // Restraint on a low card exists to preserve it as a tableau
            // bridge for eventually exposing something buried behind it.
            // Once every hidden card is already face-up there is nothing
            // left to expose, so the bridge no longer earns its keep -
            // stop deferring and let the card go up. Same logic applies once
            // the game is wasteCycleStuck: a bridge that isn't unlocking
            // anything while the stock keeps cycling empty isn't earning its
            // keep either, hidden cards elsewhere or not.
            if (card != null &&
                before.hiddenTableauCount() > 0 &&
                !wasteCycleStuck &&
                isTableauUsefulLowCard(before, card)
            ) {
                foundationScore = if (safe) 25.0 else 5.0
                reasons += "defer-low-foundation"
            }
            // Baker's-rule caution (don't found an "unsafe" card that reveals
            // nothing - it might be needed as a landing spot later) is only
            // worth paying for while something else is still progressing.
            // A card sitting at the bottom of its column (revealed == 0,
            // nothing left to reveal there) with wasteCycleStuck already
            // true is exactly the "lots of low cards could go up but the
            // arrow just keeps drawing" pattern - the discount here used to
            // crush the score to 2.0, below a plain draw's +5, keeping it
            // buried forever regardless of whether the caution ever pays off.
            if (!safe && revealed == 0 && !wasteCycleStuck) {
                foundationScore = HOLD_UNBALANCED_FOUNDATION
                reasons += "hold-unbalanced"
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

        val unstuckPeel = wasteCycleStuck &&
            move is Move.TableauToTableau &&
            isUnstuckTableauPeel(before, after, move)
        val unstuckFoundationPull = wasteCycleStuck &&
            move is Move.FoundationToTableau &&
            foundationPullCreatesReceiver(before, after, move)

        when (move) {
            is Move.WasteToTableau -> {
                score += 25.0
                reasons += "clear-waste"
                val wasteCard = before.wasteTop()
                val target = before.tableauTop(move.toColumn)
                if (wasteCard?.rank == Rank.Ace) {
                    score -= ACE_ON_TABLEAU_PENALTY
                    reasons += "ace-on-tableau"
                } else {
                    if (wasteCard != null &&
                        target != null &&
                        wasteCard.rank.isOneBelow(target.rank)
                    ) {
                        score += 130.0
                        reasons += "direct-stack"
                    }
                    if (wasteCard?.rank == Rank.Two) {
                        score += 125.0
                        reasons += "unlock-low-waste"
                    }
                }
            }
            is Move.WasteToFoundation -> {
                score += 25.0
                reasons += "clear-waste"
            }
            is Move.FoundationToTableau -> {
                // A last-resort move: pulling a card back off a foundation only
                // makes sense when it genuinely unlocks progress elsewhere
                // (a reveal, an unstuck run). The reveal/lookahead bonuses
                // above already reward that; this flat cost keeps it from
                // ever winning just because it happens to be *a* legal move.
                if (unstuckFoundationPull) {
                    score += UNSTUCK_FOUNDATION_PULL_BONUS
                    reasons += "unstuck-foundation-pull"
                } else {
                    score -= 60.0
                    reasons += "pull-from-foundation"
                }
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
                if (wasteCycleStuck) {
                    score -= 40.0
                    reasons += "defer-idle-recycle"
                }
            }
            is Move.TableauToTableau -> {
                val moving = before.tableau[move.fromColumn].getOrNull(move.startIndex)
                if (moving?.rank == Rank.Ace) {
                    score -= ACE_ON_TABLEAU_PENALTY
                    reasons += "ace-on-tableau"
                }
                if (createsUsefulEmpty) {
                    score += 35.0
                    reasons += "king-setup"
                }
                if (unstuckPeel) {
                    score += UNSTUCK_PEEL_BONUS
                    val depth = before.hiddenInColumn(move.fromColumn)
                    if (depth > 0) {
                        score += depth * REVEAL_DEPTH_BONUS
                        reasons += "unstuck-peel+deep-$depth"
                    } else {
                        reasons += "unstuck-peel"
                    }
                } else if (revealed == 0 && foundationDelta == 0 && !createsUsefulEmpty) {
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

        // Same unlock idea as the stuck peel, used for ranking whenever
        // several legal card moves exist: what do the now-open cards allow
        // under Klondike rules (this ply + two follow-ups). Modest weights
        // so a no-reveal rearrange still loses to draw.
        exposedOpenUnlock(before, after, move)?.let { (unlockScore, unlockWhy) ->
            score += unlockScore
            reasons += unlockWhy
        }
        val (followUpBonus, followState) = bestCardFollowUp(after)
        if (followUpBonus > 0) {
            score += followUpBonus
            reasons += "lookahead+$followUpBonus"
            if (move is Move.WasteToTableau && followUpBonus >= FOLLOW_REVEAL) {
                score += 40.0
                reasons += "waste-unlocks"
            }
            followState?.let { mid ->
                val second = bestCardFollowUp(mid).first * LOOKAHEAD2_SCALE
                if (second > 0) {
                    score += second
                    reasons += "lookahead2+$second"
                }
            }
        }

        if (isTrivialReversible(before, after, move)) {
            // An unstuck foundation pull that creates a receiver is not churn
            // even if the card could go straight back — the point is the next
            // card landing on it. Keep the reversible penalty only otherwise.
            if (!unstuckFoundationPull) {
                score -= 40.0
                reasons += "reversible"
            }
        }

        if (reasons.isEmpty()) reasons += "neutral"
        return score to reasons.joinToString(",")
    }

    /**
     * Top-card peel from a hidden-bearing column that exposes a card able to
     * go to foundation or stack onto another tableau top — the 3♦→4♣ / 4♠→3♠
     * line when waste cycling has already failed twice.
     */
    private fun isUnstuckTableauPeel(
        before: GameState,
        after: GameState,
        move: Move.TableauToTableau
    ): Boolean {
        val from = before.tableau[move.fromColumn]
        if (before.hiddenInColumn(move.fromColumn) <= 0) return false
        if (move.startIndex != from.lastIndex) return false
        return newlyExposedCard(before, after, move.fromColumn)
            ?.let { exposedUnlockKind(after, it, setOf(move.fromColumn, move.toColumn)) } != null
    }

    /**
     * Best non-draw follow-up using only known open cards and [KlondikeRules].
     * Reveal and foundation are the same units the old 1-ply lookahead used.
     */
    private fun bestCardFollowUp(state: GameState): Pair<Double, GameState?> {
        var best = 0.0
        var bestState: GameState? = null
        for (follow in MoveGenerator.generate(state)) {
            if (follow is Move.DrawStock || follow is Move.RecycleWaste) continue
            val followState = KlondikeRules.apply(state, follow) ?: continue
            val moreReveal = state.hiddenTableauCount() - followState.hiddenTableauCount()
            val moreFound = followState.foundationCount() - state.foundationCount()
            val value = moreReveal * FOLLOW_REVEAL + moreFound * FOLLOW_FOUNDATION
            if (value > best) {
                best = value
                bestState = followState
            }
        }
        return best to bestState
    }

    /**
     * After this move, a newly visible known card can legally found or stack —
     * the same check as the stuck peel, scored as a tie-break among card moves.
     */
    private fun exposedOpenUnlock(
        before: GameState,
        after: GameState,
        move: Move
    ): Pair<Double, String>? {
        val (col, exclude) = when (move) {
            is Move.TableauToTableau ->
                move.fromColumn to setOf(move.fromColumn, move.toColumn)
            is Move.TableauToFoundation ->
                move.fromColumn to setOf(move.fromColumn)
            else -> return null
        }
        val exposed = newlyExposedCard(before, after, col) ?: return null
        return when (exposedUnlockKind(after, exposed, exclude)) {
            "foundation" -> OPEN_UNLOCK_FOUNDATION to "open-unlock-foundation"
            "stack" -> OPEN_UNLOCK_STACK to "open-unlock-stack"
            else -> null
        }
    }

    private fun newlyExposedCard(before: GameState, after: GameState, col: Int): Card? {
        val exposed = after.tableauTop(col) ?: return null
        if (!exposed.faceUp || !exposed.known) return null
        val previousTop = before.tableauTop(col)
        if (previousTop != null && previousTop.id == exposed.id && previousTop.faceUp) return null
        return exposed
    }

    private fun exposedUnlockKind(
        state: GameState,
        exposed: Card,
        excludeColumns: Set<Int>
    ): String? {
        if (state.foundations.any { exposed.canPlaceOnFoundation(it.lastOrNull()) }) {
            return "foundation"
        }
        for (to in state.tableau.indices) {
            if (to in excludeColumns) continue
            if (exposed.canStackOnTableau(state.tableauTop(to))) return "stack"
        }
        return null
    }

    /**
     * Pulling a foundation card onto tableau creates a landing spot for some
     * other card (waste, or a tableau top sitting on a hidden column).
     */
    private fun foundationPullCreatesReceiver(
        before: GameState,
        after: GameState,
        move: Move.FoundationToTableau
    ): Boolean {
        val pulled = after.tableauTop(move.toColumn) ?: return false
        before.wasteTop()?.let { waste ->
            if (waste.canStackOnTableau(pulled)) return true
        }
        for (col in before.tableau.indices) {
            if (col == move.toColumn) continue
            if (before.hiddenInColumn(col) <= 0) continue
            val top = before.tableauTop(col) ?: continue
            if (top.canStackOnTableau(pulled)) return true
        }
        return false
    }

    private fun revealedColumn(before: GameState, after: GameState): Int? {
        for (col in before.tableau.indices) {
            if (after.hiddenInColumn(col) < before.hiddenInColumn(col)) return col
        }
        return null
    }

    private fun isTableauUsefulLowCard(state: GameState, card: Card): Boolean {
        if (card.rank != Rank.Two && card.rank != Rank.Three) return false

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
            if (wasteCard?.rank == Rank.Two) {
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
    ): Boolean = when (move) {
        is Move.TableauToTableau -> {
            val undoCandidates = MoveGenerator.generate(after).filterIsInstance<Move.TableauToTableau>()
            undoCandidates.any { undo ->
                KlondikeRules.apply(after, undo)?.tableau == before.tableau
            }
        }
        is Move.FoundationToTableau -> {
            // Pulling a card off a foundation and immediately being able to
            // send it right back to the same spot, with nothing else on the
            // board changed, is pure churn - the "pull-from-foundation"
            // penalty above already discourages it, but a move that also
            // reveals a card or otherwise helps still needs this to avoid
            // treating the round-trip itself as free progress.
            val undoCandidates = MoveGenerator.generate(after).filterIsInstance<Move.TableauToFoundation>()
            undoCandidates.any { undo ->
                val undone = KlondikeRules.apply(after, undo)
                undone?.tableau == before.tableau && undone.foundations == before.foundations
            }
        }
        else -> false
    }
}
