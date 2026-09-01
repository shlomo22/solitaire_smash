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
        val pendingStreak: Int = 0,
        /** Consecutive frames held via [MAX_VIOLATION_HOLD_FRAMES] below. */
        val violationHoldStreak: Int = 0
    )

    /**
     * Cap on how many consecutive frames a run-consistency violation can
     * freeze the display before giving up and falling through to normal
     * handling anyway. Round 6/7's oscillating-arrow bug needed about 2
     * consecutive held frames to fully suppress (the board flipped between
     * a valid and a self-flagged-broken reading roughly every 800ms). A
     * real pull right after the unconditional-freeze fix (v1.4.105) showed
     * the opposite failure mode: a *persistent* (non-oscillating) violation
     * on one board froze the arrow for 46 consecutive frames - 6.3 real
     * seconds - because the same two moves kept getting recomputed and
     * rejected every single detect() cycle with nothing ever resolving.
     * 4 comfortably covers the oscillation case with margin while cutting
     * off nowhere near that observed worst case. Not device-verified at
     * this specific value - the next lever to pull if 4 turns out to be
     * too short (oscillation still visible) or too long (still freezes
     * noticeably) is this constant, not the on/off freeze mechanism itself.
     */
    private const val MAX_VIOLATION_HOLD_FRAMES = 4

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
         *
         * v1.4.104's first attempt at this only held when [previous]'s move
         * was still present in [ranked], on the theory that a violation
         * usually leaves *some* safe fallback to keep showing. A real pull
         * (154 violations in one ~3-minute session) proved that wrong: the
         * same instability that trips the violation check almost always
         * knocks the currently-displayed move out of [ranked] at the same
         * moment too, so that "safe" branch essentially never fired and the
         * flicker continued unabated through the ordinary vanished-move
         * path below. v1.4.105 held unconditionally instead - any violation
         * freezes the display, full stop - which fixed the flicker but a
         * real pull right after showed the opposite failure: a persistent
         * (non-oscillating) violation froze the arrow for 46 consecutive
         * frames, 6.3 real seconds, on one board. See
         * [MAX_VIOLATION_HOLD_FRAMES] - the freeze is now bounded, giving up
         * and falling through to normal handling once it's held too long
         * rather than freezing indefinitely.
         */
        hasRunConsistencyViolation: Boolean = false
    ): Result {
        if (previous == null) {
            return Result(best, idle)
        }
        if (best.move == previous.move) {
            // A coincidental one-frame agreement between the raw candidate
            // and what's already shown must not silently wipe an
            // in-progress violationHoldStreak. Real device log evidence
            // (909c6d69-analysis.log, v1.4.106): on a persistently-violated
            // board, this exact branch fired 2-3 times mid-episode - each
            // time using the shared `idle` state below zeroed
            // violationHoldStreak even though hasRunConsistencyViolation was
            // still true that frame, so the counter kept restarting at 1
            // instead of accumulating toward MAX_VIOLATION_HOLD_FRAMES. The
            // reset was invisible in the log because best==previous produces
            // no new HOLD/ARROW line here. pendingCandidate/pendingStreak
            // still reset (this frame agrees with the display, so there's no
            // pending switch to track), but violationHoldStreak is preserved
            // whenever the board is still self-flagged broken this frame.
            return Result(
                best,
                if (hasRunConsistencyViolation) {
                    state.copy(pendingCandidate = null, pendingStreak = 0)
                } else {
                    idle
                }
            )
        }
        if (hasRunConsistencyViolation) {
            val heldStreak = state.violationHoldStreak + 1
            if (heldStreak <= MAX_VIOLATION_HOLD_FRAMES) {
                // Don't let a frame we already know is internally broken
                // decide whether to switch the display at all. pendingCandidate/
                // pendingStreak are left untouched so the normal streak logic
                // below picks up exactly where a run of violated frames left
                // it once one clears (or the cap below is hit).
                return Result(
                    display = null,
                    state = state.copy(violationHoldStreak = heldStreak),
                    holdReason = "HOLD prev=${previous.move.label} kept (raw=${best.move.label} " +
                        "ignored: tableau run-consistency violation this frame, " +
                        "streak=$heldStreak/$MAX_VIOLATION_HOLD_FRAMES)"
                )
            }
            // Held long enough - this isn't resolving on its own, so stop
            // freezing and fall through to normal handling below even
            // though the board is still self-admittedly broken. Better to
            // risk showing a wrong move than to freeze forever.
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
