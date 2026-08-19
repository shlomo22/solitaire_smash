package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenRankRegressionTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun labeledWasteAndTableauRanksMatchTruth() {
        val cases = listOf(
            Triple("20260814_125606", "waste", 0),
            Triple("20260815_170300", "waste", 0),
            Triple("20260814_125128", "waste", 0)
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
                val actual = detected?.engine
                if (actual?.rank != truth.truth.rank) {
                    failures +=
                        "$id $pile ${actual?.shortLabel() ?: "missing"} vs ${truth.truth.shortLabel()}"
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
