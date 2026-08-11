package com.personal.solitaireassistant.overlay

import com.personal.solitaireassistant.game.BoardRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {
    @Test
    fun scalesRegionsProportionally() {
        val src = BoardRegion(100f, 200f, 200f, 400f)
        val mapped = CoordinateMapper.mapRegion(src, 1000, 2000, 500, 1000)
        assertEquals(50f, mapped.left, 0.01f)
        assertEquals(100f, mapped.top, 0.01f)
        assertEquals(100f, mapped.right, 0.01f)
        assertEquals(200f, mapped.bottom, 0.01f)
    }
}
