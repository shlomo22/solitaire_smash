package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.PileRef

data class SuspiciousSlotHint(
    val locationKey: String,
    val reason: String
)

fun RecognizedSlot.locationKey(): String = "${pileRefKey(pile)}:$index"

object ErrorCaptureReviewHints {
    fun fromViolations(violations: List<RecognitionViolation>): List<SuspiciousSlotHint> {
        val hints = linkedMapOf<String, String>()
        violations.forEach { violation ->
            when (violation) {
                is RecognitionViolation.DuplicateCard -> {
                    violation.locations.forEach { location ->
                        hints.putIfAbsent(location, "duplicate ${violation.cardId}")
                    }
                }
                is RecognitionViolation.CascadeBreak -> {
                    val lowerKey = "${violation.pile}:${violation.lowerIndex}"
                    val upperKey = "${violation.pile}:${violation.upperIndex}"
                    val reason = "cascade ${violation.lowerCard}→${violation.upperCard}"
                    hints.putIfAbsent(lowerKey, reason)
                    hints.putIfAbsent(upperKey, reason)
                }
            }
        }
        return hints.map { (key, reason) -> SuspiciousSlotHint(key, reason) }
    }

    fun hintFor(slot: RecognizedSlot, hints: List<SuspiciousSlotHint>): SuspiciousSlotHint? =
        hints.firstOrNull { it.locationKey == slot.locationKey() }

    fun locationKeys(hints: List<SuspiciousSlotHint>): Set<String> =
        hints.map { it.locationKey }.toSet()
}
