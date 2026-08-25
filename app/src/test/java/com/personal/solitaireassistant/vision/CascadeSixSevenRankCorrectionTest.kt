package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CascadeSixSevenRankCorrectionTest {
    private val strongSeven = Rank.Seven to 0.84f

    @Test
    fun shouldChallengeStrongSevenOnTrimmedCascadeStrip() {
        assertTrue(CascadeRankCorrections.shouldChallengeStrongSeven(true, strongSeven))
    }

    @Test
    fun shouldNotChallengeStrongSevenOnFullCardCrop() {
        assertFalse(CascadeRankCorrections.shouldChallengeStrongSeven(false, strongSeven))
    }

    @Test
    fun shouldNotChallengeWeakSeven() {
        assertFalse(CascadeRankCorrections.shouldChallengeStrongSeven(true, Rank.Seven to 0.67f))
    }

    @Test
    fun shouldChallengeCloseSevenOnTrimmedCascadeStrip() {
        assertTrue(
            CascadeRankCorrections.shouldChallengeStrongSeven(
                trimmedToVisibleStrip = true,
                bitmapHit = Rank.Seven to 0.666f,
                rankScoreMap = mapOf(Rank.Six to 0.636f, Rank.Seven to 0.666f)
            )
        )
    }

    @Test
    fun closeSevenTemplateScoresPreferSixWithoutOcr() {
        val override = CascadeRankCorrections.ocrSixOverridesStrongSeven(
            bitmapHit = Rank.Seven to 0.666f,
            ocrGuess = null,
            rankScoreMap = mapOf(Rank.Six to 0.636f, Rank.Seven to 0.666f)
        )
        assertEquals(Rank.Six to 0.636f, override)
    }

    @Test
    fun ocrSixOverridesStrongSevenTemplate() {
        val override = CascadeRankCorrections.ocrSixOverridesStrongSeven(
            bitmapHit = strongSeven,
            ocrGuess = RankCornerOcr.Guess(Rank.Six, 0.62f, "6")
        )
        assertEquals(Rank.Six to 0.62f, override)
    }

    @Test
    fun weakOcrSixDoesNotOverrideStrongSeven() {
        assertNull(
            CascadeRankCorrections.ocrSixOverridesStrongSeven(
                bitmapHit = strongSeven,
                ocrGuess = RankCornerOcr.Guess(Rank.Six, 0.50f, "6")
            )
        )
    }

    @Test
    fun ocrSevenDoesNotOverrideStrongSeven() {
        assertNull(
            CascadeRankCorrections.ocrSixOverridesStrongSeven(
                bitmapHit = strongSeven,
                ocrGuess = RankCornerOcr.Guess(Rank.Seven, 0.80f, "7")
            )
        )
    }
}
