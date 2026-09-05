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
 * once it has been the waste top across [CONFIRMATION_THRESHOLD] separate
 * confirmed transitions in a row - not on its first sighting, and (v1.4.136)
 * not on its second either. v1.4.132 originally trusted on first sighting;
 * v1.4.135 raised that to two, using this exact real device evidence
 * (ed5ca730-analysis.log): a single weak OCR-only read
 * (`legacy=null,tight=Four_Diamonds@0.62`) got recorded, then the same
 * physical card immediately re-stabilized to a different, stronger fused
 * read (`Eight_Diamonds`) - a plain one-frame recognition hiccup, not a
 * second card.
 *
 * v1.4.135's two-observation bar still false-positived on the very next
 * real pull (b7d58390-analysis.log, user report: "the stop sign appeared
 * early when there was still plenty of moves to do", firing within seconds
 * of a genuine waste-to-tableau play). Root cause, traced to the exact
 * frame: the waste-rank scorer returned a flat, ambiguous distribution that
 * frame (`waste-rank-scores` spread 0.34-0.49 across nearly every rank -
 * `Four=0.337` wasn't even the top score, `Five=0.485` was - a bare
 * single-frame OCR read of `'4'@0.62` overrode the template scores anyway),
 * and "Four_Diamonds" stabilized for exactly two confirmed frames - enough
 * to clear the old bar and get recorded. Only a few seconds and one
 * intervening card later, a *different* physical card drawn from the same
 * weak region also stabilized as "Four_Diamonds" for two straight confirmed
 * frames before the recognizer moved on again - colliding with the first
 * and firing a false stuck signal despite the board having plenty of legal
 * moves. Two consecutive identical reads is not a strong enough bar when
 * the underlying per-rank score distribution is this flat and unstable;
 * three is a meaningfully harder coincidence to clear by accident on two
 * *different* misread cards, while a genuinely static, correctly-read card
 * still reconfirms for free from ordinary board jitter (see the same real
 * logs — tableau reads reprocess every few hundred ms even on an otherwise
 * static board), so a truly stuck board still clears this bar quickly.
 *
 * Deliberately does **not** short-circuit on "waste unchanged from
 * `previous.wasteTop()`" the way the first version of this file did - that
 * check is exactly what would prevent a genuinely stable, correctly-read
 * card from ever reaching its own confirmations (every later call showing
 * the same value would also see it already reflected in `previous`, since
 * the caller updates its own "last confirmed" pointer after every call, so
 * the early-return would fire every single time and [seenWasteTopIds] would
 * never accumulate anything at all). Confirmation is tracked purely via
 * [pendingCandidateId]/[pendingObservationCount], independent of whatever
 * `previous` happens to hold.
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
    private var pendingObservationCount = 0

    var isStuck: Boolean = false
        private set

    /** How many distinct *confirmed* waste-top cards have been seen since the last card left waste - for analysis.log. */
    val seenSinceProgress: Int
        get() = seenWasteTopIds.size

    fun reset() {
        seenWasteTopIds.clear()
        pendingCandidateId = null
        pendingObservationCount = 0
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
            // drawn after it. Still needs its own full confirmation like any
            // other candidate (see class doc for why v1.4.132's "track it
            // immediately" version of this line was unsafe).
            pendingCandidateId = null
            pendingObservationCount = 0
            current.wasteTop()?.let { top ->
                if (top.known) {
                    pendingCandidateId = top.id
                    pendingObservationCount = 1
                }
            }
            if (!hadProgressToReset) return null
            return if (wasStuck) "waste-play-reset(was-stuck)" else "waste-play-reset"
        }

        val wasteTop = current.wasteTop()
        if (wasteTop == null) {
            // Waste is genuinely empty (mid-recycle, or nothing drawn yet) -
            // a real, meaningful gap, unlike an unreadable-but-present card
            // below. Whatever was pending must earn its confirmation again
            // once something reappears, rather than silently reusing
            // confirmation progress left over from before the gap - that
            // would skip (part of) the repeat-check on exactly the
            // reappearing card this mechanism exists to catch.
            pendingCandidateId = null
            pendingObservationCount = 0
            return null
        }
        if (!wasteTop.known) return null // present but unreadable - leave any pending confirmation progress alone

        if (wasteTop.id == pendingCandidateId) {
            if (pendingObservationCount >= CONFIRMATION_THRESHOLD) return null // already accounted for
            pendingObservationCount++
            if (pendingObservationCount < CONFIRMATION_THRESHOLD) return null // needs more consecutive agreement first
            if (!seenWasteTopIds.add(wasteTop.id)) {
                if (isStuck) return null
                isStuck = true
                return "stuck-repeat=${wasteTop.id} seen=${seenWasteTopIds.size}"
            }
            return null
        }

        // A different identity than whatever was pending. A one-frame (or
        // two-frame, see class doc) OCR hiccup or deck-constraint
        // coincidence gets silently discarded right here, the moment
        // something else replaces it, having never reached full
        // confirmation or been recorded at all.
        pendingCandidateId = wasteTop.id
        pendingObservationCount = 1
        return null
    }

    companion object {
        /**
         * Consecutive confirmed sightings of the same waste-top identity
         * required before it's trusted enough to record (and, on a repeat,
         * to flag stuck). See the class doc for the real device evidence
         * behind raising this from 2 (v1.4.135) to 3 (v1.4.136).
         */
        const val CONFIRMATION_THRESHOLD = 3

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
