package com.personal.solitaireassistant.overlay

import com.personal.solitaireassistant.game.BoardRegion

/**
 * Maps normalized board regions between capture bitmap space and overlay screen space.
 */
object CoordinateMapper {
    fun mapRegion(
        region: BoardRegion,
        srcWidth: Int,
        srcHeight: Int,
        dstWidth: Int,
        dstHeight: Int
    ): BoardRegion {
        if (srcWidth <= 0 || srcHeight <= 0) return region
        val sx = dstWidth.toFloat() / srcWidth.toFloat()
        val sy = dstHeight.toFloat() / srcHeight.toFloat()
        return BoardRegion(
            left = region.left * sx,
            top = region.top * sy,
            right = region.right * sx,
            bottom = region.bottom * sy
        )
    }
}
