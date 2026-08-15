package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

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
}
