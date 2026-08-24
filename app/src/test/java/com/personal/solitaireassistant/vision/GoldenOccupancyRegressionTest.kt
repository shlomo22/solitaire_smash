package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FaceDown / occupancy mismatches from v1.4.20 Evaluate (analysis.log).
 * These are geometry or evaluator-artifact cases — not rank-template bugs.
 * Tracked so future geometry changes can be measured against a fixed set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenOccupancyRegressionTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun faceDownTruthSlotsAreNotMisclassifiedAsOccupiedFaceUp() {
        val cases = listOf(
            Triple("20260819_202405", "tableau:5", 0),
            Triple("20260819_212027", "tableau:6", 0)
        )
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val failures = mutableListOf<String>()
        try {
            cases.forEach { (id, pile, index) ->
                val fixture = loadGolden(id) ?: run {
                    failures += "$id missing fixture"
                    return@forEach
                }
                val (sample, bitmap) = fixture
                val truth = sample.slots.firstOrNull {
                    it.pile == pile &&
                        it.index == index &&
                        !it.inferred
                } ?: run {
                    failures += "$id missing slot $pile[$index]"
                    bitmap.recycle()
                    return@forEach
                }
                if (truth.truth.kind != SlotKind.FaceDown) {
                    failures += "$id $pile[$index] expected FaceDown truth"
                    bitmap.recycle()
                    return@forEach
                }
                val detection = detector.detect(bitmap)
                val detected = GoldenTruthEvaluator.findMatchingSlot(
                    detection.recognizedSlots,
                    truth
                )
                if (detected != null && detected.engine.kind == SlotKind.FaceUp) {
                    failures +=
                        "$id $pile[$index] face-down truth matched face-up ${detected.engine.shortLabel()}"
                }
                bitmap.recycle()
            }
        } finally {
            detector.release()
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun wasteQueenTruthHasDetectableSlotWhenPresent() {
        val fixture = loadGolden("20260814_125606") ?: return
        val (sample, bitmap) = fixture
        val truth = sample.slots.firstOrNull {
            it.pile == "waste" && it.index == 0 && !it.inferred
        }
        assertNotNull(truth)
        assertEquals(Rank.Queen, truth!!.truth.rank)
        assertEquals(Suit.Diamonds, truth.truth.suit)
        bitmap.recycle()
    }

    private fun loadGolden(id: String): Pair<GoldenSample, android.graphics.Bitmap>? {
        val jsonStream = javaClass.classLoader!!.getResourceAsStream("golden/$id.json")
        val pngStream = javaClass.classLoader!!.getResourceAsStream("golden/$id.png")
        if (jsonStream == null || pngStream == null) {
            jsonStream?.close()
            pngStream?.close()
            return null
        }
        val sample = jsonStream.bufferedReader().use { GoldenTruthJson.fromJson(it.readText()) }
        val bitmap = pngStream.use { BitmapFactory.decodeStream(it)!! }
        return sample to bitmap
    }
}
