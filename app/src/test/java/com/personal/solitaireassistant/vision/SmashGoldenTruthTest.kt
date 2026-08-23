package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmashGoldenTruthTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun detectEmitsRecognizedSlotsForLiveShot() {
        val bitmap = loadOptional("screenshots/device_live_40.png") ?: return
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        assertTrue(
            "expected recognized slots, diag=${result.diagnostics}",
            result.recognizedSlots.isNotEmpty()
        )
        assertTrue(result.recognizedSlots.any { !it.inferred })
        detector.release()
        bitmap.recycle()
    }

    @Test
    fun evaluatorAgreesWithSelfLabeledLiveShot() {
        val bitmap = loadOptional("screenshots/device_live_40.png") ?: return
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val labeled = result.recognizedSlots.filter { !it.inferred }
        assertTrue(labeled.isNotEmpty())
        val store = GoldenTruthStore(context)
        val sample = GoldenSample(
            id = "self_live_40",
            frameWidth = bitmap.width,
            frameHeight = bitmap.height,
            slots = result.recognizedSlots.map { it.toGoldenSlot() }
        )
        store.save(bitmap, sample)
        val report = GoldenTruthEvaluator.evaluate(store, detector)
        assertEquals(report.summary(), 0, report.rankErrors)
        assertEquals(report.summary(), 0, report.suitErrors)
        assertEquals(report.summary(), 0, report.occupancyErrors)
        assertTrue(report.summary(), report.matchedSlots >= labeled.size)
        detector.release()
        bitmap.recycle()
    }

    @Test
    fun desktopEvaluatePrintsSameReportAsDevice() {
        val loaded = loadGoldenFixtures()
        assertTrue(
            "No golden/{id}.png+json pairs in test resources. Pull from the device.",
            loaded.isNotEmpty()
        )
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        try {
            val report = GoldenTruthEvaluator.evaluateLoaded(loaded, detector)
            val header = "Desktop Evaluate (Robolectric, OpenCV usually off)\n${report.summary()}"
            println(header)
            System.err.println(header)
            assertTrue(
                "Detector produced no labeled-slot comparisons.\n$header",
                report.slotCount > 0
            )
        } finally {
            detector.release()
            loaded.forEach { (_, bitmap) -> bitmap.recycle() }
        }
    }

    @Test
    fun goldenFixturesMatchDetectionWhenPresent() {
        val loaded = loadGoldenFixtures()
        if (loaded.isEmpty()) return
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        try {
            val report = GoldenTruthEvaluator.evaluateLoaded(loaded, detector)
            println(report.summary())
            assertTrue(
                "Regression vs labeled fixtures:\n${report.summary()}",
                report.rankErrors == 0 &&
                    report.suitErrors == 0 &&
                    report.occupancyErrors == 0 &&
                    report.missingSlots == 0
            )
        } finally {
            detector.release()
            loaded.forEach { (_, bitmap) -> bitmap.recycle() }
        }
    }

    @Test
    fun goldenRedSuitsMatchLabeledHeartsAndDiamonds() {
        val loaded = loadGoldenFixtures()
        if (loaded.isEmpty()) return
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val failures = mutableListOf<String>()
        var redSlots = 0
        var redHits = 0
        try {
            loaded.forEach { (sample, bitmap) ->
                val detection = detector.detect(bitmap)
                sample.slots.filter { slot ->
                    !slot.inferred &&
                        slot.truth.kind == SlotKind.FaceUp &&
                        slot.truth.suit != null &&
                        slot.truth.suit!!.isRed
                }.forEach { truthSlot ->
                    redSlots++
                    val detected = GoldenTruthEvaluator.findMatchingSlot(
                        detection.recognizedSlots,
                        truthSlot
                    )
                    val actual = detected?.engine
                    if (actual?.suit == truthSlot.truth.suit) {
                        redHits++
                    } else {
                        failures +=
                            "${sample.id} ${truthSlot.pile} ${truthSlot.truth.shortLabel()} vs " +
                                (actual?.shortLabel() ?: "missing")
                    }
                }
            }
        } finally {
            detector.release()
            loaded.forEach { (_, bitmap) -> bitmap.recycle() }
        }
        println("red-suit $redHits/$redSlots")
        assertTrue(
            "red-suit $redHits/$redSlots\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }

    @Test
    fun goldenBlackSuitsMatchLabeledClubsAndSpades() {
        val loaded = loadGoldenFixtures()
        if (loaded.isEmpty()) return
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val failures = mutableListOf<String>()
        var blackSlots = 0
        var blackHits = 0
        try {
            loaded.forEach { (sample, bitmap) ->
                val detection = detector.detect(bitmap)
                sample.slots.filter { slot ->
                    !slot.inferred &&
                        slot.truth.kind == SlotKind.FaceUp &&
                        slot.truth.suit != null &&
                        !slot.truth.suit!!.isRed
                }.forEach { truthSlot ->
                    blackSlots++
                    val detected = GoldenTruthEvaluator.findMatchingSlot(
                        detection.recognizedSlots,
                        truthSlot
                    )
                    val actual = detected?.engine
                    if (actual?.suit == truthSlot.truth.suit) {
                        blackHits++
                    } else {
                        failures +=
                            "${sample.id} ${truthSlot.pile} ${truthSlot.truth.shortLabel()} vs " +
                                (actual?.shortLabel() ?: "missing")
                    }
                }
            }
        } finally {
            detector.release()
            loaded.forEach { (_, bitmap) -> bitmap.recycle() }
        }
        println("black-suit $blackHits/$blackSlots")
        assertTrue(
            "black-suit $blackHits/$blackSlots\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }

    private fun listedGoldenIds(): List<String> {
        val readme = javaClass.classLoader!!.getResource("golden/README.md") ?: return emptyList()
        val dir = runCatching { File(readme.toURI()).parentFile }.getOrNull() ?: return emptyList()
        return dir.listFiles { _, name -> name.endsWith(".json", ignoreCase = true) }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .sorted()
    }

    private fun loadGoldenFixtures(): List<Pair<GoldenSample, android.graphics.Bitmap>> {
        return listedGoldenIds().mapNotNull { id ->
            val jsonStream = javaClass.classLoader!!.getResourceAsStream("golden/$id.json")
            val pngStream = javaClass.classLoader!!.getResourceAsStream("golden/$id.png")
            if (jsonStream == null || pngStream == null) {
                jsonStream?.close()
                pngStream?.close()
                return@mapNotNull null
            }
            // Name the offending file. A hand-edited golden with a bad enum
            // spelling ("king" for "King") surfaced here only as a bare
            // IllegalArgumentException from Enum.valueOf with no clue which
            // of 35 files produced it.
            val sample = jsonStream.bufferedReader().use { reader ->
                val text = reader.readText()
                runCatching { GoldenTruthJson.fromJson(text) }.getOrElse { cause ->
                    throw IllegalStateException("Bad golden json: golden/$id.json - ${cause.message}", cause)
                }
            }
            val bitmap = pngStream.use { BitmapFactory.decodeStream(it)!! }
            sample to bitmap
        }
    }

    private fun loadOptional(path: String) =
        javaClass.classLoader!!.getResourceAsStream(path)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
}
