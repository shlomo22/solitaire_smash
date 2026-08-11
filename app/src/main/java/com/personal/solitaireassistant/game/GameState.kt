package com.personal.solitaireassistant.game

/**
 * Immutable Klondike board for draw-3 play.
 *
 * Stock is ordered so index 0 is the next card drawn.
 * Waste is ordered so the last element is the currently playable top card.
 * Foundations are suit piles with the last element as the top.
 * Tableau columns keep cards from bottom (index 0) to top (last).
 */
data class GameState(
    val tableau: List<List<Card>>,
    val foundations: List<List<Card>>,
    val stock: List<Card>,
    val waste: List<Card>,
    val recyclesUsed: Int = 0
) {
    init {
        require(tableau.size == 7) { "Klondike requires 7 tableau piles" }
        require(foundations.size == 4) { "Klondike requires 4 foundations" }
    }

    fun foundationTop(index: Int): Card? = foundations[index].lastOrNull()

    fun tableauTop(index: Int): Card? = tableau[index].lastOrNull()

    fun wasteTop(): Card? = waste.lastOrNull()

    fun isEmptyStock(): Boolean = stock.isEmpty()

    fun hiddenTableauCount(): Int =
        tableau.sumOf { column -> column.count { !it.faceUp } }

    fun foundationCount(): Int = foundations.sumOf { it.size }

    companion object {
        fun empty(): GameState = GameState(
            tableau = List(7) { emptyList() },
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
    }
}

data class BoardRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Pixel-space anchors used for overlay arrows.
 * Coordinates are relative to the captured frame / screen.
 */
data class CardLocation(
    val pile: PileRef,
    val cardIndex: Int,
    val bounds: BoardRegion
)

sealed class PileRef {
    data class Tableau(val index: Int) : PileRef()
    data class Foundation(val index: Int) : PileRef()
    data object Stock : PileRef()
    data object Waste : PileRef()
}
