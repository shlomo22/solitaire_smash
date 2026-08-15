package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RedSuitDiagnosticTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun goldenRedSlotsPrintHeartDiamondScores() {
        val loaded = loadGoldenFixtures()
        if (loaded.isEmpty()) return
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val recognizer = CardRecognizer(context)
        val reports = mutableListOf<RedSuitSlotReport>()
        try {
            loaded.forEach { (sample, bitmap) ->
                val detection = detector.detect(bitmap)
                sample.slots.filter { slot ->
                    !slot.inferred &&
                        slot.truth.kind == SlotKind.FaceUp &&
                        slot.truth.suit?.isRed == true
                }.forEach { truthSlot ->
                    val detected = GoldenTruthEvaluator.findMatchingSlot(
                        detection.recognizedSlots,
                        truthSlot
                    )
                    reports += RedSuitDiagnostic.analyzeSlot(
                        sampleId = sample.id,
                        pile = truthSlot.pile,
                        bitmap = bitmap,
                        region = truthSlot.bounds,
                        recognizer = recognizer,
                        truthSuit = truthSlot.truth.suit,
                        detected = detected
                    )
                }
            }
        } finally {
            detector.release()
            recognizer.release()
            loaded.forEach { (_, bitmap) -> bitmap.recycle() }
        }
        val summary = RedSuitDiagnostic.summarize(reports)
        println(summary)
        reports.forEach { println(it.formatLine()) }
        assertTrue(summary, reports.isNotEmpty())
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
            val sample = jsonStream.bufferedReader().use { GoldenTruthJson.fromJson(it.readText()) }
            val bitmap = pngStream.use { BitmapFactory.decodeStream(it)!! }
            sample to bitmap
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BlackSuitDiagnosticTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun goldenBlackSlotsPrintClubSpadeScores() {
        val loaded = loadGoldenFixtures()
        if (loaded.isEmpty()) return
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val recognizer = CardRecognizer(context)
        val reports = mutableListOf<BlackSuitSlotReport>()
        try {
            loaded.forEach { (sample, bitmap) ->
                val detection = detector.detect(bitmap)
                sample.slots.filter { slot ->
                    !slot.inferred &&
                        slot.truth.kind == SlotKind.FaceUp &&
                        slot.truth.suit?.isRed == false
                }.forEach { truthSlot ->
                    val detected = GoldenTruthEvaluator.findMatchingSlot(
                        detection.recognizedSlots,
                        truthSlot
                    )
                    reports += BlackSuitDiagnostic.analyzeSlot(
                        sampleId = sample.id,
                        pile = truthSlot.pile,
                        bitmap = bitmap,
                        region = truthSlot.bounds,
                        recognizer = recognizer,
                        truthSuit = truthSlot.truth.suit,
                        detected = detected
                    )
                }
            }
        } finally {
            detector.release()
            recognizer.release()
            loaded.forEach { (_, bitmap) -> bitmap.recycle() }
        }
        val summary = BlackSuitDiagnostic.summarize(reports)
        println(summary)
        reports.forEach { println(it.formatLine()) }
        assertTrue(summary, reports.isNotEmpty())
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
            val sample = jsonStream.bufferedReader().use { GoldenTruthJson.fromJson(it.readText()) }
            val bitmap = pngStream.use { BitmapFactory.decodeStream(it)!! }
            sample to bitmap
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenBadgeTemplateExtractorTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun extractRedSuitBadgesFromGoldenFixtures() {
        val loaded = loadGoldenFixtures()
        if (loaded.isEmpty()) return
        val templatesDir = locateTemplatesDir()
        val badges = mutableListOf<GoldenBadgeTemplateExtractor.ExtractedBadge>()
        loaded.forEach { (sample, bitmap) ->
            badges += GoldenBadgeTemplateExtractor.extractRedBadges(sample, bitmap)
        }
        val written = if (System.getProperty("refreshSuitTemplates") == "true") {
            GoldenBadgeTemplateExtractor.writeRedSuitTemplates(templatesDir, badges).also { files ->
                println("Wrote ${files.size} template files under $templatesDir")
                files.forEach { println("  ${it.name}") }
            }
        } else {
            emptyList()
        }
        println("Extracted ${badges.size} red badges (${written.size} written; set -DrefreshSuitTemplates=true to save)")
        assertTrue("expected at least one extracted badge", badges.isNotEmpty())
    }

    @Test
    fun printClubBadgeDimensionsFromGolden() {
        val loaded = loadGoldenFixtures()
        loaded.filter { it.first.id == "20260814_205456" }.forEach { (sample, bitmap) ->
            GoldenBadgeTemplateExtractor.extractBlackBadges(sample, bitmap)
                .filter { it.suit == com.personal.solitaireassistant.game.Suit.Clubs }
                .forEach { badge ->
                    println(
                        "${badge.sampleId} ${badge.pile} " +
                            "${badge.badge.width}x${badge.badge.height} " +
                            "area=${badge.badge.width * badge.badge.height}"
                    )
                    badge.badge.recycle()
                }
            bitmap.recycle()
        }
    }

    @Test
    fun extractBlackSuitBadgesFromGoldenFixtures() {
        val loaded = loadGoldenFixtures()
        if (loaded.isEmpty()) return
        val templatesDir = locateTemplatesDir()
        val badges = mutableListOf<GoldenBadgeTemplateExtractor.ExtractedBadge>()
        loaded.forEach { (sample, bitmap) ->
            badges += GoldenBadgeTemplateExtractor.extractBlackBadges(sample, bitmap)
        }
        val written = if (System.getProperty("refreshSuitTemplates") == "true") {
            GoldenBadgeTemplateExtractor.writeBlackSuitTemplates(templatesDir, badges).also { files ->
                println("Wrote ${files.size} black template files under $templatesDir")
                files.forEach { println("  ${it.name}") }
            }
        } else {
            emptyList()
        }
        println("Extracted ${badges.size} black badges (${written.size} written; set -DrefreshSuitTemplates=true to save)")
        assertTrue("expected at least one extracted badge", badges.isNotEmpty())
    }

    private fun locateTemplatesDir(): File {
        val candidates = listOfNotNull(
            File(System.getProperty("user.dir"), "app/src/main/assets/templates")
                .takeIf { it.isDirectory },
            File(System.getProperty("user.dir"), "src/main/assets/templates")
                .takeIf { it.isDirectory },
            javaClass.classLoader?.getResource("golden/README.md")?.let { url ->
                File(File(url.toURI()).parentFile, "../../../main/assets/templates")
            }?.takeIf { it.isDirectory }
        )
        return candidates.firstOrNull()
            ?: error("templates dir not found (tried ${candidates.size} paths)")
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
            val sample = jsonStream.bufferedReader().use { GoldenTruthJson.fromJson(it.readText()) }
            val bitmap = pngStream.use { BitmapFactory.decodeStream(it)!! }
            sample to bitmap
        }
    }
}
