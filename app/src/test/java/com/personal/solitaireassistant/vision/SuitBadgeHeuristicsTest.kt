package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SuitBadgeHeuristicsTest {
    @Test
    fun redSuitGuessReadsDiamondRhombus() {
        val badge = diamondBadge()
        val guess = SuitBadgeHeuristics.redSuitGuess(badge)
        assertNotNull(guess)
        assertEquals(Suit.Diamonds, guess!!.suit)
        badge.recycle()
    }

    @Test
    fun redSuitGuessReadsHeartCleft() {
        val badge = heartBadge()
        val guess = SuitBadgeHeuristics.redSuitGuess(badge)
        assertNotNull(guess)
        assertEquals(Suit.Hearts, guess!!.suit)
        badge.recycle()
    }

    @Test
    fun blackSuitStrictSpadeTipReadsSpadePoint() {
        val badge = spadeBadge()
        assertTrue(SuitBadgeHeuristics.blackSuitStrictSpadeTip(badge))
        badge.recycle()
    }

    @Test
    fun blackSuitClubShapeOverrideReadsClubLobes() {
        val badge = clubBadge()
        val override = SuitBadgeHeuristics.blackSuitClubShapeOverride(badge)
        assertNotNull(override)
        assertEquals(Suit.Clubs, override!!.suit)
        badge.recycle()
    }

    @Test
    fun blackSuitGuessReadsSpadePoint() {
        val badge = spadeBadge()
        val guess = SuitBadgeHeuristics.blackSuitGuess(badge)
        assertNotNull(guess)
        assertEquals(Suit.Spades, guess!!.suit)
        badge.recycle()
    }

    @Test
    fun blackSuitGuessReadsClubLobes() {
        val badge = clubBadge()
        val guess = SuitBadgeHeuristics.blackSuitGuess(badge)
        assertNotNull(guess)
        assertEquals(Suit.Clubs, guess!!.suit)
        badge.recycle()
    }

    private fun diamondBadge(): Bitmap {
        val size = 36
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cx = (size - 1) / 2f
        val cy = (size - 1) / 2f
        val hw = size * 0.38f
        val hh = size * 0.42f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val on = abs(x - cx) / hw + abs(y - cy) / hh <= 1f
                bmp.setPixel(x, y, if (on) RED else WHITE)
            }
        }
        return bmp
    }

    private fun heartBadge(): Bitmap {
        val size = 36
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cx = (size - 1) / 2f
        val r = size * 0.18f
        val leftCx = cx - r * 0.85f
        val rightCx = cx + r * 0.85f
        val lobeCy = size * 0.38f
        val tBottom = size * 0.88f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inLeft =
                    (x - leftCx) * (x - leftCx) + (y - lobeCy) * (y - lobeCy) <= r * r
                val inRight =
                    (x - rightCx) * (x - rightCx) + (y - lobeCy) * (y - lobeCy) <= r * r
                val half = ((tBottom - y) / (tBottom - lobeCy)) * (size * 0.36f)
                val inTri = y >= lobeCy && y <= tBottom && abs(x - cx) <= half
                bmp.setPixel(x, y, if (inLeft || inRight || inTri) RED else WHITE)
            }
        }
        return bmp
    }

    private fun spadeBadge(): Bitmap {
        val size = 36
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cx = (size - 1) / 2f
        val cy = size * 0.42f
        val hw = size * 0.36f
        val hh = size * 0.34f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inBody = abs(x - cx) / hw + abs(y - cy) / hh <= 1f && y < size * 0.72f
                val inStem = abs(x - cx) <= size * 0.07f && y >= size * 0.62f && y <= size * 0.92f
                val inBase = abs(x - cx) <= size * 0.16f && y >= size * 0.86f && y <= size * 0.94f
                bmp.setPixel(x, y, if (inBody || inStem || inBase) BLACK else WHITE)
            }
        }
        return bmp
    }

    private fun clubBadge(): Bitmap {
        val size = 36
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val r = size * 0.14f
        val cx = (size - 1) / 2f
        val topCy = size * 0.28f
        val leftCx = cx - r * 1.25f
        val rightCx = cx + r * 1.25f
        val lobeCy = size * 0.54f
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inTop = (x - cx) * (x - cx) + (y - topCy) * (y - topCy) <= r * r
                val inLeft =
                    (x - leftCx) * (x - leftCx) + (y - lobeCy) * (y - lobeCy) <= r * r
                val inRight =
                    (x - rightCx) * (x - rightCx) + (y - lobeCy) * (y - lobeCy) <= r * r
                val inStem = abs(x - cx) <= size * 0.07f && y >= size * 0.58f && y <= size * 0.92f
                val inBase = abs(x - cx) <= size * 0.16f && y >= size * 0.86f && y <= size * 0.94f
                bmp.setPixel(
                    x,
                    y,
                    if (inTop || inLeft || inRight || inStem || inBase) BLACK else WHITE
                )
            }
        }
        return bmp
    }

    companion object {
        private const val RED = 0xFFE01040.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF2A3540.toInt()
    }
}
