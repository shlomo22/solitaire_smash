package com.personal.solitaireassistant.pipeline

import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.ScoredMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionStickinessTest {
    private val wasteToCol0 = ScoredMove(Move.WasteToTableau(0), 155.0, "clear-waste,direct-stack")
    private val wasteToCol2 = ScoredMove(Move.WasteToTableau(2), 155.0, "clear-waste,direct-stack")
    private val drawStock = ScoredMove(Move.DrawStock, 5.0, "draw")
    private val tableauMove = ScoredMove(Move.TableauToTableau(1, 0, 2), 132.0, "reveal+1")

    @Test
    fun firstSuggestionShowsImmediately() {
        val result = SuggestionStickiness.apply(
            previous = null,
            best = wasteToCol0,
            ranked = listOf(wasteToCol0, drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State()
        )
        assertEquals(wasteToCol0.move, result.display?.move)
    }

    @Test
    fun oneFrameDrawAfterWastePlayHoldsEvenWhenPixelsChanged() {
        val result = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = drawStock,
            ranked = listOf(drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State()
        )
        assertNull(result.display)
        assertEquals(Move.DrawStock, result.state.pendingCandidate)
        assertEquals(1, result.state.pendingStreak)
        assertTrue(result.holdReason!!.contains("Waste -> Tableau"))
    }

    @Test
    fun twoDrawFramesAfterWastePlayAdoptStock() {
        val first = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = drawStock,
            ranked = listOf(drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State()
        )
        val second = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = drawStock,
            ranked = listOf(drawStock),
            boardVisuallyChanged = true,
            state = first.state
        )
        assertEquals(Move.DrawStock, second.display?.move)
        assertEquals(0, second.state.pendingStreak)
    }

    @Test
    fun alternatingDrawDoesNotStealWastePlay() {
        val flicker = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = drawStock,
            ranked = listOf(drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State()
        )
        assertNull(flicker.display)
        val back = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = wasteToCol0,
            ranked = listOf(wasteToCol0, drawStock),
            boardVisuallyChanged = true,
            state = flicker.state
        )
        assertEquals(wasteToCol0.move, back.display?.move)
        assertEquals(0, back.state.pendingStreak)
    }

    @Test
    fun newWastePlayAfterDrawAdoptsImmediately() {
        val result = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = wasteToCol2,
            ranked = listOf(wasteToCol2, drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State()
        )
        assertEquals(wasteToCol2.move, result.display?.move)
    }

    @Test
    fun visualChangeAdoptsNewTableauPlayImmediately() {
        val result = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = tableauMove,
            ranked = listOf(tableauMove, drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State()
        )
        assertEquals(tableauMove.move, result.display?.move)
    }

    @Test
    fun vanishedTableauPlayHoldsOneFrame() {
        val result = SuggestionStickiness.apply(
            previous = tableauMove,
            best = drawStock,
            ranked = listOf(drawStock),
            boardVisuallyChanged = false,
            state = SuggestionStickiness.State()
        )
        assertNull(result.display)
        assertEquals(1, result.state.pendingStreak)
    }

    @Test
    fun firstChangedFrameStillAdoptsNewMoveImmediately() {
        // visualChangeStreak defaults to 1 (first changed frame) - mirrors
        // visualChangeAdoptsNewTableauPlayImmediately but pins the default
        // explicitly so a future signature change can't silently alter it.
        val result = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = tableauMove,
            ranked = listOf(tableauMove, drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State(),
            visualChangeStreak = 1
        )
        assertEquals(tableauMove.move, result.display?.move)
    }

    @Test
    fun stillAnimatingFrameDoesNotFlickerToNewMoveImmediately() {
        // A real device complaint: the arrow flickers to a wrong move and
        // then corrects a moment later. streak 2+ means the board is still
        // visually changing frame over frame (e.g. mid card-slide
        // animation) - a differing read on one of those frames should not
        // instantly replace what's already shown.
        val result = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = tableauMove,
            ranked = listOf(tableauMove, drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State(),
            visualChangeStreak = 2
        )
        assertNull(result.display)
        assertEquals(tableauMove.move, result.state.pendingCandidate)
        assertEquals(1, result.state.pendingStreak)
    }

    @Test
    fun stillAnimatingFrameAdoptsAfterTwoAgreeingReads() {
        val first = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = tableauMove,
            ranked = listOf(tableauMove, drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State(),
            visualChangeStreak = 2
        )
        val second = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = tableauMove,
            ranked = listOf(tableauMove, drawStock),
            boardVisuallyChanged = true,
            state = first.state,
            visualChangeStreak = 3
        )
        assertEquals(tableauMove.move, second.display?.move)
    }

    @Test
    fun runConsistencyViolationHoldsPreviousMoveWhenStillRanked() {
        // Real device log evidence: a tableau run toggled between a valid
        // reading and a self-flagged-broken one (GameStateDetector's own
        // adjacency check) roughly every 800ms on a static board, flickering
        // the arrow between two moves in lockstep. A violated frame must not
        // be allowed to switch the display even though boardVisuallyChanged
        // could be true and the streak logic alone wouldn't catch it (both
        // readings are internally consistent frame-to-frame, they just
        // alternate).
        val result = SuggestionStickiness.apply(
            previous = tableauMove,
            best = wasteToCol0,
            ranked = listOf(wasteToCol0, tableauMove),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State(),
            hasRunConsistencyViolation = true
        )
        assertNull(result.display)
        assertTrue(result.holdReason!!.contains("run-consistency"))
        // pendingCandidate/pendingStreak are left untouched, but the new
        // violation-hold streak advances to 1.
        assertEquals(SuggestionStickiness.State(violationHoldStreak = 1), result.state)
    }

    @Test
    fun runConsistencyViolationHoldsEvenWhenPreviousAlsoVanished() {
        // v1.4.104's first cut only held when `previous` was still in
        // `ranked`. A real pull (154 violations in one ~3-minute session)
        // showed that branch essentially never fires: the same instability
        // that trips the violation almost always knocks the displayed move
        // out of `ranked` at the same moment too, so the flicker continued
        // via the ordinary vanished-move path below. This holds regardless
        // of whether `previous` is still rankable.
        val result = SuggestionStickiness.apply(
            previous = tableauMove,
            best = drawStock,
            ranked = listOf(drawStock),
            boardVisuallyChanged = false,
            state = SuggestionStickiness.State(),
            hasRunConsistencyViolation = true
        )
        assertNull(result.display)
        assertTrue(result.holdReason!!.contains("run-consistency"))
        assertEquals(SuggestionStickiness.State(violationHoldStreak = 1), result.state)
    }

    @Test
    fun runConsistencyViolationFreezeIsBoundedThenFallsThrough() {
        // v1.4.105's unconditional freeze fixed the flicker but a real pull
        // right after showed the opposite failure: a *persistent* (non-
        // oscillating) violation froze the arrow for 46 consecutive frames -
        // 6.3 real seconds - because the same two moves kept getting
        // recomputed and rejected every detect() cycle with nothing ever
        // resolving. Drive the same violation 5 times in a row: the first 4
        // hold, the 5th must give up and fall through to normal handling
        // instead of freezing forever.
        var state = SuggestionStickiness.State()
        repeat(4) { i ->
            val result = SuggestionStickiness.apply(
                previous = tableauMove,
                best = drawStock,
                ranked = listOf(drawStock, tableauMove),
                boardVisuallyChanged = false,
                state = state,
                hasRunConsistencyViolation = true
            )
            assertNull("frame ${i + 1} should still be held", result.display)
            assertEquals(i + 1, result.state.violationHoldStreak)
            state = result.state
        }
        val fifth = SuggestionStickiness.apply(
            previous = tableauMove,
            best = drawStock,
            ranked = listOf(drawStock, tableauMove),
            boardVisuallyChanged = false,
            state = state,
            hasRunConsistencyViolation = true
        )
        // Falls through to normal handling: previous is still ranked, so the
        // ordinary (non-violation) logic keeps showing it for one more frame
        // while it builds its own confirmation streak - it does not adopt
        // drawStock outright on this exact frame either, but critically it
        // is no longer following the violation-hold path (no run-consistency
        // hold reason, and the display is no longer frozen indefinitely).
        assertEquals(0, fifth.state.violationHoldStreak)
        assertTrue(fifth.holdReason == null || !fifth.holdReason!!.contains("run-consistency"))
    }

    @Test
    fun runConsistencyViolationCooldownPreventsImmediateSecondEpisode() {
        // Real device log evidence (a862ed06-analysis.log): a genuine
        // tableau3 misread (Ten_Clubs -> Eight_Hearts, a rank-skip a legal
        // alternating-descending run can never produce) kept re-triggering
        // the violation check. The per-episode cap
        // (runConsistencyViolationFreezeIsBoundedThenFallsThrough) worked
        // correctly in isolation, but two capped episodes fired back-to-back
        // with zero gap between them, chaining into a ~3.9s stall at the
        // very end of a game. Drive one full 4-frame capped episode (as in
        // that test), then keep the violation flag true on every following
        // frame: the next VIOLATION_COOLDOWN_FRAMES frames must not produce
        // a new "run-consistency" hold, and only once the cooldown has fully
        // elapsed may a fresh capped episode begin.
        var state = SuggestionStickiness.State()
        repeat(4) {
            val result = SuggestionStickiness.apply(
                previous = tableauMove,
                best = drawStock,
                ranked = listOf(drawStock, tableauMove),
                boardVisuallyChanged = false,
                state = state,
                hasRunConsistencyViolation = true
            )
            state = result.state
        }
        val fallThrough = SuggestionStickiness.apply(
            previous = tableauMove,
            best = drawStock,
            ranked = listOf(drawStock, tableauMove),
            boardVisuallyChanged = false,
            state = state,
            hasRunConsistencyViolation = true
        )
        assertEquals(0, fallThrough.state.violationHoldStreak)
        assertEquals(2, fallThrough.state.violationCooldownRemaining)
        state = fallThrough.state

        repeat(2) { i ->
            val cooldownFrame = SuggestionStickiness.apply(
                previous = tableauMove,
                best = drawStock,
                ranked = listOf(drawStock, tableauMove),
                boardVisuallyChanged = false,
                state = state,
                hasRunConsistencyViolation = true
            )
            assertTrue(
                "cooldown frame ${i + 1} must not re-enter the freeze",
                cooldownFrame.holdReason == null || !cooldownFrame.holdReason!!.contains("run-consistency")
            )
            assertEquals(0, cooldownFrame.state.violationHoldStreak)
            assertEquals(1 - i, cooldownFrame.state.violationCooldownRemaining)
            state = cooldownFrame.state
        }

        // Cooldown fully elapsed - a still-violated, still-differing frame
        // may now start a fresh capped episode.
        val newEpisodeStart = SuggestionStickiness.apply(
            previous = tableauMove,
            best = drawStock,
            ranked = listOf(drawStock, tableauMove),
            boardVisuallyChanged = false,
            state = state,
            hasRunConsistencyViolation = true
        )
        assertNull(newEpisodeStart.display)
        assertEquals(1, newEpisodeStart.state.violationHoldStreak)
        assertTrue(newEpisodeStart.holdReason!!.contains("run-consistency"))
    }

    @Test
    fun persistentViolationCannotFreezeDisplayIndefinitely() {
        // Real device log (2026-09-03, 21:16:24-21:16:45): a persistently
        // self-inconsistent board froze one stale arrow for 21 consecutive
        // seconds. Two defects combined. The capped frame delegated to
        // resolveNormally, and because the displayed move was *also* missing
        // from `ranked` (the usual case when a violation fires), that path
        // returned its own vanished-from-ranked hold - so the cap produced no
        // visible progress at all. AnalysisPipeline then discarded
        // violationCooldownRemaining between frames, so the next frame
        // re-entered the freeze at streak=1/4 and the whole cycle chained.
        //
        // Drive 12 straight violated frames with the raw best differing every
        // frame and the previous move absent from `ranked`, threading state the
        // way AnalysisPipeline now does: the display must never stay frozen for
        // more than MAX_VIOLATION_HOLD_FRAMES frames in a row.
        var state = SuggestionStickiness.State()
        val alternatives = listOf(drawStock, wasteToCol0, wasteToCol2, tableauMove)
        var consecutiveFrozen = 0
        var worstFrozenRun = 0
        var adopted = 0
        repeat(12) { frame ->
            val best = alternatives[frame % alternatives.size]
            val result = SuggestionStickiness.apply(
                previous = wasteToCol2,
                best = best,
                // Deliberately excludes wasteToCol2: the displayed move has
                // vanished, which is what made the old fall-through hold.
                ranked = listOf(best),
                boardVisuallyChanged = false,
                state = state,
                hasRunConsistencyViolation = true
            )
            if (result.display == null) {
                consecutiveFrozen++
                worstFrozenRun = maxOf(worstFrozenRun, consecutiveFrozen)
            } else {
                consecutiveFrozen = 0
                adopted++
            }
            state = result.state
        }
        assertTrue(
            "display froze for $worstFrozenRun consecutive frames",
            worstFrozenRun <= 4
        )
        assertTrue("display never updated across 12 violated frames", adopted > 0)
    }

    @Test
    fun violationHoldCapAdoptsRawBestEvenWhenPreviousAlsoVanished() {
        var state = SuggestionStickiness.State()
        repeat(4) {
            state = SuggestionStickiness.apply(
                previous = wasteToCol2,
                best = drawStock,
                ranked = listOf(drawStock),
                boardVisuallyChanged = false,
                state = state,
                hasRunConsistencyViolation = true
            ).state
        }
        val capped = SuggestionStickiness.apply(
            previous = wasteToCol2,
            best = drawStock,
            ranked = listOf(drawStock),
            boardVisuallyChanged = false,
            state = state,
            hasRunConsistencyViolation = true
        )
        assertEquals(drawStock.move, capped.display?.move)
        assertEquals(0, capped.state.violationHoldStreak)
        assertEquals(2, capped.state.violationCooldownRemaining)
        assertTrue(capped.holdReason!!.contains("ADOPT"))
    }

    @Test
    fun runConsistencyViolationIsIgnoredWhenMoveUnchanged() {
        // best == previous is a same-move no-op regardless of the flag.
        val result = SuggestionStickiness.apply(
            previous = tableauMove,
            best = tableauMove,
            ranked = listOf(tableauMove, drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State(),
            hasRunConsistencyViolation = true
        )
        assertEquals(tableauMove.move, result.display?.move)
    }

    @Test
    fun runConsistencyViolationHoldStreakSurvivesCoincidentalAgreementFrame() {
        // Real device log evidence (909c6d69-analysis.log, v1.4.106): mid a
        // persistently-violated episode, a raw candidate coincidentally
        // matched what was already displayed (best == previous) on some
        // frames even though the board was still self-flagged broken that
        // frame. The old code routed best==previous through the shared
        // `idle` state unconditionally, silently zeroing violationHoldStreak
        // - invisible in the log because this branch produces no new
        // HOLD/ARROW line. The counter must instead survive this frame
        // untouched so the bound (MAX_VIOLATION_HOLD_FRAMES) actually caps
        // the real total freeze duration.
        val held = SuggestionStickiness.apply(
            previous = tableauMove,
            best = wasteToCol0,
            ranked = listOf(wasteToCol0, tableauMove),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State(),
            hasRunConsistencyViolation = true
        )
        assertNull(held.display)
        assertEquals(1, held.state.violationHoldStreak)

        // Next frame: raw candidate coincidentally agrees with what's shown,
        // but the board is still violated. Must display it (nothing to
        // hold - it already matches) while preserving the streak.
        val agree = SuggestionStickiness.apply(
            previous = tableauMove,
            best = tableauMove,
            ranked = listOf(tableauMove, wasteToCol0),
            boardVisuallyChanged = true,
            state = held.state,
            hasRunConsistencyViolation = true
        )
        assertEquals(tableauMove.move, agree.display?.move)
        assertEquals(1, agree.state.violationHoldStreak)
        assertEquals(0, agree.state.pendingStreak)

        // A further violated, differing frame must resume counting from 2,
        // not restart at 1.
        val resumed = SuggestionStickiness.apply(
            previous = tableauMove,
            best = wasteToCol0,
            ranked = listOf(wasteToCol0, tableauMove),
            boardVisuallyChanged = true,
            state = agree.state,
            hasRunConsistencyViolation = true
        )
        assertNull(resumed.display)
        assertEquals(2, resumed.state.violationHoldStreak)
    }

    @Test
    fun stillAnimatingFrameThatAgreesWithCurrentDisplayIsImmediate() {
        // Regardless of streak, a read that matches what's already on
        // screen needs no extra confirmation - only a *differing* read
        // during an animation is held back.
        val result = SuggestionStickiness.apply(
            previous = wasteToCol0,
            best = wasteToCol0,
            ranked = listOf(wasteToCol0, drawStock),
            boardVisuallyChanged = true,
            state = SuggestionStickiness.State(),
            visualChangeStreak = 3
        )
        assertEquals(wasteToCol0.move, result.display?.move)
    }
}
