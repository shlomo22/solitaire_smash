package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.GameState

/**
 * Live-play stuck signal: two full stock/waste cycles with no waste card
 * played to tableau or foundation. Vision never sees [GameState.recyclesUsed]
 * (always 0 on detected boards), so this tracks confirmed board transitions
 * instead.
 *
 * Call [onConfirmedTransition] only from the fully-confirmed analysis branch,
 * the same place deal-reset and move-history fire — not the weak stabilizing
 * path (mid-animation stock/waste flicker would invent false recycles).
 */
class WasteCycleStuckTracker(
    private val stuckAfterIdleRecycles: Int = DEFAULT_STUCK_AFTER_IDLE_RECYCLES
) {
    var idleRecycles: Int = 0
        private set

    val isStuck: Boolean
        get() = idleRecycles >= stuckAfterIdleRecycles

    fun reset() {
        idleRecycles = 0
    }

    /**
     * Observe a confirmed previous → current board pair. Returns a short
     * reason when the counter changes (for analysis.log); null if unchanged.
     */
    fun onConfirmedTransition(previous: GameState?, current: GameState): String? {
        if (previous == null || previous == current) return null

        if (wastePlayedToBoard(previous, current)) {
            if (idleRecycles == 0) return null
            idleRecycles = 0
            return "waste-play-reset"
        }

        if (looksLikeRecycle(previous, current)) {
            idleRecycles++
            return "idle-recycle=$idleRecycles"
        }

        return null
    }

    companion object {
        const val DEFAULT_STUCK_AFTER_IDLE_RECYCLES = 2

        /**
         * Previous waste top appears as a new tableau or foundation top —
         * the card left waste onto the board.
         */
        fun wastePlayedToBoard(previous: GameState, current: GameState): Boolean {
            val wasteTop = previous.wasteTop() ?: return false
            if (!wasteTop.known) return false
            for (col in current.tableau) {
                val top = col.lastOrNull() ?: continue
                if (top.id == wasteTop.id && previous.tableau.none { it.lastOrNull()?.id == wasteTop.id }) {
                    return true
                }
            }
            for (i in current.foundations.indices) {
                val top = current.foundations[i].lastOrNull() ?: continue
                if (top.id != wasteTop.id) continue
                val prevTop = previous.foundations[i].lastOrNull()
                if (prevTop?.id != wasteTop.id) return true
            }
            return false
        }

        /**
         * Stock was empty with cards in waste; after the transition stock is
         * refilled and waste is empty — classic recycle shape. Draw-3 order
         * is preserved by reversing waste into stock in [KlondikeRules], so
         * we only need the empty↔refilled silhouette (vision does not give
         * full pile contents).
         */
        fun looksLikeRecycle(previous: GameState, current: GameState): Boolean {
            if (previous.stock.isNotEmpty()) return false
            if (previous.waste.isEmpty()) return false
            if (current.stock.isEmpty()) return false
            if (current.waste.isNotEmpty()) return false
            return true
        }
    }
}
