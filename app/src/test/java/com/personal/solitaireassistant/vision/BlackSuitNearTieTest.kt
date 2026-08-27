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
    fun evaluateClubMissesPreferClubsOnStrongNearTie() {
        // 10C / KC / QC / 8C on-device: full=C0.90/S0.91, lowTopMargin-noShape
        assertTrue(
            CardRecognizer.strongBlackNearTiePrefersClubs(
                BlackSuitTemplateScores(0.90f, 0.91f, 0.86f, 0.83f)
            )
        )
        // 4C opencv path: {S:0.91 C:0.91}
        assertTrue(
            CardRecognizer.strongBlackNearTiePrefersClubs(
                mapOf(Suit.Clubs to 0.91f, Suit.Spades to 0.91f)
            )
        )
        // 4C / 10C opencv: {S:0.91 C:0.90}
        assertTrue(
            CardRecognizer.strongBlackNearTiePrefersClubs(
                mapOf(Suit.Clubs to 0.90f, Suit.Spades to 0.91f)
            )
        )
    }

    @Test
    fun wasteWideMarginClubsIsNotAStrongNearTie() {
        // Waste S→C misses stay on wideMarginDirect C0.83/S0.77 — not this rule
        assertFalse(
            CardRecognizer.strongBlackNearTiePrefersClubs(
                mapOf(Suit.Clubs to 0.83f, Suit.Spades to 0.77f)
            )
        )
    }

    @Test
    fun weakScoresDoNotTriggerClubsBias() {
        assertFalse(
            CardRecognizer.strongBlackNearTiePrefersClubs(
                BlackSuitTemplateScores(0.62f, 0.63f, 0.50f, 0.51f)
            )
        )
        assertFalse(
            CardRecognizer.strongBlackNearTiePrefersClubs(emptyMap())
        )
    }
}
