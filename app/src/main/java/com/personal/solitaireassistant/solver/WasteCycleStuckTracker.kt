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
 *
 * A candidate identity is only trusted (recorded into [seenWasteTopIds])
 * once it has been the waste top across *two separate* confirmed
 * transitions - never on its first sighting. Real device evidence
 * (ed5ca730-analysis.log, v1.4.132): a single weak OCR-only read
 * (`legacy=null,tight=Four_Diamonds@0.62`) got recorded, then the same
 * physical card immediately re-stabilized to a different, stronger fused
 * read (`Eight_Diamonds`) - a plain one-frame recognition hiccup, not a
 * second card. Separately, `DeckConstraintPass` coincidentally forced two
 * *different* real draws to the same final identity purely from persistent
 * Clubs/Spades ambiguity (`deck-constraint:2C->2S` on one draw, then a later
 * draw independently landing on `Two_Spades` too) - each of those bad reads
 * only ever appeared once before the board moved on to a genuinely new card,
 * so neither would have survived a second-observation requirement either.
 * Both false positives left `isStuck` stuck true (only a real waste play
 * clears it) for the rest of the game, well past the point new cards kept
 * being drawn - exactly the "stop sign shown while many valid moves exist"
 * symptom this fixes. A genuinely stuck board trivially clears this bar:
 * nothing is changing, so the repeated card gets reconfirmed by whatever
 * unrelated board jitter keeps generating confirmed transitions anyway (see
 * the same real log - tableau reads reprocess every few hundred ms even on
 * an otherwise static board).
 *
 * Deliberately does **not** short-circuit on "waste unchanged from
 * `previous.wasteTop()`" the way the first version of this file did - that
 * check is exactly what would prevent a genuinely stable, correctly-read
 * card from ever reaching its own second confirmation (every later call
 * showing the same value would also see it already reflected in `previous`,
 * since the caller updates its own "last confirmed" pointer after every
 * call, so the early-return would fire every single time and
 * [seenWasteTopIds] would never accumulate anything at all). Confirmation
 * is tracked purely via [pendingCandidateId]/[pendingConfirmed], independent
 * of whatever `previous` happens to hold.
 *
 * A genuinely empty waste (mid-recycle) clears any pending confirmation
 * rather than leaving it in place - otherwise a card confirmed just before
 * a recycle would still read as "already confirmed" the instant it
 * reappears after coming back around, skipping the repeat-check entirely on
 * exactly the reappearance this whole mechanism exists to catch. A present
 * but unreadable card (`known == false`) is different - that's noise on a
 * card that's still physically there, not a real gap, so it leaves whatever
 * was pending untouched.
 */
class WasteCycleStuckTracker {
    private val seenWasteTopIds = mutableSetOf<String>()
    private var pendingCandidateId: String? = null
    private var pendingConfirmed = false

    var isStuck: Boolean = false
        private set

    /** How many distinct *confirmed* waste-top cards have been seen since the last card left waste - for analysis.log. */
    val seenSinceProgress: Int
        get() = seenWasteTopIds.size

    fun reset() {
        seenWasteTopIds.clear()
        pendingCandidateId = null
        pendingConfirmed = false
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
            // card of the new lap - seed it as the pending candidate so a
            // future repeat of *this* card is still caught, not just cards
            // drawn after it. Still needs its own second confirmation like
            // any other candidate (see class doc for why v1.4.132's
            // "track it immediately" version of this line was unsafe).
            pendingCandidateId = null
            pendingConfirmed = false
            current.wasteTop()?.let { top -> if (top.known) pendingCandidateId = top.id }
            if (!hadProgressToReset) return null
            return if (wasStuck) "waste-play-reset(was-stuck)" else "waste-play-reset"
        }

        val wasteTop = current.wasteTop()
        if (wasteTop == null) {
            // Waste is genuinely empty (mid-recycle, or nothing drawn yet) -
            // a real, meaningful gap, unlike an unreadable-but-present card
            // below. Whatever was pending must earn its confirmation again
            // once something reappears, rather than silently reusing an
            // "already confirmed" flag left over from before the gap - that
            // would skip the repeat-check entirely on exactly the reappearing
            // card this mechanism exists to catch.
            pendingCandidateId = null
            pendingConfirmed = false
            return null
        }
        if (!wasteTop.known) return null // present but unreadable - leave any pending confirmation progress alone

        if (wasteTop.id == pendingCandidateId) {
            if (pendingConfirmed) return null // already accounted for on an earlier call
            pendingConfirmed = true
            if (!seenWasteTopIds.add(wasteTop.id)) {
                if (isStuck) return null
                isStuck = true
                return "stuck-repeat=${wasteTop.id} seen=${seenWasteTopIds.size}"
            }
            return null
        }

        // A different identity than whatever was pending. A one-frame OCR
        // hiccup or deck-constraint coincidence (see class doc) gets
        // silently discarded right here, the moment something else replaces
        // it, having never been confirmed or recorded at all.
        pendingCandidateId = wasteTop.id
        pendingConfirmed = false
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
