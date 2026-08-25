package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.GameState

object ErrorCapturePolicy {
    data class CaptureDecision(
        val violations: List<RecognitionViolation>,
        val violationSource: String
    )

    /**
     * Prefer post-[DeckConstraintPass] violations. When [captureRawReadErrors] is
     * enabled, also capture stable boards whose raw reads still had violations
     * even though deck-constraint cleaned the final state.
     */
    fun decide(
        finalState: GameState,
        preConstraintState: GameState?,
        captureRawReadErrors: Boolean
    ): CaptureDecision? {
        val finalViolations = BoardRecognitionValidator.validate(finalState)
        if (finalViolations.isNotEmpty()) {
            return CaptureDecision(finalViolations, VIOLATION_SOURCE_FINAL)
        }
        if (!captureRawReadErrors || preConstraintState == null) return null
        val rawViolations = BoardRecognitionValidator.validate(preConstraintState)
        if (rawViolations.isEmpty()) return null
        return CaptureDecision(rawViolations, VIOLATION_SOURCE_RAW)
    }

    const val VIOLATION_SOURCE_FINAL = "final"
    const val VIOLATION_SOURCE_RAW = "raw"
}
