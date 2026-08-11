package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.KlondikeRules
import com.personal.solitaireassistant.game.Move

object MoveGenerator {
    fun generate(state: GameState): List<Move> = KlondikeRules.legalMoves(state)
}
