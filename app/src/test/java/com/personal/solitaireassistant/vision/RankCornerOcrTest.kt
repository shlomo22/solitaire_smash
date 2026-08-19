package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RankCornerOcrTest {
    @Test
    fun parseRankReadsAceAndFaceCards() {
        assertEquals(Rank.Ace, RankCornerOcr.parseRank("A"))
        assertEquals(Rank.Ace, RankCornerOcr.parseRank(" a "))
        assertEquals(Rank.Jack, RankCornerOcr.parseRank("J"))
        assertEquals(Rank.Queen, RankCornerOcr.parseRank("Q"))
        assertEquals(Rank.King, RankCornerOcr.parseRank("K"))
    }

    @Test
    fun parseRankReadsNumericRanks() {
        for (value in 2..9) {
            assertEquals(
                Rank.entries.first { it.value == value },
                RankCornerOcr.parseRank(value.toString())
            )
        }
    }

    @Test
    fun parseRankReadsTenAndCommonMisreads() {
        assertEquals(Rank.Ten, RankCornerOcr.parseRank("10"))
        assertEquals(Rank.Ten, RankCornerOcr.parseRank("1O"))
        assertEquals(Rank.Ten, RankCornerOcr.parseRank("IO"))
        assertEquals(Rank.Ten, RankCornerOcr.parseRank("1 0"))
    }

    @Test
    fun estimateConfidencePrefersCleanSingleTokenReads() {
        assertEquals(0.62f, RankCornerOcr.estimateConfidence("A"))
        assertEquals(0.58f, RankCornerOcr.estimateConfidence("1 0"))
        assertEquals(0.55f, RankCornerOcr.estimateConfidence("ABCD"))
    }

    @Test
    fun formatHitTraceUsesSanitizedText() {
        val guess = RankCornerOcr.Guess(Rank.Queen, 0.62f, "Q")
        assertEquals("ocr='Q'@0.62", RankCornerOcr.formatHitTrace(guess))
    }

    @Test
    fun parseRankRejectsGarbage() {
        assertNull(RankCornerOcr.parseRank(""))
        assertNull(RankCornerOcr.parseRank("8H"))
        assertNull(RankCornerOcr.parseRank("SOLITAIRE"))
        assertNull(RankCornerOcr.parseRank("O"))
    }
}
