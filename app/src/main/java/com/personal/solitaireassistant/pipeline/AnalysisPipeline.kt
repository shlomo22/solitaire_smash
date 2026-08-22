package com.personal.solitaireassistant.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import com.personal.solitaireassistant.capture.PendingSnapshot
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.ScoredMove
import com.personal.solitaireassistant.game.SuggestedMove
import com.personal.solitaireassistant.game.Suit
import com.personal.solitaireassistant.overlay.OverlayController
import com.personal.solitaireassistant.settings.AssistantSettings
import com.personal.solitaireassistant.settings.RejectedMoveStore
import com.personal.solitaireassistant.solver.MoveFingerprint
import com.personal.solitaireassistant.solver.MoveSelector
import com.personal.solitaireassistant.vision.DetectionResult
import com.personal.solitaireassistant.vision.GameStateDetector
import com.personal.solitaireassistant.vision.GoldenSample
import com.personal.solitaireassistant.vision.RecognizedSlot
import com.personal.solitaireassistant.vision.RejectedSnapshotStore
import com.personal.solitaireassistant.vision.RejectionMeta
import com.personal.solitaireassistant.vision.SlotKind
import com.personal.solitaireassistant.vision.recognitionTraceLines
import com.personal.solitaireassistant.vision.toGoldenSlot
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AnalysisPipeline(
    appContext: Context,
    private val overlayController: OverlayController,
    private val statusSink: (String) -> Unit
) {
    private val detector = GameStateDetector(appContext)
    private val fileLogger = AnalysisFileLogger(appContext)
    private val rejectedMoveStore = RejectedMoveStore(appContext)
    private val rejectedSnapshotStore = RejectedSnapshotStore(appContext)
    private val rejectionExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
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
    private var lastFrameBitmap: Bitmap? = null
    private val snapshotLock = Any()
    private var lastStableState: GameState? = null
    private val sessionRejected = mutableSetOf<String>()
    private val pendingFrame = AtomicReference<PendingFrame?>(null)
    private var pendingSuggestionCandidate: Move? = null
    private var pendingSuggestionStreak = 0

    val analysisLogPath: String get() = fileLogger.pathForDisplay()

    private data class PendingFrame(
        val bitmap: Bitmap,
        val settings: AssistantSettings,
        val capturedAtMs: Long
    )

    fun updateSettings(settings: AssistantSettings) {
        settingsRef.set(settings)
    }

    // onFrame is called directly from the screen-capture thread's
    // onImageAvailable callback. It used to run detect()+solve+overlay
    // (up to several seconds) synchronously right there, which blocked that
    // thread from ever seeing a newer frame until the current one finished —
    // so a move made mid-analysis was invisible to capture until a whole
    // extra cycle later. Copy and hand off to a dedicated analysis thread
    // instead, so capture stays free to keep grabbing the latest frame the
    // whole time analysis is running.
    fun onFrame(bitmap: Bitmap, settings: AssistantSettings) {
        settingsRef.set(settings)
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        pendingFrame.getAndSet(PendingFrame(copy, settings, System.currentTimeMillis()))
            ?.bitmap?.recycle()
        if (busy.compareAndSet(false, true)) {
            analysisExecutor.execute(::drainPendingFrames)
        }
    }

    private fun drainPendingFrames() {
        try {
            while (true) {
                val pending = pendingFrame.getAndSet(null) ?: break
                try {
                    processFrame(pending.bitmap, pending.settings, pending.capturedAtMs)
                } finally {
                    pending.bitmap.recycle()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Analysis failed", t)
            fileLogger.append("ERROR ${t.javaClass.simpleName}: ${t.message}")
            statusSink("Analysis error: ${t.message}")
            overlayController.hideArrowTemporarily()
        } finally {
            busy.set(false)
            // onFrame runs on the capture thread now, concurrently with this
            // drain loop, so a frame can be enqueued right after our last
            // empty check above but before busy flips false — onFrame would
            // then see busy still true and not schedule anyone to pick it up.
            // Re-check once after clearing busy and reclaim if so.
            if (pendingFrame.get() != null && busy.compareAndSet(false, true)) {
                analysisExecutor.execute(::drainPendingFrames)
            }
        }
    }

    private fun processFrame(bitmap: Bitmap, settings: AssistantSettings, capturedAtMs: Long) {
        settingsRef.set(settings)
        if (!sessionStarted) {
            sessionStarted = true
            fileLogger.sessionStart("${bitmap.width}x${bitmap.height}")
        }
        // Keep prior arrow while analyzing to avoid constant flash.

        val started = System.currentTimeMillis()
        val fingerprint = boardFingerprint(bitmap)
        val cached = fingerprint == lastFrameFingerprint
        val boardVisuallyChanged = !cached
        val detection = if (cached) {
            lastDetection ?: detector.detect(bitmap)
        } else {
            detector.detect(bitmap)
        }
        if (!cached || lastDetection == null) {
            lastFrameFingerprint = fingerprint
        }
        synchronized(snapshotLock) {
            lastDetection = detection
            // Snapshot for golden review only needs updating when the board changed.
            if (boardVisuallyChanged) {
                retainFrameLocked(bitmap)
            }
        }
        val elapsed = System.currentTimeMillis() - started
        // How long this frame sat in the pending-frame handoff before
        // analysis actually started on it — the part of end-to-end latency
        // the detect()/cache optimizations don't touch at all.
        val queueDelayMs = started - capturedAtMs
        handleDetection(
            detectionRaw = detection,
            elapsedMs = elapsed,
            frameW = bitmap.width,
            frameH = bitmap.height,
            boardVisuallyChanged = boardVisuallyChanged,
            queueDelayMs = queueDelayMs
        )
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
        pendingSuggestionCandidate = null
        pendingSuggestionStreak = 0
        synchronized(snapshotLock) {
            lastDetection = null
            lastFrameBitmap?.recycle()
            lastFrameBitmap = null
        }
        lastStableState = null
        pendingFrame.getAndSet(null)?.bitmap?.recycle()
        detector.clearSlotCache()
        overlayController.hideArrowTemporarily()
        fileLogger.append("=== pipeline cleared ===")
    }

    private fun peekCurrentFrame(): PendingSnapshot? = synchronized(snapshotLock) {
        val frame = lastFrameBitmap?.takeIf { !it.isRecycled } ?: return@synchronized null
        val detection = lastDetection ?: return@synchronized null
        val copy = frame.copy(Bitmap.Config.ARGB_8888, false) ?: return@synchronized null
        PendingSnapshot(
            bitmap = copy,
            slots = detection.recognizedSlots,
            diagnostics = detection.diagnostics
        )
    }

    fun createSnapshot(): PendingSnapshot? {
        val snapshot = peekCurrentFrame() ?: return null
        overlayController.hideArrowTemporarily()
        return snapshot
    }

    private fun retainFrameLocked(src: Bitmap) {
        val dest = lastFrameBitmap
        if (dest != null &&
            !dest.isRecycled &&
            dest.isMutable &&
            dest.width == src.width &&
            dest.height == src.height
        ) {
            Canvas(dest).drawBitmap(src, 0f, 0f, null)
        } else {
            dest?.recycle()
            lastFrameBitmap = src.copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    fun cancelCurrentHint() {
        val state = lastStableState ?: return
        val suggestion = lastSuggestion ?: return
        val frameSnapshot = peekCurrentFrame()
        val fingerprint = MoveFingerprint.of(state, suggestion.scored.move)
        // Never remember stock draw/recycle as rejected — that leaves the user
        // with no arrow when every card hint has already been cancelled.
        if (!MoveFingerprint.isStockFallback(fingerprint)) {
            sessionRejected += fingerprint
            rejectedMoveStore.reject(fingerprint)
            fileLogger.append(
                "REJECTED fingerprint=$fingerprint move=${suggestion.scored.move.label}"
            )
            statusSink("Rejected hint — showing next move")
        } else {
            statusSink("Stock hint kept available")
        }
        frameSnapshot?.let { snap ->
            saveRejectionSnapshotAsync(snap, suggestion, fingerprint)
        }
        val detection = lastDetection
        if (detection?.state != null && lastSignature != null) {
            showBestSuggestion(
                detection = detection,
                signature = lastSignature!!,
                knownFaceUp = countKnownFaceUp(detection.state),
                elapsedMs = 0L,
                frameW = 0,
                frameH = 0
            )
        } else {
            overlayController.hideArrowTemporarily()
            lastSuggestion = null
            lastSuggestionSignature = null
        }
    }

    private fun saveRejectionSnapshotAsync(
        snapshot: PendingSnapshot,
        suggestion: SuggestedMove,
        fingerprint: String
    ) {
        val id = rejectedSnapshotStore.nextId()
        val sample = GoldenSample(
            id = id,
            frameWidth = snapshot.bitmap.width,
            frameHeight = snapshot.bitmap.height,
            slots = snapshot.slots.map { it.toGoldenSlot() }
        )
        val meta = RejectionMeta(
            moveLabel = suggestion.scored.move.label,
            fingerprint = fingerprint,
            from = suggestion.from,
            to = suggestion.to
        )
        rejectionExecutor.execute {
            try {
                rejectedSnapshotStore.save(snapshot.bitmap, sample, meta)
                fileLogger.append(
                    "REJECTION_SNAPSHOT id=$id path=${rejectedSnapshotStore.dir().absolutePath}"
                )
                statusSink("Saved rejection snapshot $id")
            } catch (e: Exception) {
                Log.w(TAG, "Failed saving rejection snapshot", e)
                fileLogger.append("REJECTION_SNAPSHOT_FAILED id=$id error=${e.message}")
            } finally {
                snapshot.bitmap.recycle()
            }
        }
    }

    private fun countKnownFaceUp(state: GameState): Int =
        state.tableau.sumOf { col -> col.count { it.faceUp && it.known } } +
            state.waste.count { it.known } +
            state.foundations.sumOf { pile -> pile.count { it.known } }

    private fun boardFingerprint(bitmap: Bitmap): Long {
        var hash = fingerprintRegion(
            bitmap = bitmap,
            left = 0,
            right = bitmap.width,
            top = (bitmap.height * 0.20f).toInt(),
            bottom = (bitmap.height * 0.68f).toInt(),
            cols = 54,
            rows = 60,
            seed = -0x340d631b7bdddcdbL
        )
        // Stock/waste churn a lot on each move; sample the top bar separately so
        // draw/recycle moves are not missed by the main tableau fingerprint.
        hash = fingerprintRegion(
            bitmap = bitmap,
            left = 0,
            right = bitmap.width,
            top = (bitmap.height * 0.06f).toInt(),
            bottom = (bitmap.height * 0.22f).toInt(),
            cols = 40,
            rows = 10,
            seed = hash
        )
        return hash
    }

    private fun fingerprintRegion(
        bitmap: Bitmap,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        cols: Int,
        rows: Int,
        seed: Long
    ): Long {
        if (bottom <= top || right <= left) return seed
        val stepX = ((right - left) / cols).coerceAtLeast(1)
        val stepY = ((bottom - top) / rows).coerceAtLeast(1)
        var hash = seed
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = bitmap.getPixel(x, y)
                // 4 bits/channel (16-unit buckets), not 5 (8-unit buckets): a
                // real device log showed this whole-board fingerprint flipping
                // on almost every frame with the game visually static, which
                // marks boardVisuallyChanged=true nonstop and defeats the
                // stableHits<2 debounce in handleDetection() — a low-confidence
                // slot (0.58, partially-occluded tableau card) then alternated
                // between two different reads every other frame because
                // fastUpdateAfterMove kept firing instead of the debounce path,
                // flipping the suggested move back and forth with no real board
                // change. regionFingerprint() below hit the identical failure
                // mode for the per-slot cache and was already widened from 5 to
                // 4 bits/channel for the same reason; this brings the
                // whole-board fingerprint in line with that fix.
                val quantized =
                    (((color shr 20) and 0xF) shl 8) or
                        (((color shr 12) and 0xF) shl 4) or
                        ((color shr 4) and 0xF)
                hash = (hash xor quantized.toLong()) * 0x100000001b3L
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    private fun handleDetection(
        detectionRaw: DetectionResult,
        elapsedMs: Long,
        frameW: Int,
        frameH: Int,
        boardVisuallyChanged: Boolean,
        queueDelayMs: Long
    ) {
        val rawState = detectionRaw.state
        if (!detectionRaw.livePlayScreen) {
            stableHits = 0
            lastSignature = null
            lastSuggestion = null
            lastSuggestionSignature = null
            overlayController.hideAllChrome()
            val msg = "Waiting for game board… ${elapsedMs}ms"
            statusSink(msg)
            logOutcome(
                "NOT_PLAY_SCREEN",
                msg,
                detectionRaw,
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
        if (rawState == null) {
            stableHits = 0
            lastSignature = null
            lastSuggestion = null
            lastSuggestionSignature = null
            overlayController.hideAllChrome()
            val msg = "No board (${detectionRaw.diagnostics.lastOrNull()}) ${elapsedMs}ms"
            statusSink(msg)
            logOutcome(
                "NO_BOARD",
                msg,
                detectionRaw,
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

        val previous = recentStates.lastOrNull() ?: lastDetection?.state
        val state = stabilizeBlackSuits(rawState, previous)
        val detection = if (state !== rawState) {
            detectionRaw.copy(state = state)
        } else {
            detectionRaw
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
        val minConf = settingsRef.get().minMatchConfidence
        val reliableFirstHit = detection.confidence >= 0.82f && knownFaceUp >= 4
        val stateChangedSinceSuggestion =
            lastSuggestionSignature != null && signature != lastSuggestionSignature
        // After a move the screen changes immediately — don't wait for a second
        // matching frame before updating the arrow.
        val fastUpdateAfterMove =
            boardVisuallyChanged &&
                knownFaceUp >= 2 &&
                detection.confidence >= (minConf - 0.15f).coerceAtLeast(0.52f)

        if (stableHits < 2 && !reliableFirstHit && !fastUpdateAfterMove) {
            statusSink("Stabilizing detection… conf=${"%.2f".format(detection.confidence)}")
            val sameBoardAsLastSuggestion =
                lastSuggestionSignature != null && signature == lastSuggestionSignature
            if (sameBoardAsLastSuggestion) {
                lastSuggestion?.let { restore ->
                    if (restore.from != null && restore.to != null) {
                        overlayController.showMove(restore.from, restore.to)
                    }
                }
            } else if (boardVisuallyChanged && knownFaceUp >= 2 && detection.confidence >= 0.48f) {
                // Visual change detected but confidence is still settling — prefer a
                // best-effort new arrow over keeping the previous move visible.
                lastStableState = state
                if (recentStates.lastOrNull() != state) {
                    recentStates.addLast(state)
                    while (recentStates.size > 4) recentStates.removeFirst()
                }
                showBestSuggestion(
                    detection = detection,
                    signature = signature,
                    knownFaceUp = knownFaceUp,
                    elapsedMs = elapsedMs,
                    frameW = frameW,
                    frameH = frameH,
                    boardVisuallyChanged = true,
                    queueDelayMs = queueDelayMs
                )
            } else if (stateChangedSinceSuggestion || boardVisuallyChanged) {
                overlayController.hideArrowTemporarily()
            }
            return
        }

        lastStableState = state
        if (recentStates.lastOrNull() != state) {
            recentStates.addLast(state)
            while (recentStates.size > 4) recentStates.removeFirst()
        }
        showBestSuggestion(
            detection = detection,
            signature = signature,
            knownFaceUp = knownFaceUp,
            elapsedMs = elapsedMs,
            frameW = frameW,
            frameH = frameH,
            boardVisuallyChanged = boardVisuallyChanged,
            queueDelayMs = queueDelayMs
        )
    }

    private fun showBestSuggestion(
        detection: DetectionResult,
        signature: String,
        knownFaceUp: Int,
        elapsedMs: Long,
        frameW: Int,
        frameH: Int,
        boardVisuallyChanged: Boolean = false,
        queueDelayMs: Long = 0L
    ) {
        val state = detection.state ?: return
        val selectStartedMs = System.currentTimeMillis()
        val avoidStates = recentStates.dropLast(1)
        val rejected = rejectedMoveStore.all() + sessionRejected
        val ranked = MoveSelector.rankedMoves(state, rejected) { move ->
            isFoundationMoveTrusted(move, state, detection)
        }
        val rawBest = MoveSelector.pickBestFromRanked(ranked, state, avoidStates)
        val selectMs = System.currentTimeMillis() - selectStartedMs
        if (rawBest == null) {
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
                knownFaceUp = knownFaceUp,
                queueDelayMs = queueDelayMs,
                selectMs = selectMs
            )
            return
        }
        val best = applySuggestionStickiness(ranked, rawBest) ?: return

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
        val shouldBlink = !boardVisuallyChanged &&
            lastSuggestionSignature != null &&
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
            rationale = best.rationale,
            runnerUps = ranked.filter { it.move != best.move }.take(3),
            queueDelayMs = queueDelayMs,
            selectMs = selectMs
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
        rationale: String? = null,
        runnerUps: List<ScoredMove> = emptyList(),
        queueDelayMs: Long = 0L,
        selectMs: Long = 0L
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
                appendLine(
                    "  timing: queueDelay=${queueDelayMs}ms detect=${elapsedMs}ms " +
                        "select=${selectMs}ms total=${queueDelayMs + elapsedMs + selectMs}ms"
                )
                appendLine("  move=${move?.label ?: "-"} score=${score?.let { "%.1f".format(it) } ?: "-"} rationale=${rationale ?: "-"}")
                if (runnerUps.isNotEmpty()) {
                    appendLine("  runner-up:")
                    runnerUps.forEachIndexed { index, alt ->
                        appendLine(
                            "    ${index + 1}. ${alt.move.label} " +
                                "score=${"%.1f".format(alt.score)} (${alt.rationale})"
                        )
                    }
                }
                appendLine("  $fromTxt $toTxt")
                appendLine("  signature=$lastSignature")
                detection.diagnostics.forEach { d -> appendLine("  diag: $d") }
                val slotTraces = recognitionTraceLines(detection.recognizedSlots)
                if (slotTraces.isNotEmpty()) {
                    appendLine("  recognition:")
                    slotTraces.forEach { line -> appendLine(line) }
                }
            }.trimEnd()
        )
    }

    /**
     * Damps single-frame flicker in which move is "best". A real device log
     * showed the arrow swap back and forth every ~750ms for 20+ seconds
     * between two moves on an otherwise-static board: one card's rank read
     * toggled between two values frame to frame, and that one card sat mid-
     * run in a tableau column - when read one way the whole run was a valid
     * sequence (unlocking a big column-clearing move), when read the other
     * way the run broke and that move vanished *entirely* (not just dropped
     * in score - it became flat-out illegal), leaving only a smaller
     * fallback. The existing signature-based stability gate doesn't catch
     * this because the board's full signature also changes every frame here
     * (it's part of what's flickering), so reliableFirstHit bypasses the
     * wait.
     *
     * Rather than fix the specific misread (recognition-side, not this
     * pipeline's job), require a newly-best move to win 2 consecutive frames
     * before actually committing to it. Critically, the streak still counts
     * even on a frame where the previously-shown move has vanished outright
     * (an earlier version of this only counted frames where the old move
     * was still ranked, which never happens in the real flicker case above -
     * the big move doesn't lose the top spot, it disappears, so that
     * version's fast-path always fired and the fix did nothing). On such a
     * frame we still have to show *something* legal, so we fall back to
     * `best` for display without resetting the pending streak - the eventual
     * commit only happens once the same answer wins 2 frames running.
     */
    /**
     * Returns null to mean "hold the current display, don't touch the overlay this
     * frame" - the caller must treat that as a no-op, not fall back to [best].
     *
     * A real device log (multi-card Tableau->Tableau run vs. loose-card
     * Tableau->Foundation, alternating every frame) showed this still flicker even
     * after the streak damping below: one buried, non-exposed card in the run
     * (tableau:5:2) misread as Ace of Diamonds for a single isolated frame instead
     * of its stable Nine of Diamonds read (held on ~30 surrounding frames) - just
     * one frame was enough to break isValidRun() for the whole run and make the
     * previous move genuinely ILLEGAL that frame, not merely lower-ranked. The old
     * code's `?: best` fallback treated "previous move absent from ranked" the same
     * as "streak exhausted, adopt best" and jumped immediately. Now a vanished
     * previous move gets the same 2-frame grace period as adopting a new one: hold
     * the prior display instead of reacting to a single anomalous frame.
     */
    private fun applySuggestionStickiness(
        ranked: List<ScoredMove>,
        best: ScoredMove
    ): ScoredMove? {
        val previous = lastSuggestion?.scored
        if (previous == null || best.move == previous.move) {
            pendingSuggestionCandidate = null
            pendingSuggestionStreak = 0
            return best
        }
        if (pendingSuggestionCandidate == best.move) {
            pendingSuggestionStreak++
        } else {
            pendingSuggestionCandidate = best.move
            pendingSuggestionStreak = 1
        }
        if (pendingSuggestionStreak >= 2) {
            pendingSuggestionCandidate = null
            pendingSuggestionStreak = 0
            return best
        }
        val stillRanked = ranked.firstOrNull { it.move == previous.move }
        if (stillRanked == null) {
            fileLogger.append(
                "HOLD prev=${previous.move.label} vanished from ranked " +
                    "(raw=${best.move.label} streak=$pendingSuggestionStreak/2)"
            )
        }
        return stillRanked
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
            val column = detection.state?.tableau?.getOrNull(move.fromColumn) ?: return null
            val profile = detection.board?.profile
            val storedStart = locations.getOrNull(move.startIndex)?.bounds
            val headerHeight = when {
                storedStart != null && profile != null ->
                    storedStart.width * profile.cardAspect * profile.faceUpOverlap
                storedStart != null ->
                    (storedStart.bottom - storedStart.top) * 0.28f
                else -> null
            }
            if (storedStart != null && headerHeight != null) {
                return BoardRegion(
                    left = storedStart.left,
                    top = storedStart.top,
                    right = storedStart.right,
                    bottom = (storedStart.top + headerHeight).coerceAtMost(storedStart.bottom)
                )
            }
            val bottom = locations.lastOrNull()?.bounds ?: return null
            val movingCount = column.size - move.startIndex
            if (movingCount <= 1) return bottom
            if (profile == null) {
                return pileRegion(PileRef.Tableau(move.fromColumn), move.startIndex)
            }
            val cardHeight = bottom.width * profile.cardAspect
            val faceUpStep = cardHeight * profile.faceUpOverlap
            val firstMovingTop = bottom.top - (movingCount - 1) * faceUpStep
            return BoardRegion(
                left = bottom.left,
                top = firstMovingTop,
                right = bottom.right,
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
            is Move.FoundationToTableau -> {
                pileRegion(PileRef.Foundation(move.fromFoundation)) to
                    pileRegion(PileRef.Tableau(move.toColumn))
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

    /**
     * Block foundation arrows when suit reads are ambiguous or were corrected by
     * deck-constraint partner swaps (the main source of illegal 4S→3C-style hints).
     */
    private fun isFoundationMoveTrusted(
        move: Move,
        state: GameState,
        detection: DetectionResult
    ): Boolean {
        if (move is Move.FoundationToTableau) {
            val movingCard = state.foundations.getOrNull(move.fromFoundation)?.lastOrNull()
                ?: return false
            if (movingCard.suitAmbiguous) return false
            if (slotHasPartnerSuitDeckConstraintSwap(
                    detection.recognizedSlots,
                    PileRef.Foundation(move.fromFoundation)
                )
            ) {
                return false
            }
            return true
        }
        val foundationIndex = when (move) {
            is Move.TableauToFoundation -> move.toFoundation
            is Move.WasteToFoundation -> move.toFoundation
            else -> return true
        }
        val movingCard = when (move) {
            is Move.TableauToFoundation ->
                state.tableau.getOrNull(move.fromColumn)?.lastOrNull()
            is Move.WasteToFoundation -> state.wasteTop()
            else -> return true
        } ?: return false
        if (movingCard.suitAmbiguous) return false

        val foundationTop = state.foundations.getOrNull(foundationIndex)?.lastOrNull()
        if (foundationTop?.suitAmbiguous == true) return false

        val movingPile = when (move) {
            is Move.TableauToFoundation -> PileRef.Tableau(move.fromColumn)
            is Move.WasteToFoundation -> PileRef.Waste
            else -> return true
        }
        if (slotHasPartnerSuitDeckConstraintSwap(detection.recognizedSlots, movingPile)) {
            return false
        }
        if (foundationTop != null &&
            slotHasPartnerSuitDeckConstraintSwap(
                detection.recognizedSlots,
                PileRef.Foundation(foundationIndex)
            )
        ) {
            return false
        }
        return true
    }

    private fun slotHasPartnerSuitDeckConstraintSwap(
        slots: List<RecognizedSlot>,
        pile: PileRef
    ): Boolean = slots.any { slot ->
        slot.pile == pile &&
            slot.engine.kind == SlotKind.FaceUp &&
            slot.trace.postSteps.any(::isPartnerSuitDeckConstraintStep)
    }

    private fun isPartnerSuitDeckConstraintStep(step: String): Boolean {
        if (!step.startsWith("deck-constraint:")) return false
        val body = step.removePrefix("deck-constraint:")
        val before = body.substringBefore("->")
        val after = body.substringAfter("->", missingDelimiterValue = "")
        if (after.isEmpty() || before.length < 2 || after.length < 2) return false
        val beforeSuit = before.last()
        val afterSuit = after.last()
        return (beforeSuit == 'C' && afterSuit == 'S') ||
            (beforeSuit == 'S' && afterSuit == 'C') ||
            (beforeSuit == 'H' && afterSuit == 'D') ||
            (beforeSuit == 'D' && afterSuit == 'H')
    }

    /**
     * Prefer a previously confident black suit when the current frame is ambiguous
     * for the same rank in the same pile. Prevents Clubs↔Spades flicker.
     */
    private fun stabilizeBlackSuits(
        current: GameState,
        previous: GameState?
    ): GameState {
        if (previous == null) return current

        val confidentBlackByRank = buildMap<Rank, Suit> {
            previous.allFaceUpCards().forEach { card ->
                if (card.known && !card.suit.isRed && !card.suitAmbiguous && !card.inferred) {
                    if (!containsKey(card.rank)) put(card.rank, card.suit)
                }
            }
        }

        fun stabilizeCard(now: Card, before: Card?): Card {
            if (!now.known || now.suit.isRed || !now.suitAmbiguous) return now
            if (before != null &&
                before.known &&
                !before.suit.isRed &&
                before.rank == now.rank &&
                !before.suitAmbiguous
            ) {
                return now.copy(suit = before.suit, suitAmbiguous = false)
            }
            confidentBlackByRank[now.rank]?.let { suit ->
                return now.copy(suit = suit, suitAmbiguous = false)
            }
            return now
        }

        val tableau = current.tableau.mapIndexed { col, cards ->
            val prevCol = previous.tableau.getOrNull(col).orEmpty()
            cards.mapIndexed { index, card ->
                stabilizeCard(card, prevCol.getOrNull(index))
            }
        }
        val foundations = current.foundations.mapIndexed { index, pile ->
            val prevPile = previous.foundations.getOrNull(index).orEmpty()
            pile.mapIndexed { cardIndex, card ->
                stabilizeCard(card, prevPile.getOrNull(cardIndex))
            }
        }
        val waste = current.waste.mapIndexed { index, card ->
            stabilizeCard(card, previous.waste.getOrNull(index))
        }
        if (tableau == current.tableau &&
            foundations == current.foundations &&
            waste == current.waste
        ) {
            return current
        }
        return current.copy(
            tableau = tableau,
            foundations = foundations,
            waste = waste
        )
    }

    private fun GameState.allFaceUpCards(): Sequence<Card> = sequence {
        foundations.forEach { pile -> pile.filter { it.faceUp }.forEach { yield(it) } }
        waste.filter { it.faceUp }.forEach { yield(it) }
        tableau.forEach { column -> column.filter { it.faceUp }.forEach { yield(it) } }
    }

    companion object {
        private const val TAG = "AnalysisPipeline"
    }
}
