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
}
