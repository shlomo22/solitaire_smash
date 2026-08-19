package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WasteOcrRankOverrideTest {
    private val jackHearts = Card(Rank.Jack, Suit.Hearts, faceUp = true)

    @Test
    fun ocrThreeOverridesStrongJackFusion() {
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Three,
            legacyCard = jackHearts,
            tightCard = null,
            baseCard = jackHearts
        )
        assertEquals(Rank.Three, override)
    }

    @Test
    fun ocrFiveOverridesStrongJackFusion() {
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Five,
            legacyCard = jackHearts,
            tightCard = null,
            baseCard = jackHearts
        )
        assertEquals(Rank.Five, override)
    }

    @Test
    fun ocrKingOverridesTenFusion() {
        val tenHearts = Card(Rank.Ten, Suit.Hearts, faceUp = true)
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.King,
            legacyCard = tenHearts,
            tightCard = Card(Rank.King, Suit.Hearts, faceUp = true),
            baseCard = tenHearts
        )
        assertEquals(Rank.King, override)
    }

    @Test
    fun ocrMissDoesNotOverrideUnrelatedFusion() {
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = null,
            legacyCard = jackHearts,
            tightCard = null,
            baseCard = jackHearts
        )
        assertNull(override)
    }

    @Test
    fun ocrMatchingCandidateStillWins() {
        val override = WasteRankCorrections.ocrRankOverride(
            ocrRank = Rank.Jack,
            legacyCard = jackHearts,
            tightCard = Card(Rank.Three, Suit.Hearts, faceUp = true),
            baseCard = jackHearts
        )
        assertEquals(Rank.Jack, override)
    }
}
