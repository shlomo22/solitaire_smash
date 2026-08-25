package com.personal.solitaireassistant.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorCaptureReviewHintsTest {
    @Test
    fun duplicateViolationMarksBothLocations() {
        val hints = ErrorCaptureReviewHints.fromViolations(
            listOf(
                RecognitionViolation.DuplicateCard(
                    cardId = "Jack_Spades",
                    locations = listOf("tableau:2:2", "tableau:6:8")
                )
            )
        )

        assertEquals(2, hints.size)
        assertTrue(hints.any { it.locationKey == "tableau:2:2" && it.reason.contains("duplicate") })
        assertTrue(hints.any { it.locationKey == "tableau:6:8" })
    }

    @Test
    fun cascadeBreakMarksBothCardsInPair() {
        val hints = ErrorCaptureReviewHints.fromViolations(
            listOf(
                RecognitionViolation.CascadeBreak(
                    pile = "tableau:0",
                    lowerIndex = 1,
                    upperIndex = 2,
                    lowerCard = "Ten_Spades",
                    upperCard = "Queen_Hearts"
                )
            )
        )

        assertEquals(2, hints.size)
        assertTrue(hints.any { it.locationKey == "tableau:0:1" })
        assertTrue(hints.any { it.locationKey == "tableau:0:2" })
    }
}
