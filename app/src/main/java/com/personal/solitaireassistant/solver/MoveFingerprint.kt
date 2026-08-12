package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Move

/**
 * Stable identity for a move based on the cards involved, not column indices.
 * Used to remember user-rejected hints across board states.
 */
object MoveFingerprint {
    fun of(state: GameState, move: Move): String = when (move) {
        is Move.TableauToTableau -> {
            val column = state.tableau[move.fromColumn]
            val moving = column[move.startIndex]
            val target = state.tableauTop(move.toColumn)
            "${moving.id}->${target?.id ?: "EMPTY"}"
        }
        is Move.TableauToFoundation -> {
            val card = state.tableau[move.fromColumn].last()
            val foundationTop = state.foundations[move.toFoundation].lastOrNull()
            "${card.id}->F${foundationTop?.id ?: "EMPTY"}"
        }
        is Move.WasteToTableau -> {
            val waste = requireNotNull(state.wasteTop())
            val target = state.tableauTop(move.toColumn)
            "${waste.id}->${target?.id ?: "EMPTY"}"
        }
        is Move.WasteToFoundation -> {
            val waste = requireNotNull(state.wasteTop())
            val foundationTop = state.foundations[move.toFoundation].lastOrNull()
            "${waste.id}->F${foundationTop?.id ?: "EMPTY"}"
        }
        Move.DrawStock -> "DRAW"
        Move.RecycleWaste -> "RECYCLE"
    }
}
