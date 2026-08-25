package com.personal.solitaireassistant.vision

import android.content.Context
import android.graphics.Bitmap

data class ErrorCaptureEvalReport(
    val sampleCount: Int,
    val cleanCount: Int,
    val fixedCount: Int,
    val stillBroken: List<String>,
    val fixedSamples: List<String>,
    val unreadableSampleIds: List<String> = emptyList()
) {
    fun summary(): String {
        if (sampleCount == 0) return "No error captures saved yet."
        val lines = mutableListOf(
            "Error captures: $sampleCount samples",
            "Validator clean: $cleanCount/$sampleCount",
            "Fixed since capture: $fixedCount"
        )
        if (unreadableSampleIds.isNotEmpty()) {
            lines += "SKIPPED ${unreadableSampleIds.size} unreadable sample(s):"
            unreadableSampleIds.forEach { lines += "  $it" }
        }
        if (fixedSamples.isNotEmpty()) {
            lines += "Now clean:"
            fixedSamples.take(12).forEach { lines += "  $it" }
        }
        if (stillBroken.isNotEmpty()) {
            lines += "Still broken:"
            stillBroken.take(12).forEach { lines += "  $it" }
        }
        return lines.joinToString("\n")
    }

    fun detailBlock(): String {
        if (stillBroken.isEmpty() && fixedSamples.isEmpty()) return ""
        return buildString {
            if (fixedSamples.isNotEmpty()) {
                appendLine("Fixed captures (${fixedSamples.size}):")
                fixedSamples.forEach { appendLine("  $it") }
            }
            if (stillBroken.isNotEmpty()) {
                appendLine("Broken captures (${stillBroken.size}):")
                stillBroken.forEach { appendLine("  $it") }
            }
        }.trimEnd()
    }
}

object ErrorCaptureEvaluator {
    fun evaluate(
        context: Context,
        store: ErrorCaptureStore = ErrorCaptureStore(context)
    ): ErrorCaptureEvalReport {
        val detector = GameStateDetector(context)
        try {
            return evaluate(store, detector)
        } finally {
            detector.release()
        }
    }

    fun evaluate(
        store: ErrorCaptureStore,
        detector: GameStateDetector
    ): ErrorCaptureEvalReport {
        val stillBroken = mutableListOf<String>()
        val fixedSamples = mutableListOf<String>()
        var cleanCount = 0
        var fixedCount = 0
        var sampleCount = 0
        val unreadable = mutableListOf<String>()

        store.listIds().forEach { id ->
            val jsonText = store.loadJsonText(id)
            if (jsonText == null) {
                unreadable += id
                return@forEach
            }
            val sample = runCatching { GoldenTruthJson.fromJson(jsonText) }.getOrNull()
            if (sample == null) {
                unreadable += id
                return@forEach
            }
            val bitmap = store.loadBitmap(id)
            if (bitmap == null) {
                unreadable += id
                return@forEach
            }
            sampleCount++
            val storedMeta = GoldenTruthJson.parseErrorCaptureMeta(jsonText)
            val hadViolations = storedMeta?.violations?.isNotEmpty() == true
            try {
                detector.clearSlotCache()
                val detection = detector.detect(bitmap)
                val freshViolations = detection.state?.let { BoardRecognitionValidator.validate(it) }
                    .orEmpty()
                if (freshViolations.isEmpty()) {
                    cleanCount++
                    if (hadViolations) {
                        fixedCount++
                        fixedSamples += id
                    }
                } else {
                    val violationText = freshViolations.joinToString(", ") { it.summary() }
                    stillBroken += "$id: $violationText"
                }
            } finally {
                bitmap.recycle()
            }
        }

        return ErrorCaptureEvalReport(
            sampleCount = sampleCount,
            cleanCount = cleanCount,
            fixedCount = fixedCount,
            stillBroken = stillBroken,
            fixedSamples = fixedSamples,
            unreadableSampleIds = unreadable
        )
    }
}

fun GoldenSlot.toRecognizedSlot(): RecognizedSlot = RecognizedSlot(
    pile = parsePileRefKey(pile),
    index = index,
    bounds = bounds,
    engine = engine,
    confidence = confidence ?: 0f,
    diagnostic = diagnostic.orEmpty(),
    trace = trace ?: RecognitionTrace.EMPTY,
    inferred = inferred
)
