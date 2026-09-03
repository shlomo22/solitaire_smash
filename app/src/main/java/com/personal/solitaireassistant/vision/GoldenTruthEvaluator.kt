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
    /**
     * Suit errors where the *color* is wrong, not just Clubs-vs-Spades or
     * Hearts-vs-Diamonds. Only these can produce an illegal tableau arrow:
     * [com.personal.solitaireassistant.game.Card.canStackOnTableau] compares
     * `suit.oppositeColor(...)`, so a same-color suit error leaves every
     * tableau move legal and can only misdirect a foundation move.
     */
    val crossColorSuitErrors: Int = 0,
    /**
     * [crossColorSuitErrors] split by pile, cascade depth, whether the rank
     * was also wrong, and whether the suit was flagged ambiguous — enough to
     * tell a genuine color-scoring miss apart from a shifted cascade slot
     * whose neighbour stole the truth match.
     */
    val crossColorBuckets: List<Pair<String, Int>> = emptyList(),
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
        if (crossColorSuitErrors > 0) {
            lines += "Cross-color suit errors: $crossColorSuitErrors of $suitErrors (illegal tableau arrows)"
            crossColorBuckets.forEach { (bucket, count) ->
                lines += "  $bucket ($count)"
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
        val crossColorMap = linkedMapOf<String, Int>()
        val mismatches = mutableListOf<String>()
        val mismatchDiagnostics = mutableListOf<String>()
        val samples = store.listSamples()

        samples.forEach { sample ->
            val bitmap = store.loadBitmap(sample.id) ?: return@forEach
            try {
                // Samples are independent snapshots, not consecutive frames of one
                // game - detector.detect() otherwise reuses recognizeCached's
                // frame-to-frame slotHitCache across them, which is correct for
                // the live capture pipeline (same board, unchanged regions) but
                // wrong here: a coincidental fingerprint+bounds match against an
                // unrelated earlier sample would silently serve that sample's
                // stale hit instead of genuinely recognizing this one.
                detector.clearSlotCache()
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
                    onCrossColorSuitConfusion = { key ->
                        crossColorMap[key] = (crossColorMap[key] ?: 0) + 1
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
            crossColorMap = crossColorMap,
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
        val crossColorMap = linkedMapOf<String, Int>()
        val mismatches = mutableListOf<String>()
        val mismatchDiagnostics = mutableListOf<String>()
        samples.forEach { (sample, bitmap) ->
            // See the matching comment in evaluate(store, detector) above: these
            // are independent snapshots, not consecutive frames of one game.
            detector.clearSlotCache()
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
                onCrossColorSuitConfusion = { key ->
                    crossColorMap[key] = (crossColorMap[key] ?: 0) + 1
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
            crossColorMap = crossColorMap,
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
        crossColorMap: Map<String, Int>,
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
        val crossColorBuckets = crossColorMap.entries
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
            crossColorSuitErrors = crossColorBuckets.sumOf { it.second },
            crossColorBuckets = crossColorBuckets,
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
        onCrossColorSuitConfusion: (String) -> Unit = {},
        onMismatch: (String) -> Unit = {},
        onMismatchDiagnostic: (String) -> Unit = {}
    ) {
        val detection = detector.detect(bitmap)
        val labeled = sample.slots.filter { !it.inferred }
        // Deepest labeled slot per pile, so a tableau miss can be attributed to
        // the exposed bottom card (its own direct read) or to a covered cascade
        // card (where geometric inference and slot positioning also apply).
        val deepestIndex = labeled.groupBy { it.pile }
            .mapValues { (_, slots) -> slots.maxOf { it.index } }
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
                        allDetected = detection.recognizedSlots,
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
                        allDetected = detection.recognizedSlots,
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
            var rankWrong = false
            if (expected.rank != null && actual.rank != expected.rank) {
                ok = false
                rankWrong = true
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
                if (actual.suit != null && actual.suit!!.isRed != expected.suit!!.isRed) {
                    onCrossColorSuitConfusion(
                        crossColorBucketKey(
                            truthSlot = truthSlot,
                            actual = actual,
                            rankWrong = rankWrong,
                            deepestIndex = deepestIndex[truthSlot.pile]
                        )
                    )
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
                        allDetected = detection.recognizedSlots,
                        bitmap = bitmap,
                        detector = detector
                    )
                )
            }
        }
    }

    /**
     * `tableau-covered rank-also-wrong flagged` and friends. A bucket dominated
     * by `rank-also-wrong` points at cascade slot positioning (the neighbour
     * stole the truth match) rather than at colour scoring, so the two failure
     * modes don't get fixed with the same lever.
     */
    internal fun crossColorBucketKey(
        truthSlot: GoldenSlot,
        actual: SlotGuess,
        rankWrong: Boolean,
        deepestIndex: Int?
    ): String {
        val pileClass = when {
            truthSlot.pile == "waste" -> "waste"
            truthSlot.pile.startsWith("foundation") -> "foundation"
            truthSlot.pile.startsWith("tableau") ->
                if (deepestIndex != null && truthSlot.index == deepestIndex) {
                    "tableau-exposed"
                } else {
                    "tableau-covered"
                }
            else -> truthSlot.pile
        }
        val rankClass = if (rankWrong) "rank-also-wrong" else "rank-ok"
        val ambiguityClass = if (actual.suitAmbiguous) "flagged" else "unflagged"
        return "$pileClass $rankClass $ambiguityClass"
    }

    internal fun buildMismatchDiagnostic(
        sampleId: String,
        truthSlot: GoldenSlot,
        detected: RecognizedSlot?,
        allDetected: List<RecognizedSlot> = emptyList(),
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
            parts += formatSlotTraceParts(detected)
        }
        // A detected slot the evaluator excludes as unreliable (inferred=true,
        // see findMatchingSlot) is invisible above: a MISSING truth slot shows
        // nothing about what the real recognizer saw at that position, and an
        // occupancy/rank/suit mismatch shows only the wrong neighboring slot
        // it fell back to instead. Surface the nearest excluded candidate's
        // own trace too, so a rejected-but-real attempt (low confidence,
        // unresolved rank, ambiguous suit) can be told apart from a position
        // with no attempt at all. Purely diagnostic - findMatchingSlot still
        // ignores inferred slots for scoring, so this cannot change results.
        findNearestInferredSlot(allDetected, truthSlot)?.let { inferredSlot ->
            parts += "inferred=[${formatSlotTraceParts(inferredSlot).joinToString(" | ")}]"
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

    private fun formatSlotTraceParts(slot: RecognizedSlot): List<String> {
        val rankPart = buildString {
            append("rank=")
            append(slot.trace.rankSource ?: "?")
            slot.trace.rankScore?.let { append("@${"%.2f".format(it)}") }
            slot.trace.rankTemplates?.let { append(" {$it}") }
        }
        val suitPart = buildString {
            append("suit=")
            append(slot.trace.suitSource ?: "?")
            slot.trace.suitScore?.let { append("@${"%.2f".format(it)}") }
            slot.trace.suitTemplates?.let { append(" {$it}") }
        }
        val parts = mutableListOf(rankPart, suitPart)
        if (slot.trace.postSteps.isNotEmpty()) {
            parts += "post=[${slot.trace.postSteps.joinToString("; ")}]"
        }
        parts += "diag=${slot.diagnostic}"
        return parts
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

    private fun findNearestInferredSlot(
        detected: List<RecognizedSlot>,
        truth: GoldenSlot
    ): RecognizedSlot? {
        val pile = runCatching { parsePileRefKey(truth.pile) }.getOrNull() ?: return null
        val cx = truth.bounds.centerX
        val cy = truth.bounds.centerY
        return detected
            .filter { it.pile == pile && it.inferred }
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
