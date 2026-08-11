package com.personal.solitaireassistant.game

sealed class Move {
    abstract val label: String

    data class TableauToTableau(
        val fromColumn: Int,
        val startIndex: Int,
        val toColumn: Int
    ) : Move() {
        override val label: String =
            "Tableau ${fromColumn + 1} -> Tableau ${toColumn + 1}"
    }

    data class TableauToFoundation(
        val fromColumn: Int,
        val toFoundation: Int
    ) : Move() {
        override val label: String =
            "Tableau ${fromColumn + 1} -> Foundation ${toFoundation + 1}"
    }

    data class WasteToTableau(
        val toColumn: Int
    ) : Move() {
        override val label: String = "Waste -> Tableau ${toColumn + 1}"
    }

    data class WasteToFoundation(
        val toFoundation: Int
    ) : Move() {
        override val label: String = "Waste -> Foundation ${toFoundation + 1}"
    }

    data object DrawStock : Move() {
        override val label: String = "Draw from stock"
    }

    data object RecycleWaste : Move() {
        override val label: String = "Recycle waste to stock"
    }
}

data class ScoredMove(
    val move: Move,
    val score: Double,
    val rationale: String
)

/**
 * Suggested move plus endpoints for the overlay arrow.
 */
data class SuggestedMove(
    val scored: ScoredMove,
    val from: BoardRegion?,
    val to: BoardRegion?
)
