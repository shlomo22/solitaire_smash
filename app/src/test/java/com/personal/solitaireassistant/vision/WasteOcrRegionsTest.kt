package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.BoardRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WasteOcrRegionsTest {
    private val locator = BoardLocator()

    @Test
    fun inkAnchoredRegionMatchesGoldenSnapshotWidth() {
        val board = board1080x2340()
        val region = locator.inkAnchoredWasteCardRegion(board)
        assertEquals(707.76f, region.left, 1.5f)
        assertEquals(846f, region.right, 1.5f)
    }

    @Test
    fun legacyAnchoredRegionMatchesWideGoldenBounds() {
        val board = board1080x2340()
        val region = locator.legacyAnchoredWasteCardRegion(board)
        assertEquals(756.76f, region.left, 1.5f)
        assertEquals(895f, region.right, 1.5f)
    }

    @Test
    fun wasteRankCornerRegionCoversTopLeftPatch() {
        val card = BoardRegion(707f, 503f, 846f, 714f)
        val corner = BoardLocator.wasteRankCornerRegion(card)
        assertEquals(707f, corner.left, 0.01f)
        // Top is inset 4% of card height past the card's own drop-shadow/
        // border-transition band, not flush with the card's top edge.
        assertEquals(511.44f, corner.top, 0.01f)
        // Wider/taller so a full Smash "8" (two loops) fits (v1.4.92).
        assertEquals(707f + card.width * 0.48f, corner.right, 0.01f)
        assertEquals(503f + card.height * 0.42f, corner.bottom, 0.01f)
        assertTrue(corner.width >= 50f)
        assertTrue(corner.height >= 50f)
    }

    private fun board1080x2340(): LocatedBoard {
        val bitmap = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        return try {
            locator.locate(bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
