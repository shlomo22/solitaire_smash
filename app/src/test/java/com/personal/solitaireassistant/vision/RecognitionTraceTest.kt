package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.PileRef
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
}
