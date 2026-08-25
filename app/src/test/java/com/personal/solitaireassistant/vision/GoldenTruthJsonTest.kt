package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenTruthJsonTest {
    @Test
    fun roundTripPreservesSlotLabels() {
        val sample = GoldenSample(
            id = "20260814_120533",
            frameWidth = 1080,
            frameHeight = 2340,
            slots = listOf(
                GoldenSlot(
                    pile = "waste",
                    index = 0,
                    bounds = BoardRegion(100f, 200f, 180f, 320f),
                    engine = SlotGuess(SlotKind.FaceUp, Rank.Five, Suit.Hearts),
                    truth = SlotGuess(SlotKind.FaceUp, Rank.Six, Suit.Hearts),
                    inferred = false
                ),
                GoldenSlot(
                    pile = "tableau:3",
                    index = 2,
                    bounds = BoardRegion(400f, 800f, 480f, 980f),
                    engine = SlotGuess(SlotKind.Unknown),
                    truth = SlotGuess(SlotKind.FaceDown),
                    inferred = true
                )
            )
        )
        val parsed = GoldenTruthJson.fromJson(GoldenTruthJson.toJson(sample))
        assertEquals(sample.id, parsed.id)
        assertEquals(sample.frameWidth, parsed.frameWidth)
        assertEquals(sample.frameHeight, parsed.frameHeight)
        assertEquals(2, parsed.slots.size)
        val waste = parsed.slots[0]
        assertEquals("waste", waste.pile)
        assertEquals(SlotKind.FaceUp, waste.engine.kind)
        assertEquals(Rank.Five, waste.engine.rank)
        assertEquals(Suit.Hearts, waste.engine.suit)
        assertEquals(Rank.Six, waste.truth.rank)
        assertFalse(waste.inferred)
        assertEquals(100f, waste.bounds.left, 0.01f)
        assertEquals(true, parsed.slots[1].inferred)
        assertEquals(SlotKind.FaceDown, parsed.slots[1].truth.kind)
    }

    @Test
    fun rejectionMetadataSerializesAndRoundTripsWithoutIt() {
        val sample = GoldenSample(
            id = "reject_20260817_105400",
            frameWidth = 1080,
            frameHeight = 2340,
            slots = listOf(
                GoldenSlot(
                    pile = "waste",
                    index = 0,
                    bounds = BoardRegion(100f, 200f, 180f, 320f),
                    engine = SlotGuess(SlotKind.FaceUp, Rank.Three, Suit.Hearts),
                    truth = SlotGuess(SlotKind.FaceUp, Rank.Three, Suit.Hearts),
                    inferred = false
                )
            )
        )
        val meta = RejectionMeta(
            moveLabel = "Three Hearts → Foundation 1",
            fingerprint = "Three_Hearts->Ace_Spades",
            from = BoardRegion(400f, 800f, 480f, 980f),
            to = BoardRegion(100f, 200f, 180f, 320f)
        )
        val json = GoldenTruthJson.toJson(sample, meta)
        assertTrue(json.contains("\"rejectedMove\""))
        assertTrue(json.contains("\"fingerprint\""))
        assertTrue(json.contains("\"arrowFrom\""))
        assertTrue(json.contains("\"arrowTo\""))
        assertTrue(json.contains("Three Hearts → Foundation 1"))

        val parsed = GoldenTruthJson.fromJson(json)
        assertEquals(sample.id, parsed.id)
        assertEquals(sample.frameWidth, parsed.frameWidth)
        assertEquals(1, parsed.slots.size)
        assertEquals(Rank.Three, parsed.slots[0].engine.rank)
        assertEquals(Suit.Hearts, parsed.slots[0].engine.suit)
    }

    @Test
    fun errorCaptureMetadataAndExtendedSlotsRoundTrip() {
        val sample = GoldenSample(
            id = "error_20260824_221530",
            frameWidth = 1080,
            frameHeight = 2340,
            slots = listOf(
                GoldenSlot(
                    pile = "waste",
                    index = 0,
                    bounds = BoardRegion(100f, 200f, 180f, 320f),
                    engine = SlotGuess(SlotKind.FaceUp, Rank.Six, Suit.Spades),
                    truth = SlotGuess(SlotKind.FaceUp, Rank.Six, Suit.Spades),
                    inferred = false,
                    confidence = 0.67f,
                    diagnostic = "match-Six-Spades@0.67",
                    trace = RecognitionTrace(
                        rankSource = "rank-png",
                        rankScore = 0.71f,
                        rankTemplates = "Six=0.42 Nine=0.44",
                        suitSource = "suit-png",
                        suitScore = 0.88f,
                        postSteps = listOf("black-tiebreak:club")
                    )
                )
            )
        )
        val meta = ErrorCaptureMeta(
            stateSignature = "Six_Spades|-|-",
            stableHits = 3,
            detectionConfidence = 0.84f,
            diagnostics = listOf("board-found", "live-play"),
            violations = listOf(
                RecognitionViolation.DuplicateCard(
                    cardId = "King_Spades",
                    locations = listOf("tableau:2:1", "tableau:4:0")
                ),
                RecognitionViolation.CascadeBreak(
                    pile = "tableau:3",
                    lowerIndex = 4,
                    upperIndex = 3,
                    lowerCard = "Eight_Clubs",
                    upperCard = "Ten_Spades"
                )
            )
        )
        val json = GoldenTruthJson.toJson(sample, errorCapture = meta)
        assertTrue(json.contains("\"captureType\": \"recognition_error\""))
        assertTrue(json.contains("\"confidence\": 0.67"))
        assertTrue(json.contains("\"rankSource\": \"rank-png\""))

        val parsedSample = GoldenTruthJson.fromJson(json)
        assertEquals(sample.id, parsedSample.id)
        assertEquals(1, parsedSample.slots.size)

        val parsedMeta = GoldenTruthJson.parseErrorCaptureMeta(json)
        requireNotNull(parsedMeta)
        assertEquals(meta.stateSignature, parsedMeta.stateSignature)
        assertEquals(meta.stableHits, parsedMeta.stableHits)
        assertEquals(meta.detectionConfidence, parsedMeta.detectionConfidence, 0.001f)
        assertEquals(meta.diagnostics, parsedMeta.diagnostics)
        assertEquals(2, parsedMeta.violations.size)
        assertTrue(parsedMeta.violations[0] is RecognitionViolation.DuplicateCard)
        assertTrue(parsedMeta.violations[1] is RecognitionViolation.CascadeBreak)
        assertEquals(ErrorCapturePolicy.VIOLATION_SOURCE_FINAL, parsedMeta.violationSource)
    }

    @Test
    fun errorCaptureViolationSourceRoundTrips() {
        val sample = GoldenSample(
            id = "error_raw_test",
            frameWidth = 1080,
            frameHeight = 2340,
            slots = emptyList()
        )
        val meta = ErrorCaptureMeta(
            stateSignature = "sig",
            stableHits = 2,
            detectionConfidence = 0.8f,
            diagnostics = emptyList(),
            violations = listOf(
                RecognitionViolation.DuplicateCard(
                    cardId = "Six_Spades",
                    locations = listOf("waste:0", "tableau:1:0")
                )
            ),
            violationSource = ErrorCapturePolicy.VIOLATION_SOURCE_RAW
        )
        val json = GoldenTruthJson.toJson(sample, errorCapture = meta)
        val parsed = requireNotNull(GoldenTruthJson.parseErrorCaptureMeta(json))
        assertEquals(ErrorCapturePolicy.VIOLATION_SOURCE_RAW, parsed.violationSource)
    }

    @Test
    fun fixtureTruthLabelsUseEachCardAtMostOnce() {
        val readme = javaClass.classLoader!!.getResource("golden/README.md")
            ?: error("golden/README.md missing from test resources")
        val dir = runCatching { File(readme.toURI()).parentFile }.getOrNull()
            ?: error("Could not resolve golden/ directory from test resources")
        val duplicates = mutableListOf<String>()
        dir.listFiles { _, name -> name.endsWith(".json", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val sample = GoldenTruthJson.fromJson(file.readText())
                val seen = linkedMapOf<String, String>()
                sample.slots
                    .filter { !it.inferred && it.truth.kind == SlotKind.FaceUp }
                    .forEach { slot ->
                        val rank = slot.truth.rank ?: return@forEach
                        val suit = slot.truth.suit ?: return@forEach
                        val cardId = "${rank.name}_${suit.name}"
                        val location = "${slot.pile}:${slot.index}"
                        val previous = seen.putIfAbsent(cardId, location)
                        if (previous != null) {
                            duplicates +=
                                "${sample.id} $cardId at $location and $previous"
                        }
                    }
            }
        assertTrue(
            "Duplicate face-up truth cards (impossible in one deck):\n" +
                duplicates.joinToString("\n"),
            duplicates.isEmpty()
        )
    }
}
