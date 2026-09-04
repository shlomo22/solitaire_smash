package com.personal.solitaireassistant.solver

import com.personal.solitaireassistant.game.GameState

/**
 * Live-play stuck signal: the same waste-top card has come back around with
 * no card having left waste onto the board since the last time it was seen.
 * That is the earliest possible confirmation that a full lap through the
 * stock made zero progress - no need to wait for a second full recycle, or
 * even a first *complete* one, since a repeat can only happen once every
 * card between the two sightings has already been shown and none of them
 * went anywhere. Vision never sees [GameState.recyclesUsed] (always 0 on
 * detected boards), so this tracks confirmed board transitions instead.
 *
 * Call [onConfirmedTransition] only from the fully-confirmed analysis
 * branch, the same place deal-reset and move-history fire — not the weak
 * stabilizing path (mid-animation stock/waste flicker would invent false
 * repeats).
 */
class WasteCycleStuckTracker {
    private val seenWasteTopIds = mutableSetOf<String>()

    var isStuck: Boolean = false
        private set

    /** How many distinct waste-top cards have been seen since the last card left waste - for analysis.log. */
    val seenSinceProgress: Int
        get() = seenWasteTopIds.size

    fun reset() {
        seenWasteTopIds.clear()
        isStuck = false
    }

    /**
     * Observe a confirmed previous → current board pair. Returns a short
     * reason when something changes (for analysis.log); null if unchanged.
     */
    fun onConfirmedTransition(previous: GameState?, current: GameState): String? {
        if (previous == null || previous == current) return null

        if (wastePlayedToBoard(previous, current)) {
            val hadProgressToReset = seenWasteTopIds.isNotEmpty() || isStuck
            val wasStuck = isStuck
            seenWasteTopIds.clear()
            isStuck = false
            // The card now exposed under the one that just left is the first
            // card of the new lap - track it immediately so a future repeat
            // of *this* card is still caught, not just cards drawn after it.
            current.wasteTop()?.let { top -> if (top.known) seenWasteTopIds.add(top.id) }
            if (!hadProgressToReset) return null
            return if (wasStuck) "waste-play-reset(was-stuck)" else "waste-play-reset"
        }

        val wasteTop = current.wasteTop() ?: return null
        if (!wasteTop.known) return null
        // Only a genuinely new front card counts as "seeing" it - a repeat
        // detected line-to-line off the identical unchanged card would be
        // meaningless (and the recognizer can re-emit the same GameState
        // multiple times before something moves).
        if (previous.wasteTop()?.id == wasteTop.id) return null

        if (!seenWasteTopIds.add(wasteTop.id)) {
            if (isStuck) return null
            isStuck = true
            return "stuck-repeat=${wasteTop.id} seen=${seenWasteTopIds.size}"
        }
        return null
    }

    companion object {
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
    }
}
