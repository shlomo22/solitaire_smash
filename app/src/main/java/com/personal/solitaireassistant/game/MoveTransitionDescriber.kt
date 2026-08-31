package com.personal.solitaireassistant.game

/**
 * Turns two consecutive confirmed [GameState] snapshots into a short,
 * human-readable line describing what changed between them - e.g.
 * "draw: Six_Clubs" or "Five_Clubs: tableau2 -> tableau5".
 *
 * This is inference over already-recognized pile contents, not new vision:
 * every card identity it reports came from the same per-frame recognition
 * that produced the two [GameState]s, so a misread card produces a
 * misdescribed move rather than a corrected one. The goal is a chronological
 * summary that's faster to review than opening every snapshot pair by hand,
 * not a source of truth independent of the underlying recognition.
 *
 * Known, permanent blind spots (not fixable by better diffing):
 * - Only the top card of stock/waste/each foundation pile is ever visible to
 *   the recognizer, so a card buried under 2+ others that get added and
 *   removed between two confirmed frames leaves no trace here.
 * - A multi-card tableau run move only reports the new top card and how many
 *   cards the destination column gained - it does not attempt to name which
 *   source column an ambiguous multi-card run came from.
 */
object MoveTransitionDescriber {
    fun describe(previous: GameState?, current: GameState): String {
        if (previous == null) return "deal: opening layout"
        if (previous == current) return "no change"

        val destinationEvents = mutableListOf<String>()
        val sourceEvents = mutableListOf<String>()
        val explainedAsDestination = mutableSetOf<String>()

        // Phase 1: every event that *gains* a card - foundation adds and
        // tableau growths - runs first and populates explainedAsDestination,
        // so phase 2 (tableau shrinks, waste changes) can tell a resolved
        // move's source side from a genuinely untraceable loss and skip the
        // former instead of double-reporting the same card moving.

        for (i in 0 until 4) {
            val prevTop = previous.foundationTop(i)
            val curTop = current.foundationTop(i)
            if (curTop == null || curTop == prevTop) continue
            if (!looksLikeFreshFoundationCard(prevTop, curTop)) continue
            val source = findSource(previous, curTop, excludeFoundation = i)
            destinationEvents += "${curTop.id}: $source -> foundation$i"
            explainedAsDestination += curTop.id
        }

        // Every column is classified exactly once, by size comparison, so
        // phase 1 (growth) and phase 2 (everything else) never both touch
        // the same column - that would risk phase 2 re-describing a column
        // phase 1 already explained, in either order.
        for (col in previous.tableau.indices) {
            val prevCol = previous.tableau[col]
            val curCol = current.tableau[col]
            if (prevCol == curCol) continue
            if (curCol.size <= prevCol.size) continue

            if (startsWith(curCol, prevCol)) {
                // Pure growth: prevCol is an unchanged prefix of curCol.
                val added = curCol.subList(prevCol.size, curCol.size)
                val newTop = added.last()
                if (newTop.known && newTop.id !in explainedAsDestination) {
                    val source = findSource(previous, newTop, excludeTableau = col)
                    destinationEvents += if (added.size == 1) {
                        "${newTop.id}: $source -> tableau$col"
                    } else {
                        "${newTop.id} (+${added.size - 1} more): $source -> tableau$col"
                    }
                    explainedAsDestination += newTop.id
                } else {
                    destinationEvents += "tableau$col grew by ${added.size} card(s)"
                }
            } else {
                // Grew, but not as a clean prefix-extension (e.g. a run got
                // reshuffled underneath a reveal) - don't guess at a card.
                destinationEvents += "tableau$col changed"
            }
        }

        // Phase 2: reveals (self-contained, no source/destination pairing)
        // and losses - shrinks and waste changes - checked against the now-
        // complete explainedAsDestination set from phase 1. Only columns
        // phase 1 didn't already claim (same size or shrank) land here.

        for (col in previous.tableau.indices) {
            val prevCol = previous.tableau[col]
            val curCol = current.tableau[col]
            if (prevCol == curCol) continue
            if (curCol.size > prevCol.size) continue

            if (curCol.size < prevCol.size && startsWith(prevCol, curCol)) {
                // Pure shrink: curCol is an unchanged prefix of prevCol - only
                // log it if nothing in phase 1 already claimed where the top
                // card(s) went (this then mainly catches losses whose
                // destination we couldn't resolve, e.g. a covered fan card).
                val removedTop = prevCol.last()
                if (removedTop.id !in explainedAsDestination) {
                    sourceEvents += "tableau$col lost ${prevCol.size - curCol.size} card(s) (was top ${removedTop.id})"
                }
            } else if (curCol.size == prevCol.size) {
                // Same length: look for a face-down -> face-up reveal at the
                // same trailing position, distinct from a genuine mis-read
                // flip-flop (which this can't and shouldn't try to filter).
                val revealIdx = prevCol.indices.firstOrNull { i ->
                    !prevCol[i].faceUp && curCol[i].faceUp && curCol[i].known
                }
                sourceEvents += if (revealIdx != null) {
                    "reveal: tableau$col -> ${curCol[revealIdx].id}"
                } else {
                    "tableau$col changed"
                }
            } else {
                // Shrank, but not as a clean prefix-truncation.
                sourceEvents += "tableau$col changed"
            }
        }

        // Waste change not already explained as the source of a foundation
        // or tableau gain above: most commonly a stock draw exposing a new
        // card, occasionally a waste card played somewhere we couldn't trace.
        val prevWaste = previous.wasteTop()
        val curWaste = current.wasteTop()
        if (curWaste != prevWaste) {
            if (curWaste != null && curWaste.known && curWaste.id !in explainedAsDestination) {
                sourceEvents += "draw: ${curWaste.id}"
            } else if (curWaste == null && prevWaste != null && prevWaste.id !in explainedAsDestination) {
                sourceEvents += "waste emptied (was ${prevWaste.id})"
            }
        }

        val events = destinationEvents + sourceEvents
        if (events.isEmpty()) return "state changed (see snapshot)"
        return events.joinToString("; ")
    }

    private fun looksLikeFreshFoundationCard(prevTop: Card?, curTop: Card): Boolean {
        if (!curTop.known || curTop.suitAmbiguous) return false
        if (prevTop == null) return curTop.rank == Rank.Ace
        return prevTop.known && curTop.suit == prevTop.suit && prevTop.rank.isOneBelow(curTop.rank)
    }

    /** Looks for [card] as the previous top of waste or a tableau column. */
    private fun findSource(
        previous: GameState,
        card: Card,
        excludeFoundation: Int? = null,
        excludeTableau: Int? = null
    ): String {
        if (previous.wasteTop()?.id == card.id) return "waste"
        previous.tableau.forEachIndexed { i, col ->
            if (i == excludeTableau) return@forEachIndexed
            if (col.lastOrNull()?.id == card.id) return "tableau$i"
        }
        previous.foundations.forEachIndexed { i, pile ->
            if (i == excludeFoundation) return@forEachIndexed
            if (pile.lastOrNull()?.id == card.id) return "foundation$i"
        }
        return "?"
    }

    private fun startsWith(longer: List<Card>, prefix: List<Card>): Boolean {
        if (prefix.size > longer.size) return false
        for (i in prefix.indices) {
            if (longer[i] != prefix[i]) return false
        }
        return true
    }
}
