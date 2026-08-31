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
        visualChangeStreak: Int = 1,
        /**
         * True when this frame's own board read is self-inconsistent -
         * GameStateDetector.tableauRunConsistencyDiagnostics found a
         * resolved tableau run whose adjacent cards don't form a legal
         * descending/alternating-color sequence. Real device log evidence:
         * a King-Queen-Jack tableau run toggled between a valid reading and
         * a self-flagged-broken one roughly every 800ms on an otherwise
         * completely static board (no player action), and the arrow
         * flickered between two different moves in lockstep with it -
         * because the *board's own scoring* genuinely changed which moves
         * were legal each time, this wasn't something the existing
         * pending-streak confirmation (below) could catch: several
         * consecutive frames would agree with each other on a bad reading,
         * satisfying the confirmation, before flipping to several frames
         * agreeing on a good one. Defaults to false so callers that don't
         * pass it keep prior behavior exactly.
         */
        hasRunConsistencyViolation: Boolean = false
    ): Result {
        if (previous == null || best.move == previous.move) {
            return Result(best, idle)
        }
        if (hasRunConsistencyViolation && ranked.any { it.move == previous.move }) {
            // Don't let a frame we already know is internally broken decide
            // whether to switch away from a move we can still legally show.
            // State is left untouched (not advanced or reset) so the next
            // clean frame's pending-streak tracking picks up exactly where
            // this one found it, as if this frame had never run. If even
            // the previous move has vanished under this broken reading,
            // fall through to the normal handling below instead - there's
            // nothing safe left to keep showing, so the existing streak-
            // based confirmation is the best available signal.
            return Result(
                display = null,
                state = state,
                holdReason = "HOLD prev=${previous.move.label} kept (raw=${best.move.label} " +
                    "ignored: tableau run-consistency violation this frame)"
            )
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
