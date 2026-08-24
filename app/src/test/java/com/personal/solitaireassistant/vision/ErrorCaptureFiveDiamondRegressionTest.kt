package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ErrorCaptureFiveDiamondRegressionTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun errorCapture225719ReadsTableauFiveAsDiamondsNotClubs() {
        val fixture = loadErrorCapture("error_20260824_225719")
            ?: return // skip if error captures not checked in on this machine
        val (sample, bitmap) = fixture
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        try {
            val detection = detector.detect(bitmap)
            val slot = detection.recognizedSlots.firstOrNull {
                it.pile == com.personal.solitaireassistant.game.PileRef.Tableau(5) &&
                    it.index == 7 &&
                    !it.inferred
            } ?: error("missing tableau:5 index 7 slot")
            assertEquals(Rank.Five, slot.engine.rank)
            assertEquals(
                "Five at tableau:5:7 should be a red suit, got ${slot.engine.suit}",
                true,
                slot.engine.suit?.isRed == true
            )

            val knownIds = buildList {
                detection.state?.let { state ->
                    state.waste.forEach { if (it.known && it.faceUp) add(it.id) }
                    state.foundations.flatten()
                        .forEach { if (it.known && it.faceUp) add(it.id) }
                    state.tableau.flatten()
                        .forEach { if (it.known && it.faceUp) add(it.id) }
                }
            }
            assertFalse(
                "duplicate Five_Clubs still present: ${knownIds.count { it == "Five_Clubs" }}",
                knownIds.count { it == "Five_Clubs" } > 1
            )

            val violations = BoardRecognitionValidator.validate(requireNotNull(detection.state))
            assertFalse(
                violations.joinToString { it.summary() },
                violations.any {
                    it is RecognitionViolation.DuplicateCard &&
                        it.cardId == "Five_Clubs"
                }
            )

            val inferredFour = detection.recognizedSlots.firstOrNull {
                it.pile == com.personal.solitaireassistant.game.PileRef.Tableau(5) &&
                    it.index == 8
            } ?: error("missing tableau:5 index 8 slot")
            assertEquals(Rank.Four, inferredFour.engine.rank)
            assertEquals(Suit.Diamonds, inferredFour.engine.suit)
        } finally {
            bitmap.recycle()
            detector.release()
        }
    }

    private fun loadErrorCapture(id: String): Pair<GoldenSample, android.graphics.Bitmap>? {
        val jsonStream = javaClass.classLoader?.getResourceAsStream("recognition_errors/$id.json")
            ?: return null
        val pngStream = javaClass.classLoader?.getResourceAsStream("recognition_errors/$id.png")
            ?: return null
        val sample = GoldenTruthJson.fromJson(jsonStream.bufferedReader().readText())
        val bitmap = BitmapFactory.decodeStream(pngStream) ?: return null
        return sample to bitmap
    }
}
