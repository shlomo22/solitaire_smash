package com.personal.solitaireassistant.vision

import android.content.Context
import android.graphics.Bitmap
import com.personal.solitaireassistant.game.Suit
import kotlin.math.hypot

data class GoldenEvalReport(
    val sampleCount: Int,
    val slotCount: Int,
    val matchedSlots: Int,
    val rankErrors: Int,
    val suitErrors: Int,
    val occupancyErrors: Int,
    val missingSlots: Int,
    val confusions: List<Pair<String, Int>>,
    val redSuitConfusions: List<Pair<String, Int>>,
    val blackSuitConfusions: List<Pair<String, Int>>,
    val mismatches: List<String>,
    val mismatchDiagnostics: List<String> = emptyList(),
    /** Golden ids present on disk but unparseable, so not evaluated at all. */
    val unreadableSampleIds: List<String> = emptyList()
) {
    val accuracy: Float
        get() = if (slotCount == 0) 0f else matchedSlots.toFloat() / slotCount

    fun summary(): String {
        if (sampleCount == 0) return "No golden samples saved yet."
        val lines = mutableListOf(
            "Golden set: $sampleCount samples, $slotCount labeled slots",
            "Accuracy: ${"%.0f".format(accuracy * 100f)}% ($matchedSlots/$slotCount)",
            "Rank errors: $rankErrors  Suit errors: $suitErrors  Occupancy: $occupancyErrors  Missing: $missingSlots"
        )
        if (unreadableSampleIds.isNotEmpty()) {
            lines += "SKIPPED ${unreadableSampleIds.size} unreadable sample(s) - not evaluated:"
            unreadableSampleIds.forEach { lines += "  $it.json" }
        }
        if (confusions.isNotEmpty()) {
            lines += "Confusions:"
            confusions.take(8).forEach { (pair, count) ->
                lines += "  $pair ($count)"
            }
        }
        if (redSuitConfusions.isNotEmpty()) {
            lines += "Red suit confusions (H↔D):"
            redSuitConfusions.forEach { (pair, count) ->
                lines += "  $pair ($count)"
            }
        }
        if (blackSuitConfusions.isNotEmpty()) {
            lines += "Black suit confusions (C↔S):"
            blackSuitConfusions.forEach { (pair, count) ->
                lines += "  $pair ($count)"
            }
        }
        if (mismatches.isNotEmpty()) {
            lines += "Examples:"
            mismatches.take(12).forEach { lines += "  $it" }
        }
        return lines.joinToString("\n")
    }

    fun mismatchTraceBlock(): String {
        if (mismatchDiagnostics.isEmpty()) return ""
        return buildString {
            appendLine("Mismatch traces (${mismatchDiagnostics.size}):")
            mismatchDiagnostics.forEach { appendLine("  $it") }
        }.trimEnd()
    }
}

object GoldenTruthEvaluator {
    fun evaluate(
        context: Context,
        store: GoldenTruthStore = GoldenTruthStore(context)
    ): GoldenEvalReport {
        val detector = GameStateDetector(context)
        try {
            return evaluate(store, detector)
        } finally {
            detector.release()
        }
    }

    fun evaluate(
        store: GoldenTruthStore,
        detector: GameStateDetector
    ): GoldenEvalReport {
        var slotCount = 0
        var matched = 0
        var rankErrors = 0
        var suitErrors = 0
        var occupancyErrors = 0
        var missingSlots = 0
        val confusionMap = linkedMapOf<String, Int>()
        val redSuitConfusionMap = linkedMapOf<String, Int>()
        val blackSuitConfusionMap = linkedMapOf<String, Int>()
        val mismatches = mutableListOf<String>()
        val mismatchDiagnostics = mutableListOf<String>()
        val samples = store.listSamples()

        samples.forEach { sample ->
            val bitmap = store.loadBitmap(sample.id) ?: return@forEach
            try {
                compareSample(
                    sample = sample,
                    bitmap = bitmap,
                    detector = detector,
                    onSlot = { slotCount++ },
                    onMatch = { matched++ },
                    onRank = { rankErrors++ },
                    onSuit = { suitErrors++ },
                    onOccupancy = { occupancyErrors++ },
                    onMissing = { missingSlots++ },
                    onConfusion = { key ->
                        confusionMap[key] = (confusionMap[key] ?: 0) + 1
                    },
                    onRedSuitConfusion = { key ->
                        redSuitConfusionMap[key] = (redSuitConfusionMap[key] ?: 0) + 1
                    },
                    onBlackSuitConfusion = { key ->
                        blackSuitConfusionMap[key] = (blackSuitConfusionMap[key] ?: 0) + 1
                    },
                    onMismatch = { mismatches += it },
                    onMismatchDiagnostic = { mismatchDiagnostics += it }
                )
            } finally {
                bitmap.recycle()
            }
        }

        return finishReport(
            sampleCount = samples.size,
            slotCount = slotCount,
            matched = matched,
            rankErrors = rankErrors,
            suitErrors = suitErrors,
            occupancyErrors = occupancyErrors,
            missingSlots = missingSlots,
            confusionMap = confusionMap,
            redSuitConfusionMap = redSuitConfusionMap,
            blackSuitConfusionMap = blackSuitConfusionMap,
            mismatches = mismatches,
            mismatchDiagnostics = mismatchDiagnostics,
            unreadableSampleIds = store.listUnreadableIds()
        )
    }

