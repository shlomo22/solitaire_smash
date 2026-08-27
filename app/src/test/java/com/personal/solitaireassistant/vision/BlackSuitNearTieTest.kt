package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BlackSuitNearTieTest {
    @Test
    fun evaluateClubMissesAreStrongNearTies() {
        // 10C / KC / QC / 8C on-device: full=C0.90/S0.91, lowTopMargin-noShape
        assertTrue(
            CardRecognizer.isStrongBlackNearTie(
                BlackSuitTemplateScores(0.90f, 0.91f, 0.86f, 0.83f)
            )
        )
        assertTrue(
            CardRecognizer.isStrongBlackNearTie(
                mapOf(Suit.Clubs to 0.91f, Suit.Spades to 0.91f)
            )
        )
        assertTrue(
            CardRecognizer.isStrongBlackNearTie(
                mapOf(Suit.Clubs to 0.90f, Suit.Spades to 0.91f)
            )
        )
    }

    @Test
    fun wasteWideMarginClubsIsNotAStrongNearTie() {
        assertFalse(
            CardRecognizer.isStrongBlackNearTie(
                mapOf(Suit.Clubs to 0.83f, Suit.Spades to 0.77f)
            )
        )
    }

    @Test
    fun weakScoresAreNotStrongNearTies() {
        assertFalse(
            CardRecognizer.isStrongBlackNearTie(
                BlackSuitTemplateScores(0.62f, 0.63f, 0.50f, 0.51f)
            )
        )
        assertFalse(
            CardRecognizer.isStrongBlackNearTie(emptyMap())
        )
    }
}
