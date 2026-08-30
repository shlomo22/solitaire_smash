package com.personal.solitaireassistant.pipeline

import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.ScoredMove

/**
 * Damps single-frame flicker in which move is "best". See
 * [AnalysisPipeline.applySuggestionStickiness] for the device-log history.
 *
 * [Result.display] is null when the overlay should be left untouched this frame.
 */
internal object SuggestionStickiness {
    data class State(
        val pendingCandidate: Move? = null,
        val pendingStreak: Int = 0
    )

    data class Result(
        val display: ScoredMove?,
        val state: State,
        val holdReason: String? = null
    )

    private val idle = State()

    fun apply(
        previous: ScoredMove?,
        best: ScoredMove,
        ranked: List<ScoredMove>,
        boardVisuallyChanged: Boolean,
        state: State,
        /**
         * How many consecutive frames in a row have come back pixel-changed
         * (1 = this is the first changed frame since a static frame; 2+ = the
         * board is still visually mid-transition, e.g. a multi-frame card-
         * slide animation). Only the *first* changed frame gets the
         * immediate-adopt fast path below - a real device complaint was the
         * arrow flickering to a wrong move and then correcting a moment
         * later, which traces to this function previously treating every
         * still-changing frame as an equally valid "the move just landed"
         * signal and re-adopting whatever that frame's possibly-noisy read
         * produced, with no cross-frame confirmation at all for as long as
         * pixels kept moving. Defaults to 1 (first-changed-frame) so callers
         * that don't track a streak keep the original always-immediate
         * behavior.
         */
        visualChangeStreak: Int = 1
    ): Result {
        if (previous == null || best.move == previous.move) {
            return Result(best, idle)
        }
        // Pixel-level "the board changed" is for adopting a *new card play*
        // after the user already moved. Draw/recycle is the always-legal
        // fallback — a one-frame waste or target misread makes the real
        // play vanish and this would otherwise snap the arrow to the stock
        // (seen on device: 9D→10S flashes, then Draw Stock sticks).
        if (boardVisuallyChanged &&
            visualChangeStreak <= 1 &&
            (!best.move.isStockFallbackHint() || previous.move.isStockFallbackHint())
        ) {
            return Result(best, idle)
        }
        val nextState = if (state.pendingCandidate == best.move) {
            State(best.move, state.pendingStreak + 1)
        } else {
            State(best.move, 1)
        }
        if (nextState.pendingStreak >= 2) {
            return Result(best, idle)
        }
        val stillRanked = ranked.firstOrNull { it.move == previous.move }
        if (stillRanked == null) {
            val previousFromWaste = previous.move.isWasteSourcedCardHint()
            val newWastePlay = best.move.isWasteSourcedCardHint()
            if (previousFromWaste && newWastePlay) {
                // Player drew; the new waste card has its own play.
                return Result(best, idle)
            }
            return Result(
                display = null,
                state = nextState,
                holdReason = "HOLD prev=${previous.move.label} vanished from ranked " +
                    "(raw=${best.move.label} streak=${nextState.pendingStreak}/2)"
            )
        }
        return Result(stillRanked, nextState)
    }
}

private fun Move.isStockFallbackHint(): Boolean =
    this is Move.DrawStock || this is Move.RecycleWaste

private fun Move.isWasteSourcedCardHint(): Boolean =
    this is Move.WasteToTableau || this is Move.WasteToFoundation
