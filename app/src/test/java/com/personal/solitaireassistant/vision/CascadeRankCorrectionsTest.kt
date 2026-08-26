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
    fun challengesQueenWhenJackScoresWithinWideMargin() {
        assertTrue(
            CascadeRankCorrections.shouldChallengeStrongJackQueen(
                Rank.Queen to 0.86f,
                mapOf(Rank.Queen to 0.86f, Rank.Jack to 0.65f)
            )
        )
    }

    @Test
    fun challengesEvenWhenJackBelowOldFloor() {
        assertTrue(
            CascadeRankCorrections.shouldChallengeStrongJackQueen(
                Rank.Queen to 0.80f,
                mapOf(Rank.Queen to 0.80f, Rank.Jack to 0.58f)
            )
        )
    }

    @Test
    fun doesNotChallengeWhenJackFarBehind() {
        assertFalse(
            CascadeRankCorrections.shouldChallengeStrongJackQueen(
                Rank.Queen to 0.90f,
                mapOf(Rank.Queen to 0.90f, Rank.Jack to 0.41f)
            )
        )
    }

    @Test
    fun ocrJackOverridesStrongQueenBitmap() {
        val override = CascadeRankCorrections.ocrJackQueenOverridesStrongBitmap(
            bitmapHit = Rank.Queen to 0.84f,
            ocrGuess = RankCornerOcr.Guess(Rank.Jack, 0.70f, "J"),
            rankScoreMap = mapOf(Rank.Queen to 0.84f, Rank.Jack to 0.42f)
        )
        assertNotNull(override)
        assertEquals(Rank.Jack, override!!.first)
    }

    @Test
    fun ocrDoesNotOverrideWhenNotJackOrQueen() {
        assertNull(
            CascadeRankCorrections.ocrJackQueenOverridesStrongBitmap(
                bitmapHit = Rank.Queen to 0.84f,
                ocrGuess = RankCornerOcr.Guess(Rank.Ten, 0.90f, "10"),
                rankScoreMap = mapOf(Rank.Queen to 0.84f)
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