    fun evaluateLoaded(
        samples: List<Pair<GoldenSample, Bitmap>>,
        detector: GameStateDetector
    ): GoldenEvalReport {
        var slotCount = 0
        var matched = 0
        var rankErrors = 0
        var suitErrors = 0
        var occupancyErrors = 0
        var missingSlots = 0
        val confusionMap = linkedMapOf<String, Int>()
        val redSuitConfusionMap = linkedMapOf<String, Int>()
        val blackSuitConfusionMap = linkedMapOf<String, Int>()
        val mismatches = mutableListOf<String>()
        val mismatchDiagnostics = mutableListOf<String>()
        samples.forEach { (sample, bitmap) ->
            compareSample(
                sample = sample,
                bitmap = bitmap,
                detector = detector,
                onSlot = { slotCount++ },
                onMatch = { matched++ },
                onRank = { rankErrors++ },
                onSuit = { suitErrors++ },
                onOccupancy = { occupancyErrors++ },
                onMissing = { missingSlots++ },
                onConfusion = { key ->
                    confusionMap[key] = (confusionMap[key] ?: 0) + 1
                },
                onRedSuitConfusion = { key ->
                    redSuitConfusionMap[key] = (redSuitConfusionMap[key] ?: 0) + 1
                },
                onBlackSuitConfusion = { key ->
                    blackSuitConfusionMap[key] = (blackSuitConfusionMap[key] ?: 0) + 1
                },
                onMismatch = { mismatches += it },
                onMismatchDiagnostic = { mismatchDiagnostics += it }
            )
        }
        return finishReport(
            sampleCount = samples.size,
            slotCount = slotCount,
            matched = matched,
            rankErrors = rankErrors,
            suitErrors = suitErrors,
            occupancyErrors = occupancyErrors,
            missingSlots = missingSlots,
            confusionMap = confusionMap,
            redSuitConfusionMap = redSuitConfusionMap,
            blackSuitConfusionMap = blackSuitConfusionMap,
            mismatches = mismatches,
            mismatchDiagnostics = mismatchDiagnostics
        )
    }

    private fun finishReport(
        sampleCount: Int,
        slotCount: Int,
        matched: Int,
        rankErrors: Int,
        suitErrors: Int,
        occupancyErrors: Int,
        missingSlots: Int,
        confusionMap: Map<String, Int>,
        redSuitConfusionMap: Map<String, Int>,
        blackSuitConfusionMap: Map<String, Int>,
        mismatches: List<String>,
        mismatchDiagnostics: List<String>,
        unreadableSampleIds: List<String> = emptyList()
    ): GoldenEvalReport {
        val confusions = confusionMap.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
        val redSuitConfusions = redSuitConfusionMap.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
        val blackSuitConfusions = blackSuitConfusionMap.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
        return GoldenEvalReport(
            sampleCount = sampleCount,
            slotCount = slotCount,
            matchedSlots = matched,
            rankErrors = rankErrors,
            suitErrors = suitErrors,
            occupancyErrors = occupancyErrors,
            missingSlots = missingSlots,
            confusions = confusions,
            redSuitConfusions = redSuitConfusions,
            blackSuitConfusions = blackSuitConfusions,
            mismatches = mismatches,
            mismatchDiagnostics = mismatchDiagnostics,
            unreadableSampleIds = unreadableSampleIds
        )
    }

