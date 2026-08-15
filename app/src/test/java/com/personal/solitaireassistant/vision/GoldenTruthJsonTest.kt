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
