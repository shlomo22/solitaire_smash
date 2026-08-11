package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BoardLocatorTest {
    @Test
    fun locateReturnsFullFrameBoardAndSevenColumns() {
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val locator = BoardLocator()
        val board = locator.locate(bitmap)
        assertEquals(0f, board.bounds.left, 0.01f)
        assertEquals(1080f, board.bounds.right, 0.01f)
        val columns = locator.tableauColumnRegions(board)
        assertEquals(7, columns.size)
        assertEquals(4, locator.foundationRegions(board).size)
        assertTrue(locator.stockRegion(board).left > locator.wasteRegion(board).left)
        bitmap.recycle()
    }
}
