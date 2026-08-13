package com.personal.solitaireassistant.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.SuggestedMove
import com.personal.solitaireassistant.overlay.OverlayController
import com.personal.solitaireassistant.settings.AssistantSettings
import com.personal.solitaireassistant.settings.RejectedMoveStore
import com.personal.solitaireassistant.solver.MoveFingerprint
import com.personal.solitaireassistant.solver.MoveGenerator
import com.personal.solitaireassistant.solver.MoveSelector
import com.personal.solitaireassistant.vision.DetectionResult
import com.personal.solitaireassistant.vision.GameStateDetector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AnalysisPipeline(
    appContext: Context,
    private val overlayController: OverlayController,
    private val statusSink: (String) -> Unit
) {
    private val detector = GameStateDetector(appContext)
    private val fileLogger = AnalysisFileLogger(appContext)
    private val rejectedMoveStore = RejectedMoveStore(appContext)
    private val busy = AtomicBoolean(false)
    private val settingsRef = AtomicReference(AssistantSettings())
    private var stableHits = 0
    private var lastSignature: String? = null
    private var lastSuggestion: SuggestedMove? = null
    private var lastSuggestionSignature: String? = null
    private var lastLoggedOutcome: String? = null
    private var sessionStarted = false
    private val recentStates = ArrayDeque<GameState>()
    private var lastFrameFingerprint: Long? = null
    private var lastDetection: DetectionResult? = null
    private var lastStableState: GameState? = null
    private val sessionRejected = mutableSetOf<String>()
    private var templateLabMode = false
    private val _labSnapshot = MutableStateFlow<LabSnapshot?>(null)
    val labSnapshot: StateFlow<LabSnapshot?> = _labSnapshot.asStateFlow()

    val analysisLogPath: String get() = fileLogger.pathForDisplay()

    fun setTemplateLabMode(enabled: Boolean) {
        templateLabMode = enabled
        if (!enabled) {
            // Ownership of the emitted bitmap belongs to the collector (ViewModel),
            // which recycles it on the main thread. Recycling here races with reads.
            _labSnapshot.value = null
        } else {
            overlayController.hideArrowTemporarily()
            lastSuggestion = null
            lastSuggestionSignature = null
        }
    }

    fun reloadTemplates() {
        detector.reloadTemplates()
    }

    fun detector(): GameStateDetector = detector

    fun updateSettings(settings: AssistantSettings) {
        settingsRef.set(settings)
        detector.updateMinConfidence(settings.minMatchConfidence)
        detector.setIgnoreUserTemplates(settings.ignoreUserTemplates)
    }

    fun onFrame(bitmap: Bitmap, settings: AssistantSettings) {
        settingsRef.set(settings)
        if (!busy.compareAndSet(false, true)) {
            return
        }
        try {
            if (!sessionStarted) {
                sessionStarted = true
                fileLogger.sessionStart("${bitmap.width}x${bitmap.height}")
            }
            // Keep prior arrow while analyzing to avoid constant flash.

            val started = System.currentTimeMillis()
            val fingerprint = boardFingerprint(bitmap)
            val cached = fingerprint == lastFrameFingerprint
            val detection = if (cached) {
                lastDetection ?: detector.detect(bitmap)
            } else {
                detector.detect(bitmap)
            }
            if (!cached || lastDetection == null) {
                lastFrameFingerprint = fingerprint
                lastDetection = detection
            }
            val elapsed = System.currentTimeMillis() - started
            if (templateLabMode) {
                publishLabSnapshot(bitmap, detection)
                statusSink("Template Lab — frame captured")
                return
            }
            handleDetection(detection, elapsed, bitmap.width, bitmap.height)
        } catch (t: Throwable) {
            Log.e(TAG, "Analysis failed", t)
            fileLogger.append("ERROR ${t.javaClass.simpleName}: ${t.message}")
            statusSink("Analysis error: ${t.message}")
            overlayController.hideArrowTemporarily()
        } finally {
            busy.set(false)
        }
    }

    fun clear() {
        lastSignature = null
        stableHits = 0
        lastSuggestion = null
        lastSuggestionSignature = null
        lastLoggedOutcome = null
        sessionStarted = false
        recentStates.clear()
        sessionRejected.clear()
        lastFrameFingerprint = null
        lastDetection = null
        lastStableState = null
        _labSnapshot.value?.bitmap?.recycle()
        _labSnapshot.value = null
        overlayController.hideArrowTemporarily()
        fileLogger.append("=== pipeline cleared ===")
    }

    fun cancelCurrentHint() {
        val state = lastStableState ?: return
        val suggestion = lastSuggestion ?: return
        val fingerprint = MoveFingerprint.of(state, suggestion.scored.move)
        sessionRejected += fingerprint
        rejectedMoveStore.reject(fingerprint)
        fileLogger.append("REJECTED fingerprint=$fingerprint move=${suggestion.scored.move.label}")
        statusSink("Rejected hint — showing next move")
        val detection = lastDetection
        if (detection?.state != null && lastSignature != null) {
            refreshSuggestion(detection, lastSignature!!, knownFaceUp = countKnownFaceUp(detection.state))
        } else {
            overlayController.hideArrowTemporarily()
            lastSuggestion = null
            lastSuggestionSignature = null
        }
    }

    private fun boardFingerprint(bitmap: Bitmap): Long {
        val left = 0
        val right = bitmap.width
        val top = (bitmap.height * 0.20f).toInt()
        val bottom = (bitmap.height * 0.68f).toInt()
        val stepX = (bitmap.width / 54).coerceAtLeast(1)
        val stepY = ((bottom - top) / 60).coerceAtLeast(1)
        var hash = -0x340d631b7bdddcdbL
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = bitmap.getPixel(x, y)
                val quantized =
                    (((color shr 19) and 0x1F) shl 10) or
                        (((color shr 11) and 0x1F) shl 5) or
                        ((color shr 3) and 0x1F)
                hash = (hash xor quantized.toLong()) * 0x100000001b3L
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    private fun handleDetection(
        detection: DetectionResult,
        elapsedMs: Long,
        frameW: Int,
        frameH: Int
    ) {
        val state = detection.state
        if (state == null) {
            stableHits = 0
            lastSignature = null
            lastSuggestion = null
            lastSuggestionSignature = null
            overlayController.hideArrowTemporarily()
            val msg = "No board (${detection.diagnostics.lastOrNull()}) ${elapsedMs}ms"
            statusSink(msg)
            logOutcome(
                "NO_BOARD",
                msg,
                detection,
                elapsedMs,
                frameW,
                frameH,
                move = null,
                from = null,
                to = null,
                knownFaceUp = 0
            )
            return
        }

        val signature = buildString {
            append(state.waste.joinToString { it.id })
            append('|')
            append(state.foundations.joinToString(";") { pile ->
                pile.lastOrNull()?.id ?: "-"
            })
            append('|')
            append(state.tableau.joinToString(";") { col ->
                col.joinToString(",") { c ->
                    when {
                        !c.faceUp -> "D"
                        c.known -> c.id
                        else -> "U"
                    }
                }
            })
        }

        if (signature == lastSignature) {
            stableHits++
        } else {
            lastSignature = signature
            stableHits = 1
        }

        val knownFaceUp = countKnownFaceUp(state)
        val reliableFirstHit = detection.confidence >= 0.78f && knownFaceUp >= 4

        if (stableHits < 2 && !reliableFirstHit) {
            statusSink("Stabilizing detection… conf=${"%.2f".format(detection.confidence)}")
            lastSuggestion?.let { restore ->
                if (restore.from != null && restore.to != null) {
                    overlayController.showMove(restore.from, restore.to)
                }
            }
            return
        }

        lastStableState = state
        if (recentStates.lastOrNull() != state) {
            recentStates.addLast(state)
            while (recentStates.size > 4) recentStates.removeFirst()
        }
        refreshSuggestion(
            detection,
            signature,
            knownFaceUp,
            elapsedMs,
            frameW,
            frameH
        )
    }

    private fun refreshSuggestion(
        detection: DetectionResult,
        signature: String,
        knownFaceUp: Int,
        elapsedMs: Long = 0L,
        frameW: Int = 0,
        frameH: Int = 0
    ) {
        val state = detection.state ?: return
        if (!isDetectionReliableEnough(detection, knownFaceUp)) {
            overlayController.hideArrowTemporarily()
            lastSuggestion = null
            lastSuggestionSignature = null
            // Distinguish gate-blocked hints from genuine dead ends: report which
            // threshold failed and whether a legal move existed anyway.
            val legalCount = MoveGenerator.generate(state).size
            val gateReason = gateFailureReason(detection, knownFaceUp)
            val msg =
                "Low detection quality — hiding hint conf=${"%.2f".format(detection.confidence)} " +
                    "known=$knownFaceUp gate=$gateReason legalMoves=$legalCount"
            statusSink(msg)
            logOutcome(
                "LOW_QUALITY",
                msg,
                detection,
                elapsedMs,
                frameW,
                frameH,
                move = null,
                from = null,
                to = null,
                knownFaceUp = knownFaceUp
            )
            return
        }
        val avoidStates = recentStates.dropLast(1)
        val rejected = rejectedMoveStore.all() + sessionRejected
        val best = MoveSelector.bestMove(state, avoidStates, rejected)
        if (best == null) {
            overlayController.hideArrowTemporarily()
            lastSuggestion = null
            lastSuggestionSignature = null
            // Separate "board has zero legal moves" from "all legal moves were
            // filtered out by avoid-loop / rejected-move memory".
            val legal = MoveGenerator.generate(state)
            val filteredOut = legal.count { move ->
                MoveFingerprint.of(state, move) in rejected
            }
            val cause = when {
                legal.isEmpty() -> "no-legal-moves"
                legal.size == filteredOut -> "all-rejected($filteredOut)"
                else -> "all-avoided(legal=${legal.size},rejected=$filteredOut)"
            }
            val msg = "No move suggested ($cause) (${elapsedMs}ms) known=$knownFaceUp"
            statusSink(msg)
            logOutcome(
                "NO_MOVES",
                msg,
                detection,
                elapsedMs,
                frameW,
                frameH,
                move = null,
                from = null,
                to = null,
                knownFaceUp = knownFaceUp
            )
            return
        }

        // Always draw the best legal move once the board is stable.
        // Recognition quality is logged via knownFaceUp / diag — hiding the arrow
        // left the user with no feedback while ranks are still weak.
        val endpoints = endpointsFor(best.move, detection)
        val from = endpoints.first
        val to = endpoints.second
        if (from == null || to == null) {
            overlayController.hideArrowTemporarily()
            lastSuggestion = null
            lastSuggestionSignature = null
            val msg = "Move found but endpoints missing: ${best.move.label} known=$knownFaceUp"
            statusSink(msg)
            logOutcome(
                "NO_ENDPOINTS",
                msg,
                detection,
                elapsedMs,
                frameW,
                frameH,
                move = best.move,
                from = null,
                to = null,
                knownFaceUp = knownFaceUp,
                score = best.score,
                rationale = best.rationale
            )
            return
        }
        val dx = kotlin.math.abs(from.centerX - to.centerX)
        val dy = kotlin.math.abs(from.centerY - to.centerY)
        if (dx + dy < 20f) {
            overlayController.hideArrowTemporarily()
            val msg = "Degenerate arrow endpoints — hiding"
            statusSink(msg)
            logOutcome(
                "DEGENERATE_ARROW",
                msg,
                detection,
                elapsedMs,
                frameW,
                frameH,
                move = best.move,
                from = from,
                to = to,
                knownFaceUp = knownFaceUp,
                score = best.score,
                rationale = best.rationale
            )
            return
        }

        val suggested = SuggestedMove(best, from, to)
        val shouldBlink = lastSuggestionSignature != null &&
            lastSuggestionSignature != signature &&
            visuallySameArrow(lastSuggestion, suggested)
        lastSuggestion = suggested
        lastSuggestionSignature = signature
        if (shouldBlink) {
            overlayController.blinkMove(from, to)
        } else {
            overlayController.showMove(from, to)
        }
        val msg =
            "Best: ${best.move.label} score=${"%.1f".format(best.score)} " +
                "(${best.rationale}) ${elapsedMs}ms conf=${"%.2f".format(detection.confidence)} " +
                "known=$knownFaceUp"
        statusSink(msg)
        Log.i(TAG, detection.diagnostics.joinToString(" | "))
        logOutcome(
            "ARROW",
            msg,
            detection,
            elapsedMs,
            frameW,
            frameH,
            move = best.move,
            from = from,
            to = to,
            knownFaceUp = knownFaceUp,
            score = best.score,
            rationale = best.rationale
        )
    }

    private fun countKnownFaceUp(state: GameState): Int =
        state.tableau.sumOf { col -> col.count { it.faceUp && it.known } } +
            state.waste.count { it.known } +
            state.foundations.sumOf { pile -> pile.count { it.known } }

    private fun isDetectionReliableEnough(
        detection: DetectionResult,
        knownFaceUp: Int
    ): Boolean {
        if (detection.confidence >= MIN_CONFIDENCE_FOR_ARROW &&
            knownFaceUp >= MIN_KNOWN_FOR_ARROW
        ) {
            return true
        }
        return detection.confidence >= HIGH_CONFIDENCE_THRESHOLD &&
            knownFaceUp >= MIN_KNOWN_HIGH_CONF
    }

    /** Explains which gate threshold blocked the arrow, for log triage. */
    private fun gateFailureReason(detection: DetectionResult, knownFaceUp: Int): String {
        val conf = detection.confidence
        val lowConf = conf < MIN_CONFIDENCE_FOR_ARROW
        val lowKnown = knownFaceUp < MIN_KNOWN_FOR_ARROW
        return when {
            lowConf && lowKnown ->
                "conf<${MIN_CONFIDENCE_FOR_ARROW}&known<$MIN_KNOWN_FOR_ARROW"
            lowConf -> "conf<$MIN_CONFIDENCE_FOR_ARROW"
            lowKnown -> "known<$MIN_KNOWN_FOR_ARROW"
            // Passed the primary gate values but failed the high-confidence fallback.
            else -> "highConf-path:conf<$HIGH_CONFIDENCE_THRESHOLD|known<$MIN_KNOWN_HIGH_CONF"
        }
    }

    private fun logOutcome(
        outcome: String,
        status: String,
        detection: DetectionResult,
        elapsedMs: Long,
        frameW: Int,
        frameH: Int,
        move: Move?,
        from: BoardRegion?,
        to: BoardRegion?,
        knownFaceUp: Int,
        score: Double? = null,
        rationale: String? = null
    ) {
        val key = buildString {
            append(outcome)
            append('|')
            append(move?.label ?: "-")
            append('|')
            append(knownFaceUp)
            append('|')
            append(lastSignature ?: "")
        }
        if (key == lastLoggedOutcome) return
        lastLoggedOutcome = key

        val fromTxt = from?.let {
            "from=(${"%.0f".format(it.centerX)},${"%.0f".format(it.centerY)})"
        } ?: "from=null"
        val toTxt = to?.let {
            "to=(${"%.0f".format(it.centerX)},${"%.0f".format(it.centerY)})"
        } ?: "to=null"

        fileLogger.append(
            buildString {
                appendLine("OUTCOME=$outcome status=$status")
                appendLine("  frame=${frameW}x$frameH elapsed=${elapsedMs}ms conf=${"%.2f".format(detection.confidence)} knownFaceUp=$knownFaceUp")
                appendLine("  move=${move?.label ?: "-"} score=${score?.let { "%.1f".format(it) } ?: "-"} rationale=${rationale ?: "-"}")
                appendLine("  $fromTxt $toTxt")
                appendLine("  signature=$lastSignature")
                detection.diagnostics.forEach { d -> appendLine("  diag: $d") }
            }.trimEnd()
        )
    }

    private fun endpointsFor(
        move: Move,
        detection: DetectionResult
    ): Pair<BoardRegion?, BoardRegion?> {
        val locs = detection.locations
        fun pileRegion(ref: PileRef, index: Int = -1): BoardRegion? =
            detector.regionForMove(locs, ref, index, detection.board)
        fun tableauRunSource(move: Move.TableauToTableau): BoardRegion? {
            val locations = locs[PileRef.Tableau(move.fromColumn)] ?: return null
            val bottom = locations.lastOrNull()?.bounds ?: return null
            val column = detection.state?.tableau?.getOrNull(move.fromColumn) ?: return null
            val movingCount = column.size - move.startIndex
            if (movingCount <= 1) return bottom
            val profile = detection.board?.profile ?: return pileRegion(
                PileRef.Tableau(move.fromColumn),
                move.startIndex
            )
            val cardHeight = bottom.width * profile.cardAspect
            val faceUpStep = cardHeight * profile.faceUpOverlap
            val storedStart = locations.firstOrNull {
                it.cardIndex == move.startIndex
            }?.bounds
            val firstMovingTop = storedStart?.top
                ?: (bottom.top - (movingCount - 1) * faceUpStep)
            // Anchor in the visible header of the first moving card, rather
            // than the obscured center of its full card body.
            return BoardRegion(
                left = storedStart?.left ?: bottom.left,
                top = firstMovingTop,
                right = storedStart?.right ?: bottom.right,
                bottom = firstMovingTop + faceUpStep
            )
        }

        return when (move) {
            is Move.TableauToTableau -> {
                tableauRunSource(move) to
                    pileRegion(PileRef.Tableau(move.toColumn))
            }
            is Move.TableauToFoundation -> {
                pileRegion(PileRef.Tableau(move.fromColumn)) to
                    pileRegion(PileRef.Foundation(move.toFoundation))
            }
            is Move.WasteToTableau -> {
                pileRegion(PileRef.Waste) to pileRegion(PileRef.Tableau(move.toColumn))
            }
            is Move.WasteToFoundation -> {
                pileRegion(PileRef.Waste) to pileRegion(PileRef.Foundation(move.toFoundation))
            }
            Move.DrawStock -> {
                val stock = pileRegion(PileRef.Stock)
                val waste = pileRegion(PileRef.Waste)
                if (detection.state?.waste.isNullOrEmpty() && stock != null) {
                    // Tap the stock pile itself; avoid pointing at the empty waste slot.
                    val tapFrom = BoardRegion(
                        left = stock.centerX - 1f,
                        top = stock.top - 36f,
                        right = stock.centerX + 1f,
                        bottom = stock.top - 8f
                    )
                    val tapTo = BoardRegion(
                        left = stock.left + stock.width * 0.25f,
                        top = stock.top + stock.height * 0.35f,
                        right = stock.left + stock.width * 0.75f,
                        bottom = stock.top + stock.height * 0.85f
                    )
                    tapFrom to tapTo
                } else {
                    stock to waste
                }
            }
            Move.RecycleWaste -> {
                pileRegion(PileRef.Waste) to pileRegion(PileRef.Stock)
            }
        }
    }

    private fun visuallySameArrow(
        previous: SuggestedMove?,
        current: SuggestedMove
    ): Boolean {
        val oldFrom = previous?.from ?: return false
        val oldTo = previous.to ?: return false
        val newFrom = current.from ?: return false
        val newTo = current.to ?: return false
        return kotlin.math.abs(oldFrom.centerX - newFrom.centerX) < 16f &&
            kotlin.math.abs(oldFrom.centerY - newFrom.centerY) < 16f &&
            kotlin.math.abs(oldTo.centerX - newTo.centerX) < 16f &&
            kotlin.math.abs(oldTo.centerY - newTo.centerY) < 16f
    }

    private fun publishLabSnapshot(bitmap: Bitmap, detection: DetectionResult) {
        // Emit a private copy and hand ownership to the collector. We must NOT
        // recycle the previously emitted bitmap here: this runs on the capture
        // thread and the ViewModel may still be reading it on the main thread,
        // which caused a use-after-recycle crash when opening the Template Lab.
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        _labSnapshot.value = LabSnapshot(
            bitmap = copy,
            detection = detection,
            regions = buildLabRegions(detection)
        )
    }

    private fun buildLabRegions(detection: DetectionResult): List<LabCardRegion> {
        val regions = mutableListOf<LabCardRegion>()
        val state = detection.state
        detection.locations[PileRef.Waste]?.lastOrNull()?.let { loc ->
            regions += LabCardRegion(
                id = "waste",
                label = "Waste",
                region = loc.bounds,
                detectedCard = state?.wasteTop()
            )
        }
        detection.locations.filterKeys { it is PileRef.Foundation }.forEach { (pile, locs) ->
            val foundation = pile as PileRef.Foundation
            locs.lastOrNull()?.let { loc ->
                val card = state?.foundations?.getOrNull(foundation.index)?.lastOrNull()
                if (card != null) {
                    regions += LabCardRegion(
                        id = "foundation-${foundation.index}",
                        label = "Foundation ${foundation.index + 1}",
                        region = loc.bounds,
                        detectedCard = card
                    )
                }
            }
        }
        detection.locations.filterKeys { it is PileRef.Tableau }.forEach { (pile, locs) ->
            val col = (pile as PileRef.Tableau).index
            locs.lastOrNull()?.let { loc ->
                val card = state?.tableau?.getOrNull(col)?.lastOrNull()?.takeIf { it.faceUp }
                regions += LabCardRegion(
                    id = "tableau-$col",
                    label = "Tableau ${col + 1}",
                    region = loc.bounds,
                    detectedCard = card?.takeIf { it.known }
                )
            }
        }
        return regions
    }

    companion object {
        private const val TAG = "AnalysisPipeline"
        private const val MIN_CONFIDENCE_FOR_ARROW = 0.75f
        private const val MIN_KNOWN_FOR_ARROW = 4
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.82f
        private const val MIN_KNOWN_HIGH_CONF = 6
    }
}
