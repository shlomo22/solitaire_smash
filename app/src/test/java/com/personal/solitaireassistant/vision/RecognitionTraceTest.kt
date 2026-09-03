package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionTraceTest {
    @Test
    fun logLineIncludesRankAndSuitSources() {
        val line = RecognitionTrace(
            rankSource = "rank-png",
            rankScore = 0.71f,
            rankTemplates = "10:0.71 K:0.63",
            suitSource = "suit-shape-black",
            suitScore = 0.58f,
            suitTemplates = "S:0.55 C:0.51",
            postSteps = listOf("post-black-suit:Spades->Clubs")
        ).logLine(
            pile = PileRef.Tableau(2),
            index = 0,
            label = "7S",
            confidence = 0.71f
        )
        assertTrue(line.contains("rank=rank-png@0.71"))
        assertTrue(line.contains("suit=suit-shape-black@0.58"))
        assertTrue(line.contains("post-black-suit:Spades->Clubs"))
        assertTrue(line.contains("tableau:2"))
    }

    @Test
    fun inferredSlotsAreLoggedWithMarker() {
        val slot = RecognizedSlot(
            pile = PileRef.Tableau(3),
            index = 1,
            bounds = com.personal.solitaireassistant.game.BoardRegion(0f, 0f, 10f, 10f),
            engine = SlotGuess(SlotKind.FaceUp, Rank.Five, Suit.Spades),
            confidence = 0.93f,
            diagnostic = "inferred-cascade",
            trace = RecognitionTrace(rankSource = "geom", rankScore = 0.55f),
            inferred = true
        )
        val lines = recognitionTraceLines(listOf(slot))
        assertTrue(lines.single().contains("inferred-slot"))
        assertTrue(lines.single().contains("tableau:3:1"))
    }
}
