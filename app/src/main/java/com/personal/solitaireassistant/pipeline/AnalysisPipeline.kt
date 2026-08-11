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
import com.personal.solitaireassistant.solver.MoveSelector
import com.personal.solitaireassistant.vision.DetectionResult
import com.personal.solitaireassistant.vision.GameStateDetector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AnalysisPipeline(
    appContext: Context,
    private val overlayController: OverlayController,
    private val statusSink: (String) -> Unit
) {
    private val detector = GameStateDetector(appContext)
    private val fileLogger = AnalysisFileLogger(appContext)
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

    val analysisLogPath: String get() = fileLogger.pathForDisplay()

    fun updateSettings(settings: AssistantSettings) {
        settingsRef.set(settings)
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
        lastFrameFingerprint = null
        lastDetection = null
        overlayController.hideArrowTemporarily()
        fileLogger.append("=== pipeline cleared ===")
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

        val knownFaceUp = state.tableau.sumOf { col -> col.count { it.faceUp && it.known } } +
            state.waste.count { it.known } +
            state.foundations.sumOf { pile -> pile.count { it.known } }
        val reliableFirstHit = detection.confidence >= 0.82f && knownFaceUp >= 4

        if (stableHits < 2 && !reliableFirstHit) {
            statusSink("Stabilizing detection… conf=${"%.2f".format(detection.confidence)}")
            lastSuggestion?.let { restore ->
                if (restore.from != null && restore.to != null) {
                    overlayController.showMove(restore.from, restore.to)
                }
            }
            return
        }

        if (recentStates.lastOrNull() != state) {
            recentStates.addLast(state)
            while (recentStates.size > 4) recentStates.removeFirst()
        }
        val avoidStates = recentStates.dropLast(1)
        val best = MoveSelector.bestMove(state, avoidStates)
        if (best == null) {
            overlayController.hideArrowTemporarily()
            lastSuggestion = null
            lastSuggestionSignature = null
            val msg = "No legal moves (${elapsedMs}ms) known=$knownFaceUp"
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
            Move.DrawStock, Move.RecycleWaste -> {
                pileRegion(PileRef.Stock) to pileRegion(PileRef.Waste)
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

    companion object {
        private const val TAG = "AnalysisPipeline"
    }
}
