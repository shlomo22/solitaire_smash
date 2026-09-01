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
