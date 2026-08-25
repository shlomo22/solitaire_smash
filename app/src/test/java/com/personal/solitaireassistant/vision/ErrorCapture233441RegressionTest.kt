package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ErrorCapture233441RegressionTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun errorCapture233441NoDuplicateJacksInLongCascade() {
        val fixture = loadErrorCapture("error_20260824_233441")
            ?: return
        val (_, bitmap) = fixture
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        try {
            val detection = detector.detect(bitmap)
            val state = requireNotNull(detection.state)

            val knownIds = buildList {
                state.waste.forEach { if (it.known && it.faceUp) add(it.id) }
                state.foundations.flatten().forEach { if (it.known && it.faceUp) add(it.id) }
                state.tableau.flatten().forEach { if (it.known && it.faceUp) add(it.id) }
            }
            assertTrue(
                "Jack_Hearts appears at most once, got ${knownIds.count { it == "Jack_Hearts" }}",
                knownIds.count { it == "Jack_Hearts" } <= 1
            )
            assertTrue(
                "Jack_Spades appears at most twice (tableau:0 + one real), got ${knownIds.count { it == "Jack_Spades" }}",
                knownIds.count { it == "Jack_Spades" } <= 2
            )

            val violations = BoardRecognitionValidator.validate(state)
            assertFalse(
                violations.joinToString { it.summary() },
                violations.any {
                    it is RecognitionViolation.DuplicateCard &&
                        (it.cardId == "Jack_Hearts" || it.cardId == "Jack_Spades")
                }
            )
            assertFalse(
                violations.joinToString { it.summary() },
                violations.any {
                    it is RecognitionViolation.CascadeBreak &&
                        it.pile == "tableau:6"
                }
            )
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
