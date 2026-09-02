package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WasteUnreadProbeTest {
    @Test
    fun unreadQueenOfDiamondsRecoveredByOpenQueenShape() {
        val waste = detectWaste("20260814_125606")
        assertEquals(Rank.Queen, waste.rank)
        assertEquals(Suit.Diamonds, waste.suit)
        assertTrue(waste.known)
    }

    @Test
    fun unreadTenOfSpadesRecoveredByDenseTenShape() {
        val waste = detectWaste("20260825_172118")
        assertEquals(Rank.Ten, waste.rank)
        assertEquals(Suit.Spades, waste.suit)
        assertTrue(waste.known)
    }

    @Test
    fun openQueenGateIsUniqueOn125606Crop() {
        val bmp = loadGolden("20260814_125606")
        // Exact locate crop from local probe: 705-843.
        val crop = android.graphics.Bitmap.createBitmap(bmp, 705, 503, 843 - 705, 714 - 503)
        assertTrue(RankInkHeuristics.matchesOpenQueen(crop))
        assertFalse(RankInkHeuristics.matchesDenseTen(crop))
        crop.recycle()
        bmp.recycle()
    }

    @Test
    fun denseTenGateHits172118ExactCrop() {
        val bmp = loadGolden("20260825_172118")
        val crop = android.graphics.Bitmap.createBitmap(bmp, 660, 503, 798 - 660, 714 - 503)
        assertTrue(RankInkHeuristics.matchesDenseTen(crop))
        assertFalse(RankInkHeuristics.matchesOpenQueen(crop))
        crop.recycle()
        bmp.recycle()
    }

    private fun detectWaste(id: String): com.personal.solitaireassistant.game.Card {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bmp = loadGolden(id)
        val detector = GameStateDetector(context, minConfidence = 0.5f)
        val result = detector.detect(bmp)
        val waste = result.state?.waste?.firstOrNull()
        println("PROBE $id waste=$waste")
        result.diagnostics.filter { it.startsWith("waste") }.forEach { println("  $it") }
        assertNotNull("$id missing waste", waste)
        detector.release()
        bmp.recycle()
        return waste!!
    }

    private fun loadGolden(id: String): android.graphics.Bitmap {
        val stream = javaClass.classLoader!!.getResourceAsStream("golden/$id.png")
        requireNotNull(stream) { "missing $id.png" }
        return requireNotNull(BitmapFactory.decodeStream(stream))
    }
}
