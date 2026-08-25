package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit

data class ReviewSlotStatus(
    val stillBroken: SuspiciousSlotHint?,
    val resolved: Boolean
)

object GoldenReviewStateBuilder {
    fun gameState(slots: List<RecognizedSlot>, truths: List<SlotGuess>): GameState {
        require(slots.size == truths.size) { "slots/truths size mismatch" }
        val tableau = List(7) { column ->
            slots.withIndex()
                .filter { (_, slot) -> slot.pile == PileRef.Tableau(column) }
                .sortedBy { (_, slot) -> slot.index }
                .map { (index, slot) -> cardFromGuess(truths[index], slot.inferred) }
        }
        val foundations = List(4) { pile ->
            slots.withIndex()
                .filter { (_, slot) -> slot.pile == PileRef.Foundation(pile) }
                .sortedBy { (_, slot) -> slot.index }
                .map { (index, slot) -> cardFromGuess(truths[index], slot.inferred) }
        }
        val waste = slots.withIndex()
            .filter { (_, slot) -> slot.pile == PileRef.Waste }
            .sortedBy { (_, slot) -> slot.index }
            .map { (index, slot) -> cardFromGuess(truths[index], slot.inferred) }
        val stock = slots.withIndex()
            .filter { (_, slot) -> slot.pile == PileRef.Stock }
            .sortedBy { (_, slot) -> slot.index }
            .map { (index, slot) -> cardFromGuess(truths[index], slot.inferred) }
        return GameState(
            tableau = tableau,
            foundations = foundations,
            stock = stock,
            waste = waste
        )
    }

    fun reviewStatuses(
        slots: List<RecognizedSlot>,
        truths: List<SlotGuess>,
        originallyFlagged: Set<String>
    ): Map<String, ReviewSlotStatus> {
        val state = gameState(slots, truths)
        val currentHints = ErrorCaptureReviewHints.fromViolations(
            BoardRecognitionValidator.validate(state)
        )
        val currentByKey = currentHints.associateBy { it.locationKey }
        val statuses = linkedMapOf<String, ReviewSlotStatus>()
        originallyFlagged.forEach { key ->
            val hint = currentByKey[key]
            statuses[key] = if (hint != null) {
                ReviewSlotStatus(stillBroken = hint, resolved = false)
            } else {
                ReviewSlotStatus(stillBroken = null, resolved = true)
            }
        }
        currentByKey.forEach { (key, hint) ->
            if (key !in statuses) {
                statuses[key] = ReviewSlotStatus(stillBroken = hint, resolved = false)
            }
        }
        return statuses
    }

    private fun cardFromGuess(guess: SlotGuess, inferred: Boolean): Card = when (guess.kind) {
        SlotKind.Empty -> Card(
            rank = Rank.Ace,
            suit = Suit.Spades,
            faceUp = false,
            known = false
        )
        SlotKind.FaceDown -> Card(
            rank = Rank.Ace,
            suit = Suit.Spades,
            faceUp = false,
            known = false
        )
        SlotKind.Unknown -> Card(
            rank = guess.rank ?: Rank.Ace,
            suit = guess.suit ?: Suit.Spades,
            faceUp = true,
            known = false,
            suitAmbiguous = guess.suitAmbiguous,
            inferred = inferred
        )
        SlotKind.FaceUp -> Card(
            rank = guess.rank ?: Rank.Ace,
            suit = guess.suit ?: Suit.Spades,
            faceUp = true,
            known = true,
            suitAmbiguous = guess.suitAmbiguous,
            inferred = inferred
        )
    }
}
