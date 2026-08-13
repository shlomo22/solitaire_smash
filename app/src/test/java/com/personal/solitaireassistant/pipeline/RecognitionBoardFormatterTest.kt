package com.personal.solitaireassistant.pipeline

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import com.personal.solitaireassistant.vision.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionBoardFormatterTest {
    @Test
    fun overlayLines_usesShortCodesForTops() {
        val state = GameState(
            tableau = listOf(
                listOf(Card(Rank.King, Suit.Diamonds)),
                emptyList(),
                listOf(Card(Rank.Nine, Suit.Spades)),
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(Card(Rank.Ace, Suit.Hearts, known = false))
            ),
            foundations = listOf(
                listOf(Card(Rank.Ace, Suit.Spades)),
                emptyList(),
                listOf(Card(Rank.King, Suit.Hearts)),
                emptyList()
            ),
            stock = emptyList(),
            waste = listOf(Card(Rank.Seven, Suit.Clubs))
        )
        val detection = DetectionResult(
            state = state,
            locations = emptyMap(),
            confidence = 0.91f,
            diagnostics = emptyList(),
            board = null
        )
        val lines = RecognitionBoardFormatter.overlayLines(detection)
        assertEquals(
            "f: AS _ KH _\nw: 7C\nt: KD _ 9S _ _ _ Ur",
            lines
        )
    }

    @Test
    fun dump_includesDiagnostics() {
        val detection = DetectionResult(
            state = GameState.empty(),
            locations = emptyMap(),
            confidence = 0.5f,
            diagnostics = listOf("tableau0=King_Diamonds"),
            board = null
        )
        val text = RecognitionBoardFormatter.dump(detection)
        assertTrue(text.contains("diagnostics:"))
        assertTrue(text.contains("tableau0=King_Diamonds"))
    }
}
