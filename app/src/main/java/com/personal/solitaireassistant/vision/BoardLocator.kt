package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.CardLocation
import com.personal.solitaireassistant.game.PileRef

/**
 * Relative geometry for portrait Solitaire Smash.
 *
 * Layout (confirmed from device screenshots):
 * foundations (4) on the left, waste, then stock on the far right.
 * Header/time/score sit above; End/Undo/Rules sit below.
 */
data class BoardGeometryProfile(
    val name: String = "solitaire_smash_s25",
    // Calibrated from Samsung S25+ 1080x2340 device screenshots.
    // Header/time/score sit ~0.04–0.12; foundations/stock are mid-upper (~0.215–0.305).
    // Tableau starts below that gap (~0.35).
    val stock: RectF = RectF(0.855f, 0.215f, 0.981f, 0.305f),
    val waste: RectF = RectF(0.575f, 0.215f, 0.840f, 0.305f),
    val foundations: List<RectF> = listOf(
        RectF(0.015f, 0.215f, 0.143f, 0.305f),
        RectF(0.155f, 0.215f, 0.282f, 0.305f),
        RectF(0.295f, 0.215f, 0.423f, 0.305f),
        RectF(0.436f, 0.215f, 0.563f, 0.305f)
    ),
    val tableauTop: Float = 0.358f,
    // Long late-game runs extend well below the opening-deal area.
    val tableauBottom: Float = 0.780f,
    val tableauLeft: Float = 0.015f,
    val tableauRight: Float = 0.985f,
    val cardAspect: Float = 1.40f,
    // Measured, not assumed: the exposed height of an overlapped face-up card
    // is 48.90px against a 192.76px card (137.68 * cardAspect), i.e. 0.2537.
    // Taken from the rank-glyph row spacing in 12 long cascades across the
    // golden set - 88 intervals, all between 48.67 and 49.17, with no
    // dependence on cascade length. The previous 0.28 (53.97px) was ~10% too
    // large, and because cascade slots are placed at firstFaceTop + i * step
    // the error accumulates: by the 8th card a slot sits a full card low and
    // reads its neighbour instead. Replaying all 37 golden cascade columns,
    // 0.28 puts 38 of 179 slots more than half a card off (mean error
    // 16.9px); 0.2537 puts 1 of 179 off (mean error 1.0px).
    val faceUpOverlap: Float = 0.2537f,
    // Never got the same empirical pass as faceUpOverlap above - still had the
    // old guessed value. Measured the same way (teal-back transition spacing
    // this time, cleaner than glyph ink since it's one saturated color with no
    // per-rank shape variance): 0.23 (44.34px) undershoots the true ~49px
    // step by ~4.6px per face-down card. Since firstFaceTop for the face-up
    // cascade is columnRegion.top + faceDownCount * downStep, that error
    // accumulates through the face-down run and gets carried into every card
    // below it - a 3-deep face-down stack was enough to misread the exposed
    // card 5+ slots into the cascade (e.g. Eight of Diamonds read as the
    // Seven of Clubs sitting right below it). True face-down spacing measured
    // the same as face-up: reuse 0.2537.
    val faceDownOverlap: Float = 0.2537f
)

data class LocatedBoard(
    val bounds: BoardRegion,
    val profile: BoardGeometryProfile,
    val confidence: Float
)