    fun compareSample(
        sample: GoldenSample,
        bitmap: Bitmap,
        detector: GameStateDetector,
        onSlot: () -> Unit = {},
        onMatch: () -> Unit = {},
        onRank: () -> Unit = {},
        onSuit: () -> Unit = {},
        onOccupancy: () -> Unit = {},
        onMissing: () -> Unit = {},
        onConfusion: (String) -> Unit = {},
        onRedSuitConfusion: (String) -> Unit = {},
        onBlackSuitConfusion: (String) -> Unit = {},
        onMismatch: (String) -> Unit = {},
        onMismatchDiagnostic: (String) -> Unit = {}
    ) {
        val detection = detector.detect(bitmap)
        val labeled = sample.slots.filter { !it.inferred }
        labeled.forEach { truthSlot ->
            onSlot()
            val detected = findMatchingSlot(detection.recognizedSlots, truthSlot)
            if (detected == null) {
                onMissing()
                onMismatch("${sample.id} ${truthSlot.pile} missing (truth=${truthSlot.truth.shortLabel()})")
                onMismatchDiagnostic(
                    buildMismatchDiagnostic(
                        sampleId = sample.id,
                        truthSlot = truthSlot,
                        detected = null,
                        bitmap = bitmap,
                        detector = detector
                    )
                )
                return@forEach
            }
            val actual = detected.engine
            val expected = truthSlot.truth
            if (actual.kind != expected.kind) {
                onOccupancy()
                onConfusion("${expected.kind.name} → ${actual.kind.name}")
                onMismatch(
                    "${sample.id} ${truthSlot.pile} occupancy " +
                        "${expected.shortLabel()} vs ${actual.shortLabel()}"
                )
                onMismatchDiagnostic(
                    buildMismatchDiagnostic(
                        sampleId = sample.id,
                        truthSlot = truthSlot,
                        detected = detected,
                        bitmap = bitmap,
                        detector = detector
                    )
                )
                return@forEach
            }
            if (expected.kind != SlotKind.FaceUp) {
                onMatch()
                return@forEach
            }
            var ok = true
            if (expected.rank != null && actual.rank != expected.rank) {
                ok = false
                onRank()
                onConfusion("${expected.rank.name} → ${actual.rank?.name ?: "?"}")
            }
            if (expected.suit != null && actual.suit != expected.suit) {
                ok = false
                onSuit()
                val confusionKey = "${expected.suit.name} → ${actual.suit?.name ?: "?"}"
                onConfusion(confusionKey)
                if (expected.suit!!.isRed &&
                    actual.suit != null &&
                    (
                        (expected.suit == Suit.Hearts && actual.suit == Suit.Diamonds) ||
                            (expected.suit == Suit.Diamonds && actual.suit == Suit.Hearts)
                        )
                ) {
                    onRedSuitConfusion(confusionKey)
                }
                if (!expected.suit!!.isRed &&
                    actual.suit != null &&
                    (
                        (expected.suit == Suit.Clubs && actual.suit == Suit.Spades) ||
                            (expected.suit == Suit.Spades && actual.suit == Suit.Clubs)
                        )
                ) {
                    onBlackSuitConfusion(confusionKey)
                }
            }
            if (ok) {
                onMatch()
            } else {
                onMismatch(
                    "${sample.id} ${truthSlot.pile} " +
                        "${expected.shortLabel()} vs ${actual.shortLabel()}"
                )
                onMismatchDiagnostic(
                    buildMismatchDiagnostic(
                        sampleId = sample.id,
                        truthSlot = truthSlot,
                        detected = detected,
                        bitmap = bitmap,
                        detector = detector
                    )
                )
            }
        }
    }

    internal fun buildMismatchDiagnostic(
        sampleId: String,
        truthSlot: GoldenSlot,
        detected: RecognizedSlot?,
        bitmap: Bitmap,
        detector: GameStateDetector
    ): String {
        val expected = truthSlot.truth
        val actual = detected?.engine
        val label = when {
            detected == null -> "${expected.shortLabel()} vs MISSING"
            else -> "${expected.shortLabel()} vs ${actual?.shortLabel() ?: "?"}"
        }
        val parts = mutableListOf("$sampleId ${truthSlot.pile} $label")
        if (detected != null) {
            val rankPart = buildString {
                append("rank=")
                append(detected.trace.rankSource ?: "?")
                detected.trace.rankScore?.let { append("@${"%.2f".format(it)}") }
                detected.trace.rankTemplates?.let { append(" {$it}") }
            }
            parts += rankPart
            val suitPart = buildString {
                append("suit=")
                append(detected.trace.suitSource ?: "?")
                detected.trace.suitScore?.let { append("@${"%.2f".format(it)}") }
                detected.trace.suitTemplates?.let { append(" {$it}") }
            }
            parts += suitPart
            if (detected.trace.postSteps.isNotEmpty()) {
                parts += "post=[${detected.trace.postSteps.joinToString("; ")}]"
            }
            parts += "diag=${detected.diagnostic}"
        }
        if (expected.kind == SlotKind.FaceUp) {
            val probe = if (truthSlot.pile == "waste") {
                detector.attemptWasteRankOcr(bitmap, listOf(truthSlot.bounds))
            } else {
                detector.attemptCornerRankOcr(bitmap, truthSlot.bounds)
            }
            parts += "probe=${probe.trace}"
        }
        return parts.joinToString(" | ")
    }

    fun findMatchingSlot(
        detected: List<RecognizedSlot>,
        truth: GoldenSlot
    ): RecognizedSlot? {
        val pile = runCatching { parsePileRefKey(truth.pile) }.getOrNull() ?: return null
        val cx = truth.bounds.centerX
        val cy = truth.bounds.centerY
        return detected
            .filter { it.pile == pile && !it.inferred }
            .minByOrNull { hypot(it.bounds.centerX - cx, it.bounds.centerY - cy) }
            ?.takeIf { match ->
                hypot(
                    match.bounds.centerX - cx,
                    match.bounds.centerY - cy
                ) < MATCH_DISTANCE_PX
            }
    }

    private const val MATCH_DISTANCE_PX = 80f
}
