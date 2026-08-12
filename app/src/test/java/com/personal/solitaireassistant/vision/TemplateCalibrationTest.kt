package com.personal.solitaireassistant.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateCalibrationTest {
    @Test
    fun identicalMaskScoresOne() {
        val mask = buildMask(
            listOf(
                ".....1111.....",
                "....111111....",
                "...11111111...",
                "....111111....",
                ".....1111....."
            )
        )
        val score = MaskScoreHarness.score(mask, mask)
        assertEquals(1f, score, 0.02f)
    }

    @Test
    fun distinctMaskBeatsWeakNeighbor() {
        val three = buildMask(
            listOf(
                "...111...",
                "..11111..",
                ".1111111.",
                "..11111..",
                "...111..."
            )
        )
        val eight = buildMask(
            listOf(
                ".1111111.",
                "11....11.",
                "11....11.",
                ".1111111.",
                "11....11."
            )
        )
        val source = three
        val threeScore = MaskScoreHarness.score(source, three)
        val eightScore = MaskScoreHarness.score(source, eight)
        assertTrue(
            "three=$threeScore eight=$eightScore",
            threeScore > eightScore + 0.05f
        )
    }

    @Test
    fun glyphRegionFractionsStayWithinCard() {
        val crop = android.graphics.Bitmap.createBitmap(100, 140, android.graphics.Bitmap.Config.ARGB_8888)
        val corner = TemplateGlyphRegions.cropRankCorner(crop)
        val badge = TemplateGlyphRegions.cropSuitBadge(crop)
        val glyph = TemplateGlyphRegions.cropRankGlyph(crop)
        try {
            assertTrue(corner!!.width <= crop.width * TemplateGlyphRegions.RANK_CORNER_WIDTH + 1)
            assertTrue(corner.height <= crop.height * TemplateGlyphRegions.RANK_CORNER_HEIGHT + 1)
            assertTrue(badge!!.width <= crop.width * TemplateGlyphRegions.SUIT_BADGE_WIDTH + 1)
            assertTrue(glyph!!.width <= crop.width * TemplateGlyphRegions.RANK_GLYPH_WIDTH + 1)
        } finally {
            corner?.recycle()
            badge?.recycle()
            glyph?.recycle()
            crop.recycle()
        }
    }

    private fun buildMask(rows: List<String>): LongArray {
        val size = 48
        return LongArray(size) { y ->
            if (y >= rows.size) return@LongArray 0L
            val row = rows[y]
            var bits = 0L
            for (x in row.indices) {
                if (row[x] == '1' || row[x] == '#') {
                    val mapped = (x * size / row.length).coerceIn(0, size - 1)
                    bits = bits or (1L shl mapped)
                }
            }
            bits
        }
    }

    private object MaskScoreHarness {
        fun score(source: LongArray, template: LongArray): Float {
            val size = 48
            val widthMask = (1L shl size) - 1L
            val sourceInk = source.sumOf { java.lang.Long.bitCount(it) }
            var best = 0f
            for (dy in -2..2) {
                for (dx in -2..2) {
                    var intersection = 0
                    var templateInk = 0
                    for (y in 0 until size) {
                        val templateY = y - dy
                        if (templateY !in 0 until size) continue
                        val templateRow = when {
                            dx > 0 -> (template[templateY] shl dx) and widthMask
                            dx < 0 -> template[templateY] ushr -dx
                            else -> template[templateY]
                        }
                        templateInk += java.lang.Long.bitCount(templateRow)
                        intersection += java.lang.Long.bitCount(source[y] and templateRow)
                    }
                    val denom = sourceInk + templateInk
                    if (denom > 0) best = kotlin.math.max(best, 2f * intersection / denom)
                }
            }
            return best
        }
    }
}
