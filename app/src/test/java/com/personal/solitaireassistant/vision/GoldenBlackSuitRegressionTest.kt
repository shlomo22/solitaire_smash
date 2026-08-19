package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenBlackSuitRegressionTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun labeledClubBadgesAreNotReadAsSpades() {
        val cases = listOf(
            Triple("20260814_125955", "foundation:0", 0 to Suit.Clubs),
            Triple("20260814_130253", "foundation:0", 0 to Suit.Clubs),
            Triple("20260814_234016", "tableau:1", 4 to Suit.Clubs),
            Triple("20260815_165137", "foundation:3", 0 to Suit.Clubs),
            Triple("20260814_125352", "tableau:2", 1 to Suit.Clubs),
            Triple("20260814_125352", "tableau:5", 3 to Suit.Clubs),
            Triple("20260814_125744", "waste", 0 to Suit.Clubs),
            Triple("20260814_205456", "foundation:1", 0 to Suit.Clubs),
            Triple("20260815_132843", "foundation:0", 0 to Suit.Clubs),
            Triple("20260815_165137", "foundation:1", 0 to Suit.Clubs)
        )
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val failures = mutableListOf<String>()
        try {
            cases.forEach { (id, pile, slot) ->
                val (index, expectedSuit) = slot
                val fixture = loadGolden(id) ?: run {
                    failures += "$id missing fixture"
                    return@forEach
                }
                val (sample, bitmap) = fixture
                val truth = sample.slots.firstOrNull {
                    it.pile == pile &&
                        it.index == index &&
                        !it.inferred &&
                        it.truth.kind == SlotKind.FaceUp
                } ?: run {
                    failures += "$id missing truth slot $pile[$index]"
                    bitmap.recycle()
                    return@forEach
                }
                val detection = detector.detect(bitmap)
                val detected = GoldenTruthEvaluator.findMatchingSlot(
                    detection.recognizedSlots,
                    truth
                )
                val actual = detected?.engine?.suit
                if (actual != expectedSuit) {
                    failures +=
                        "$id $pile rank=${truth.truth.rank} ${actual?.name ?: "missing"} vs ${expectedSuit.name}"
                } else {
                    assertEquals(truth.truth.rank, detected?.engine?.rank)
                }
                bitmap.recycle()
            }
        } finally {
            detector.release()
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun labeledSpadeBadgesAreNotReadAsClubs() {
        val cases = listOf(
            Triple("20260814_233444", "tableau:1", 4 to Suit.Spades),
            Triple("20260814_234016", "foundation:2", 0 to Suit.Spades),
            Triple("20260814_124940", "tableau:0", 0 to Suit.Spades),
            Triple("20260814_233209", "tableau:6", 8 to Suit.Spades),
            Triple("20260814_125955", "foundation:0", 0 to Suit.Spades),
            Triple("20260814_130253", "foundation:0", 0 to Suit.Spades),
            Triple("20260815_165137", "foundation:3", 0 to Suit.Spades)
        )
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val failures = mutableListOf<String>()
        try {
            cases.forEach { (id, pile, slot) ->
                val (index, expectedSuit) = slot
                val fixture = loadGolden(id) ?: run {
                    failures += "$id missing fixture"
                    return@forEach
                }
                val (sample, bitmap) = fixture
                val truth = sample.slots.firstOrNull {
                    it.pile == pile &&
                        it.index == index &&
                        !it.inferred &&
                        it.truth.kind == SlotKind.FaceUp
                } ?: run {
                    failures += "$id missing truth slot $pile[$index]"
                    bitmap.recycle()
                    return@forEach
                }
                val detection = detector.detect(bitmap)
                val detected = GoldenTruthEvaluator.findMatchingSlot(
                    detection.recognizedSlots,
                    truth
                )
                val actual = detected?.engine?.suit
                if (actual != expectedSuit) {
                    failures +=
                        "$id $pile rank=${truth.truth.rank} ${actual?.name ?: "missing"} vs ${expectedSuit.name}"
                } else {
                    assertEquals(truth.truth.rank, detected?.engine?.rank)
                }
                bitmap.recycle()
            }
        } finally {
            detector.release()
        }
        assertEquals(emptyList<String>(), failures)
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