class BoardLocator(
    private val profile: BoardGeometryProfile = BoardGeometryProfile()
) {
    companion object {
        /** Screen-space rank-corner patch for OCR on fanned waste crops. */
        fun wasteRankCornerRegion(cardRegion: BoardRegion): BoardRegion =
            BoardRegion(
                left = cardRegion.left,
                top = cardRegion.top,
                right = cardRegion.left + cardRegion.width * 0.44f,
                bottom = cardRegion.top + cardRegion.height * 0.30f
            )
    }

    /**
     * Use nearly full-frame bounds. Solitaire Smash fills the portrait screen;
     * header/footer are excluded via profile fractions.
     */
    fun locate(bitmap: Bitmap): LocatedBoard {
        val bounds = BoardRegion(
            left = 0f,
            top = 0f,
            right = bitmap.width.toFloat(),
            bottom = bitmap.height.toFloat()
        )
        return LocatedBoard(bounds, profile, confidence = 0.85f)
    }

    fun stockRegion(board: LocatedBoard): BoardRegion =
        map(board.bounds, board.profile.stock)

    fun wasteRegion(board: LocatedBoard): BoardRegion =
        map(board.bounds, board.profile.waste)

    /** Top-most (playable) waste card region; waste fans leftward underneath. */
    fun wasteTopRegion(board: LocatedBoard): BoardRegion {
        val full = wasteRegion(board)
        val cardWidth = full.width * 0.55f
        return BoardRegion(
            left = full.right - cardWidth,
            top = full.top,
            right = full.right,
            bottom = full.bottom
        )
    }

    /** Tighter front-card crop for waste fans that do not reach the stock gap. */
    fun tightWasteTopRegion(board: LocatedBoard): BoardRegion {
        val full = wasteRegion(board)
        val right = full.left + full.width * 0.79f
        val cardWidth = (right - full.left) * 0.65f
        return BoardRegion(
            left = right - cardWidth,
            top = full.top,
            right = right,
            bottom = full.bottom
        )
    }

    /** Foundation-width crop aligned to golden snapshot bounds (r~846 at 1080w). */
    fun inkAnchoredWasteCardRegion(board: LocatedBoard): BoardRegion {
        val full = wasteRegion(board)
        val cardWidth = foundationRegions(board).first().width
        val right = full.left + full.width * 0.786f
        return BoardRegion(
            left = right - cardWidth,
            top = full.top,
            right = right,
            bottom = full.bottom
        )
    }

    /** Foundation-width crop aligned to legacy waste fan (r~895 at 1080w). */
    fun legacyAnchoredWasteCardRegion(board: LocatedBoard): BoardRegion {
        val full = wasteRegion(board)
        val cardWidth = foundationRegions(board).first().width
        val right = full.right - full.width * 0.043f
        return BoardRegion(
            left = right - cardWidth,
            top = full.top,
            right = right,
            bottom = full.bottom
        )
    }

    /** Candidate full-card regions used for waste rank OCR. */
    fun wasteOcrCardRegions(board: LocatedBoard): List<BoardRegion> =
        listOf(
            inkAnchoredWasteCardRegion(board),
            legacyAnchoredWasteCardRegion(board),
            wasteTopRegion(board),
            tightWasteTopRegion(board)
        )

    fun foundationRegions(board: LocatedBoard): List<BoardRegion> =
        board.profile.foundations.map { map(board.bounds, it) }

    fun tableauColumnRegions(board: LocatedBoard): List<BoardRegion> {
        val p = board.profile
        val width = board.bounds.width
        val left = board.bounds.left + width * p.tableauLeft
        val right = board.bounds.left + width * p.tableauRight
        val top = board.bounds.top + board.bounds.height * p.tableauTop
        val bottom = board.bounds.top + board.bounds.height * p.tableauBottom
        val colWidth = (right - left) / 7f
        return (0 until 7).map { i ->
            BoardRegion(
                left = left + i * colWidth + colWidth * 0.04f,
                top = top,
                right = left + (i + 1) * colWidth - colWidth * 0.04f,
                bottom = bottom
            )
        }
    }

    fun estimateCardSlots(
        column: BoardRegion,
        cardCount: Int,
        faceUpFrom: Int,
        profile: BoardGeometryProfile
    ): List<BoardRegion> {
        if (cardCount <= 0) return emptyList()
        val cardWidth = column.width
        val cardHeight = cardWidth * profile.cardAspect
        var y = column.top
        return (0 until cardCount).map { index ->
            val region = BoardRegion(
                left = column.left,
                top = y,
                right = column.right,
                bottom = (y + cardHeight).coerceAtMost(column.bottom)
            )
            val overlap = if (index < faceUpFrom) profile.faceDownOverlap else profile.faceUpOverlap
            y += cardHeight * overlap
            region
        }
    }

    fun toCardLocation(pile: PileRef, index: Int, region: BoardRegion): CardLocation =
        CardLocation(pile, index, region)

    private fun map(board: BoardRegion, rel: RectF): BoardRegion {
        val w = board.width
        val h = board.height
        return BoardRegion(
            left = board.left + rel.left * w,
            top = board.top + rel.top * h,
            right = board.left + rel.right * w,
            bottom = board.top + rel.bottom * h
        )
    }
}
