package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CascadeRankCorrectionsTest {
    @Test
    fun challengesEveryStrongQueen() {
        assertTrue(
            CascadeRankCorrections.shouldChallengeStrongJackQueen(
                Rank.Queen to 0.92f,
                mapOf(Rank.Queen to 0.92f, Rank.Jack to 0.40f)
            )
        )
    }

    @Test
    fun challengesStrongQueenWhenJackAlsoScores() {
        val scores = mapOf(Rank.Queen to 0.86f, Rank.Jack to 0.74f)
        assertTrue(
            CascadeRankCorrections.shouldChallengeStrongJackQueen(
                Rank.Queen to 0.86f,
                scores
            )
        )
    }

    @Test
    fun doesNotChallengeJackWhenQueenAbsent() {
        val scores = mapOf(Rank.Jack to 0.90f, Rank.Ten to 0.55f)
        assertFalse(
            CascadeRankCorrections.shouldChallengeStrongJackQueen(
                Rank.Jack to 0.90f,
                scores
            )
        )
    }

    @Test
    fun ocrJackOverridesStrongQueenBitmap() {
        val scores = mapOf(Rank.Queen to 0.84f, Rank.Jack to 0.40f)
        val override = CascadeRankCorrections.ocrJackQueenOverridesStrongBitmap(
            bitmapHit = Rank.Queen to 0.84f,
            ocrGuess = RankCornerOcr.Guess(Rank.Jack, 0.70f, "J"),
            rankScoreMap = scores
        )
        assertNotNull(override)
        assertEquals(Rank.Jack, override!!.first)
    }

    @Test
    fun ocrDoesNotOverrideWhenNotJackOrQueen() {
        val scores = mapOf(Rank.Queen to 0.84f, Rank.Jack to 0.72f)
        assertNull(
            CascadeRankCorrections.ocrJackQueenOverridesStrongBitmap(
                bitmapHit = Rank.Queen to 0.84f,
                ocrGuess = RankCornerOcr.Guess(Rank.Ten, 0.90f, "10"),
                rankScoreMap = scores
            )
        )
    }

    @Test
    fun ocrDoesNotOverrideWhenAgreesWithBitmap() {
        assertNull(
            CascadeRankCorrections.ocrJackQueenOverridesStrongBitmap(
                bitmapHit = Rank.Queen to 0.84f,
                ocrGuess = RankCornerOcr.Guess(Rank.Queen, 0.70f, "Q"),
                rankScoreMap = mapOf(Rank.Queen to 0.84f)
            )
        )
    }
}
