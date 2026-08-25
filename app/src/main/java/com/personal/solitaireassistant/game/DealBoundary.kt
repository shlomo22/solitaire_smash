package com.personal.solitaireassistant.game

/**
 * Detects when the on-screen board is a different Klondike deal so Cancel'd
 * hints from the previous game can be dropped.
 *
 * Rejections are fingerprinted by card identity alone (`8H->9C`). That pairing
 * is meaningless on a later random deal, but the opening 1..7 layout is easy
 * to miss (overlay often starts mid-game, or the deal screen is skipped), so
 * a fresh-deal check by itself is not enough.
 */
object DealBoundary {
    /** Hidden-count rise that cannot be an undo (undo flips at most one card). */
    const val HIDDEN_JUMP_MIN = 4

    fun looksLikeFreshDeal(state: GameState): Boolean {
        if (state.foundations.any { it.isNotEmpty() }) return false
        if (state.waste.isNotEmpty()) return false
        if (state.tableau.size != 7) return false
        return state.tableau.withIndex().all { (index, column) ->
            column.size == index + 1 &&
                column.dropLast(1).all { !it.faceUp } &&
                column.last().faceUp
        }
    }

    /**
     * Why [current] looks like a different game from [previous], or null if
     * it still belongs to the same deal.
     */
    fun newGameReason(previous: GameState?, current: GameState): String? {
        if (looksLikeFreshDeal(current)) {
            if (previous == null || !looksLikeFreshDeal(previous)) return "fresh-layout"
            return null
        }
        if (previous == null) return null

        val hiddenDelta = current.hiddenTableauCount() - previous.hiddenTableauCount()
        if (hiddenDelta >= HIDDEN_JUMP_MIN) return "hidden-jump+$hiddenDelta"

        val foundationDelta = current.foundationCount() - previous.foundationCount()
        if (foundationDelta <= -2) return "foundation-drop$foundationDelta"

        if (knownCardsTurnedOver(previous, current)) return "known-set-turnover"
        return null
    }

    fun isNewGame(previous: GameState?, current: GameState): Boolean =
        newGameReason(previous, current) != null

    private fun knownCardsTurnedOver(previous: GameState, current: GameState): Boolean {
        val prev = knownIds(previous)
        val now = knownIds(current)
        if (prev.size < 8 || now.size < 8) return false
        return prev.intersect(now).size <= 2
    }

    private fun knownIds(state: GameState): Set<String> {
        val ids = mutableSetOf<String>()
        fun add(card: Card) {
            if (card.faceUp && card.known) ids += card.id
        }
        state.tableau.forEach { col -> col.forEach(::add) }
        state.waste.forEach(::add)
        state.foundations.forEach { pile -> pile.forEach(::add) }
        return ids
    }
}
