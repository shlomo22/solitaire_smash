package com.personal.solitaireassistant.vision

import android.content.Context
import android.graphics.Bitmap
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.CardLocation
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

data class DetectionResult(
    val state: GameState?,
    val locations: Map<PileRef, List<CardLocation>>,
    val confidence: Float,
    val diagnostics: List<String>,
    val board: LocatedBoard?,
    val recognizedSlots: List<RecognizedSlot> = emptyList(),
    val livePlayScreen: Boolean = false,
    /** State after suit scrub, before [DeckConstraintPass]. */
    val preConstraintState: GameState? = null
)

class GameStateDetector(
    context: Context,
    minConfidence: Float = 0.65f
) {
    private val locator = BoardLocator()
    private val recognizer = CardRecognizer(context, minConfidence)
    private var slotHitCache = mutableMapOf<SlotKey, CachedSlotHit>()

    /** Memoized waste rank-OCR result; see the OCR call in [detect]. */
    private data class CachedWasteOcr(
        val fingerprint: Long,
        val regions: List<BoardRegion>,
        val result: RankCornerOcr.AttemptResult
    )

    private var cachedWasteOcr: CachedWasteOcr? = null

    // Dedicated pool for parallel tableau-column + foundation recognition
    // (see detect()'s computeColumn / computeFoundation), rather than the
    // shared Dispatchers.Default: detect() is also invoked from
    // GoldenTruthEvaluator via withContext(Dispatchers.Default), and nesting
    // runBlocking on that same shared pool would block one of its own worker
    // threads while trying to schedule more work onto it. Sized for 7
    // columns + 4 foundations so cheap foundation piles don't queue behind
    // a long cascade.
    private val columnExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(4, 11)
    )
    // Separate from columnExecutor so a long waste-OCR call cannot starve
    // the pile workers.
    private val wasteOcrExecutor = Executors.newSingleThreadExecutor()

    // Diagnostic-only: per-detect() timing, reset at the top of detect().
    // Not thread-safe, but the pipeline only ever runs one detect() at a time.
    private var recognizeCacheHits = 0
    private var recognizeCacheMisses = 0
    private var recognizeMissNanos = 0L
    private var missNoPriorEntry = 0
    private var missFingerprintChanged = 0
    private var missBoundsShifted = 0
    private val tableauColumnNanos = LongArray(7)

    private data class SlotKey(val pile: PileRef, val index: Int)
    private data class CachedSlotHit(
        val fingerprint: Long,
        val bounds: BoardRegion,
        val hit: RecognitionHit
    )

    // Per-column-local accumulator for recognizeCached's diagnostic counters.
    // Tableau columns run concurrently (see detect()'s computeColumn), so each
    // gets its own instance instead of incrementing the instance fields
    // directly - merged back into them sequentially once every column
    // finishes.
    private class RecognizeStats {
        var hits = 0
        var misses = 0
        var missNanos = 0L
        var missNoPrior = 0
        var missFingerprint = 0
        var missBounds = 0
    }

    private class FoundationPileResult(
        val index: Int,
        val cards: List<Card>,
        val location: CardLocation,
        val recognizedSlot: RecognizedSlot,
        val cacheEntries: Map<SlotKey, CachedSlotHit>,
        val stats: RecognizeStats,
        val diagnostic: String
    )

    private class TableauColumnResult(
        val col: Int,
        val cards: List<Card>,
        val locations: List<CardLocation>,
        val diagnostics: List<String>,
        val recognizedSlots: List<RecognizedSlot>,
        val cacheEntries: Map<SlotKey, CachedSlotHit>,
        val stats: RecognizeStats,
        val elapsedNanos: Long
    )

    @Suppress("UNUSED_PARAMETER")
    fun updateMinConfidence(value: Float) {
        // Recognizer is constructed with a threshold; pipeline passes settings for future rebuilds.
    }

    fun clearSlotCache() {
        slotHitCache.clear()
        cachedWasteOcr = null
    }

    fun detect(bitmap: Bitmap): DetectionResult {
        recognizeCacheHits = 0
        recognizeCacheMisses = 0
        recognizeMissNanos = 0L
        missNoPriorEntry = 0
        missFingerprintChanged = 0
        missBoundsShifted = 0
        tableauColumnNanos.fill(0L)
        val detectStartNanos = System.nanoTime()
        val board = locator.locate(bitmap)
        val diagnostics = mutableListOf<String>()
        diagnostics += "boardConfidence=${"%.2f".format(board.confidence)}"
        diagnostics += "profile=${board.profile.name}"
        val newSlotCache = mutableMapOf<SlotKey, CachedSlotHit>()

        val locations = linkedMapOf<PileRef, List<CardLocation>>()
        val recognizedSlots = mutableListOf<RecognizedSlot>()
        val foundations = MutableList(4) { emptyList<Card>() }
        val tableau = MutableList(7) { emptyList<Card>() }

        val stockRegion = locator.stockRegion(board)
        val stockStats = SmashColorAnalyzer.analyze(bitmap, stockRegion)
        val stockHit = when {
            SmashColorAnalyzer.looksLikeStockPile(stockStats) ||
                SmashColorAnalyzer.looksFaceDown(stockStats) ->
                RecognitionHit(null, 0.85f, true, false, "face-down-stock")
            else -> recognizer.recognize(bitmap, stockRegion)
        }
        locations[PileRef.Stock] = listOf(
            locator.toCardLocation(PileRef.Stock, 0, stockRegion)
        )
        recognizedSlots += recognizedSlot(
            pile = PileRef.Stock,
            index = 0,
            bounds = stockRegion,
            hit = stockHit
        )
        val stockCards = when {
            stockHit.isEmpty -> emptyList()
            else -> listOf(Card(Rank.Ace, Suit.Spades, faceUp = false, known = false))
        }
        diagnostics += "stock=${stockHit.diagnostic}"

        val computeFoundation = fun(index: Int, region: BoardRegion): FoundationPileResult {
            val stats = RecognizeStats()
            val localCache = mutableMapOf<SlotKey, CachedSlotHit>()
            val hit = recognizeCached(
                bitmap = bitmap,
                pile = PileRef.Foundation(index),
                index = 0,
                region = region,
                cache = localCache,
                stats = stats
            )
            val (foundationCard, trace) = cardFromHit(hit)?.let { card ->
                resolveCardSuitWithTrace(bitmap, region, card, hit.trace)
            } ?: (null to hit.trace)
            return FoundationPileResult(
                index = index,
                cards = listOfNotNull(foundationCard),
                location = locator.toCardLocation(PileRef.Foundation(index), 0, region),
                recognizedSlot = recognizedSlot(
                    pile = PileRef.Foundation(index),
                    index = 0,
                    bounds = region,
                    hit = hit,
                    cardOverride = foundationCard,
                    trace = trace
                ),
                cacheEntries = localCache,
                stats = stats,
                diagnostic = "foundation$index=${hit.diagnostic}:${foundationCard}"
            )
        }

        // Each column is recognized independently of the others (its own
        // region, its own slot indices), so the whole per-column body below
        // runs as a local function whose diagnostics/recognizedSlots/cache/
        // counters are all column-local - declared fresh here, they shadow
        // the detect()-level lists of the same name for the rest of this
        // function, so every `diagnostics +=` / `recognizedSlots +=` below
        // is untouched from the previous sequential version. Real device
        // logs showed one or two long cascades dominating a frame's time
        // (up to ~1.3s) while the rest finish in a couple of ms, so running
        // all 7 concurrently on a dedicated pool (columnExecutor)
        // lets the frame finish in roughly the slowest column's time instead
        // of the sum of all 7.
        val computeColumn = fun(col: Int, columnRegion: BoardRegion): TableauColumnResult {
            val columnStartNanos = System.nanoTime()
            val diagnostics = mutableListOf<String>()
            val recognizedSlots = mutableListOf<RecognizedSlot>()
            val localCache = mutableMapOf<SlotKey, CachedSlotHit>()
            val stats = RecognizeStats()
            val cards = mutableListOf<Card>()
            val locs = mutableListOf<CardLocation>()
            val cardWidth = columnRegion.width
            val cardHeight = cardWidth * board.profile.cardAspect
            val downStep = cardHeight * board.profile.faceDownOverlap
            val (faceRegion, faceScanTrace) = findPlayableFaceRegion(bitmap, columnRegion, cardHeight)
            diagnostics += "tableau$col.faceScan=$faceScanTrace"

            if (faceRegion != null) {
                // Count only genuinely teal headers above the playable face. This
                // avoids treating exposed face-up cascades as hidden cards.
                var y = columnRegion.top
                var faceDownCount = 0
                while (y + 4f < faceRegion.top && faceDownCount < 6) {
                    val stripBottom = (y + downStep).coerceAtMost(faceRegion.top)
                    val strip = BoardRegion(
                        columnRegion.left,
                        y,
                        columnRegion.right,
                        stripBottom
                    )
                    val stats = SmashColorAnalyzer.analyze(bitmap, strip)
                    // The faceRegion.top cap can truncate the final strip well
                    // below a full downStep when the true facedown/faceup
                    // boundary falls mid-band. A real golden sample showed that
                    // truncated sliver (21.6px of a 44.3px step) still averaging
                    // teal=0.32 - past looksFaceDown's 0.20 floor - purely
                    // because it straddles the boundary (part teal card back,
                    // part the exposed card's white top edge), not because a
                    // whole card sits there. Only trust a facedown read from a
                    // strip that got most of its intended height.
                    val stripHeight = stripBottom - y
                    if (stripHeight >= downStep * 0.85f && SmashColorAnalyzer.looksFaceDown(stats)) {
                        val bounds = BoardRegion(
                            columnRegion.left,
                            y,
                            columnRegion.right,
                            (y + cardHeight).coerceAtMost(columnRegion.bottom)
                        )
                        cards += Card(Rank.Ace, Suit.Clubs, faceUp = false, known = false)
                        locs += locator.toCardLocation(
                            PileRef.Tableau(col),
                            faceDownCount,
                            bounds
                        )
                        recognizedSlots += RecognizedSlot(
                            pile = PileRef.Tableau(col),
                            index = faceDownCount,
                            bounds = bounds,
                            engine = SlotGuess(SlotKind.FaceDown),
                            confidence = 0.85f,
                            diagnostic = "face-down",
                            inferred = false
                        )
                        faceDownCount++
                    }
                    y += downStep
                }

                val hit = recognizeCached(
                    bitmap = bitmap,
                    pile = PileRef.Tableau(col),
                    index = 0,
                    region = faceRegion,
                    cache = localCache,
                    stats = stats
                )
                val inkRed = hit.inferredRed ?: hit.card?.suit?.isRed
                var card = cardFromHit(hit) ?: Card(
                    Rank.Ace,
                    Suit.Clubs,
                    faceUp = true,
                    known = false
                )
                var slotTrace = hit.trace
                val (resolvedCard, resolvedTrace) = resolveCardSuitWithTrace(
                    bitmap,
                    faceRegion,
                    card,
                    slotTrace
                )
                card = resolvedCard
                slotTrace = resolvedTrace

                // Reconstruct the overlapped face-up run geometrically. Sampling
                // colored header strips missed leading cards in long cascades;
                // card spacing is fixed and every legal tableau run descends
                // while alternating color.
                val faceUpStep = cardHeight * board.profile.faceUpOverlap
                var firstFaceTop = columnRegion.top + faceDownCount * downStep
                var leadingHit: RecognitionHit? = null
                var leadingCard: Card? = null
                // Diagnostic-only: which condition stopped the face-down scan,
                // and the stats it stopped on. A boundary strip that is really
                // still face-down but gets misread as face-up here shifts every
                // faceUp index below it by one, without the count logic further
                // down ever seeing anything wrong.
                var boundaryBreakReason = "ran-out-of-room"
                // A narrow teal header can be diluted by a white border and miss
                // the primary face-down test. Do not infer a face-up card there.
                while (firstFaceTop + downStep * 0.25f < faceRegion.top) {
                    val boundaryStrip = BoardRegion(
                        columnRegion.left,
                        firstFaceTop,
                        columnRegion.right,
                        firstFaceTop + downStep * 0.45f
                    )
                    val boundaryStats = SmashColorAnalyzer.analyze(bitmap, boundaryStrip)
                    if (!SmashColorAnalyzer.looksFaceDown(boundaryStats)) {
                        boundaryBreakReason = "not-face-down@faceDownCount=$faceDownCount:" +
                            "teal=${"%.3f".format(boundaryStats.tealRatio)}," +
                            "white=${"%.3f".format(boundaryStats.whiteRatio)}," +
                            "red=${"%.3f".format(boundaryStats.redInkRatio)}," +
                            "black=${"%.3f".format(boundaryStats.blackInkRatio)}"
                        break
                    }
                    val bounds = BoardRegion(
                        columnRegion.left,
                        firstFaceTop,
                        columnRegion.right,
                        (firstFaceTop + cardHeight).coerceAtMost(columnRegion.bottom)
                    )
                    // A full cardHeight-tall peek reaches well past the next
                    // downStep-wide slice - a real device log showed this
                    // picking up a confidently-recognized card several slices
                    // below firstFaceTop (e.g. bottomTop 21-29px away vs a
                    // ~193px-tall peek) and wrongly treating THIS position as
                    // its start, converting a still-face-down card into a
                    // phantom face-up one. Cap the peek at one downStep so a
                    // hit here can only belong to a card actually exposed at
                    // this position.
                    val peekBounds = BoardRegion(
                        columnRegion.left,
                        firstFaceTop,
                        columnRegion.right,
                        (firstFaceTop + downStep).coerceAtMost(columnRegion.bottom)
                    )
                    val boundaryHit = recognizeCached(
                        bitmap = bitmap,
                        pile = PileRef.Tableau(col),
                        index = 100 + faceDownCount,
                        region = peekBounds,
                        cache = localCache,
                        stats = stats
                    )
                    if (boundaryStats.whiteRatio > 0.12f &&
                        !boundaryHit.isFaceDown &&
                        !boundaryHit.isEmpty
                    ) {
                        boundaryBreakReason = "white-override@faceDownCount=$faceDownCount:" +
                            "white=${"%.3f".format(boundaryStats.whiteRatio)}," +
                            "hit=${boundaryHit.diagnostic}"
                        break
                    }
                    cards += Card(Rank.Ace, Suit.Clubs, faceUp = false, known = false)
                    locs += locator.toCardLocation(
                        PileRef.Tableau(col),
                        cards.lastIndex,
                        bounds
                    )
                    recognizedSlots += RecognizedSlot(
                        pile = PileRef.Tableau(col),
                        index = cards.lastIndex,
                        bounds = bounds,
                        engine = SlotGuess(SlotKind.FaceDown),
                        confidence = 0.80f,
                        diagnostic = "face-down-boundary",
                        inferred = false
                    )
                    faceDownCount++
                    firstFaceTop += downStep
                }
                val faceUpDistance = (faceRegion.top - firstFaceTop) / faceUpStep
                var faceUpCount = (faceUpDistance.roundToInt() + 1)
                    .coerceIn(1, Rank.entries.size)
                val geometricFaceUpCount = faceUpCount
                var cascadeRankCountNote = "n/a"
                // True when the bottom card (measured directly from faceRegion) and
                // the leading/most-covered card (read independently at firstFaceTop)
                // agree on exactly how many cards this run should have - a
                // doubly-anchored geometric consensus, not just one card's own
                // confidence score. See its use below: a weak middle-of-run read
                // that contradicts this consensus is more likely wrong than the
                // consensus is.
                var rankCountConsistent = false
                if (card.known) {
                    val leadingRegion = BoardRegion(
                        columnRegion.left,
                        firstFaceTop,
                        columnRegion.right,
                        (firstFaceTop + cardHeight).coerceAtMost(columnRegion.bottom)
                    )
                    // Only the top faceUpStep sliver of leadingRegion is actually
                    // this card — the rest is covered by the card(s) stacked on
                    // top of it. Feed the ink-color read only that visible strip
                    // so the covering card's color can't leak into inkRed.
                    //
                    // leadingRegion itself stays full cardHeight: a fifth
                    // attempt trimmed it down (matching leadingHeaderRegion)
                    // and a real device log showed FaceUp->FaceDown mismatches
                    // spike (Occupancy 9->29, Missing 1->18) - the coarse
                    // looksFaceDown/looksEmpty color gates in recognize() run
                    // on this same region's stats, and a few pixels of teal
                    // bleed from the covering card at the boundary is a much
                    // larger fraction of a ~44px strip than of a ~193px card,
                    // tipping genuinely face-up cards into the face-down gate.
                    // trimmedToVisibleStrip (passed below) tells recognize() to
                    // derive its own smaller sub-crop internally, from
                    // leadingRegion's already-correct crop, using inkRegion's
                    // proportions - narrower fix than trimming the region the
                    // color gates see.
                    val leadingHeaderRegion = BoardRegion(
                        columnRegion.left,
                        firstFaceTop,
                        columnRegion.right,
                        (firstFaceTop + faceUpStep * 0.9f)
                            .coerceAtMost(columnRegion.bottom)
                    )
                    val leadingHitResult = recognizeCached(
                        bitmap = bitmap,
                        pile = PileRef.Tableau(col),
                        index = 200,
                        region = leadingRegion,
                        cache = localCache,
                        exactCardBounds = true,
                        inkRegion = leadingHeaderRegion,
                        trimmedToVisibleStrip = true,
                        stats = stats
                    )
                    leadingHit = leadingHitResult
                    leadingCard = leadingHitResult.card
                    val leadingHeaderStats = SmashColorAnalyzer.analyze(
                        bitmap,
                        leadingHeaderRegion
                    )
                    val expectedLeadingRed =
                        if ((faceUpCount - 1) % 2 == 0) {
                            card.suit.isRed
                        } else {
                            !card.suit.isRed
                        }
                    val leadingRed = when {
                        leadingHeaderStats.redInkRatio >
                            leadingHeaderStats.blackInkRatio * 1.20f -> true
                        leadingHeaderStats.blackInkRatio >
                            leadingHeaderStats.redInkRatio * 1.20f -> false
                        else -> leadingHit?.inferredRed ?: leadingCard?.suit?.isRed
                    }
                    val fractionalFaceUp = faceUpDistance - faceUpDistance.toInt()
                    if (leadingRed != null &&
                        leadingRed != expectedLeadingRed &&
                        card.rank.value + faceUpCount <= Rank.King.value &&
                        (faceUpCount <= 1 || faceUpCount >= 4 || fractionalFaceUp >= 0.55f)
                    ) {
                        faceUpCount++
                    }
                    val resolvedLeading = leadingCard
                    if (resolvedLeading?.known == true &&
                        resolvedLeading.rank.value >= card.rank.value
                    ) {
                        val rankCount = resolvedLeading.rank.value - card.rank.value + 1
                        val countDifference = rankCount - faceUpCount
                        cascadeRankCountNote =
                            "leadingRank=${resolvedLeading.rank.name},rankCount=$rankCount," +
                                "diff=$countDifference,applied=${countDifference == 2}"
                        if (rankCount in 1..Rank.entries.size &&
                            countDifference == 2
                        ) {
                            faceUpCount = rankCount
                        }
                        rankCountConsistent = rankCount == faceUpCount
                    } else {
                        cascadeRankCountNote = "leadingUnknown"
                    }
                }
                // Diagnostic-only: geometric faceUpCount is a real-pixel-distance
                // divided by an assumed per-card step, which can drift over a long
                // cascade and land every slot below the drift point on the wrong
                // physical card. Surface the raw numbers so a real miscount can be
                // told apart from a genuine recognition miss. Attached to every
                // slot in the column (not just the bottom anchor) since the
                // anchor itself is usually the one read correctly and a mismatch
                // elsewhere in the column would otherwise carry none of this.
                val cascadeDiagnosticPost =
                    "cascade:firstFaceTop=${"%.1f".format(firstFaceTop)}," +
                        "bottomTop=${"%.1f".format(faceRegion.top)}," +
                        "faceUpStep=${"%.2f".format(faceUpStep)}," +
                        "geomCount=$geometricFaceUpCount,finalCount=$faceUpCount," +
                        "bottomRank=${card.rank.name},$cascadeRankCountNote," +
                        "boundary=[$boundaryBreakReason]"
                slotTrace = slotTrace.withPost(cascadeDiagnosticPost)
                for (exposedIndex in 0 until faceUpCount - 1) {
                    val distanceFromBottom = faceUpCount - 1 - exposedIndex
                    val geometricFallback = TableauCascadeSupport.geometricCascadeCard(
                        bottomCard = card,
                        distanceFromBottom = distanceFromBottom
                    )
                    val top = firstFaceTop + exposedIndex * faceUpStep
                    val bounds = BoardRegion(
                        columnRegion.left,
                        top,
                        columnRegion.right,
                        (top + cardHeight).coerceAtMost(columnRegion.bottom)
                    )
                    // Same overlap problem as the leading card: bounds reaches
                    // down through cardHeight, but everything past the next
                    // card's header start is that next card's face, not this
                    // one's. Keep `bounds` (full cardHeight) as the region
                    // recognizeCached sees - trimming it made the coarse
                    // looksFaceDown color gate misfire on genuinely face-up
                    // cards (see the leading-card comment above). Only
                    // trimmedToVisibleStrip + inkRegion tell recognize() to
                    // build its own smaller sub-crop internally for rank
                    // matching specifically.
                    val headerRegion = BoardRegion(
                        columnRegion.left,
                        top,
                        columnRegion.right,
                        (top + faceUpStep * 0.9f).coerceAtMost(columnRegion.bottom)
                    )
                    val precomputedHit = if (exposedIndex == 0) leadingHit else null
                    val slotHit = precomputedHit ?: recognizeCached(
                        bitmap = bitmap,
                        pile = PileRef.Tableau(col),
                        index = 300 + col * 16 + exposedIndex,
                        region = bounds,
                        cache = localCache,
                        exactCardBounds = true,
                        inkRegion = headerRegion,
                        trimmedToVisibleStrip = true,
                        stats = stats
                    )
                    val slotCard = cardFromHit(slotHit) ?: slotHit.card
                    val headerStats = SmashColorAnalyzer.analyze(bitmap, headerRegion)
                    val inkSaysRed = headerStats.redInkRatio >
                        headerStats.blackInkRatio * 1.20f
                    val inkSaysBlack = headerStats.blackInkRatio >
                        headerStats.redInkRatio * 1.20f
                    val inkDisagreesWithDirectSuit = slotCard?.known == true && (
                        (inkSaysBlack && slotCard.suit.isRed) ||
                            (inkSaysRed && !slotCard.suit.isRed)
                        )
                    val prefersGeometric = TableauCascadeSupport.prefersGeometricOverDirectRead(
                        bottomCard = card,
                        bottomReadConfidence = hit.confidence,
                        geometric = geometricFallback,
                        directCard = slotCard,
                        directConfidence = slotHit.confidence,
                        rankCountConsistent = rankCountConsistent,
                        inkDisagreesWithDirectSuit = inkDisagreesWithDirectSuit
                    )
                    val (cascadeCard, cascadeTrace, cascadeDiagnostic, cascadeConfidence, cascadeInferred) =
                        if (TableauCascadeSupport.isReliableRead(slotHit, slotCard) &&
                            !prefersGeometric
                        ) {
                            // Independent crop from whatever region it's given;
                            // locateBadge searches adaptively for the pip
                            // rather than a fixed proportion, so route it
                            // through the same trimmed strip too.
                            val (resolved, trace) = resolveCardSuitWithTrace(
                                bitmap,
                                headerRegion,
                                requireNotNull(slotCard),
                                slotHit.trace
                            )
                            ResolvedCascadeSlot(
                                card = resolved.copy(inferred = false),
                                trace = trace.withPost(
                                    "$cascadeDiagnosticPost,exposedIndex=$exposedIndex," +
                                        "distanceFromBottom=$distanceFromBottom"
                                ),
                                diagnostic = slotHit.diagnostic,
                                confidence = slotHit.confidence,
                                inferred = false
                            )
                        } else if (prefersGeometric && geometricFallback.known) {
                            // Geometry beat a direct mid-run read. Must stay
                            // inferred=false — GoldenTruthEvaluator ignores
                            // inferred slots, so marking these inferred made
                            // every geometric override invisible to Evaluate
                            // (and let neighbors steal truth matches → new
                            // rank errors). Re-score suit in the geometric
                            // color family; rejected red/black templates from
                            // a color-flip read must not leave the Spades/
                            // Hearts placeholder as a fake "known" suit.
                            val enriched = enrichGeometricCascadeSuit(
                                bitmap = bitmap,
                                headerRegion = headerRegion,
                                geometric = geometricFallback,
                                rejectedHit = slotHit
                            )
                            ResolvedCascadeSlot(
                                card = enriched.copy(inferred = false),
                                trace = slotHit.trace.withPost(
                                    "geom-override:direct=${slotCard?.id}," +
                                        "conf=${"%.2f".format(slotHit.confidence)}," +
                                        "to=${enriched.id}"
                                ),
                                diagnostic = "geom-override:${slotHit.diagnostic}",
                                confidence = slotHit.confidence.coerceAtMost(0.72f),
                                inferred = false
                            )
                        } else {
                            // Keep the real attempt's own rank/suit/post trace instead
                            // of discarding it to RecognitionTrace.EMPTY. Previously a
                            // card that failed isReliableRead left zero information
                            // about why - "inferred-cascade" masked whatever rank/suit
                            // read and confidence actually got rejected, making these
                            // cases undebuggable from analysis.log alone. Golden
                            // matching still excludes inferred=true slots entirely
                            // (GoldenTruthEvaluator.findMatchingSlot), so surfacing
                            // this cannot change what gets scored - it only makes a
                            // rejected read visible instead of invisible.
                            val enriched = TableauCascadeSupport.enrichGeometricFromRejectedRead(
                                geometricFallback,
                                slotHit
                            )
                            ResolvedCascadeSlot(
                                card = enriched,
                                trace = slotHit.trace.withPost(
                                    "rejected:known=${slotCard?.known}," +
                                        "conf=${"%.2f".format(slotHit.confidence)}"
                                ),
                                diagnostic = "inferred-cascade:${slotHit.diagnostic}",
                                confidence = if (enriched.known) 0.55f else 0.20f,
                                inferred = true
                            )
                        }
                    cards += cascadeCard
                    locs += locator.toCardLocation(
                        PileRef.Tableau(col),
                        cards.lastIndex,
                        bounds
                    )
                    recognizedSlots += if (cascadeInferred) {
                        RecognizedSlot(
                            pile = PileRef.Tableau(col),
                            index = cards.lastIndex,
                            bounds = bounds,
                            engine = slotGuessFromCard(cascadeCard),
                            confidence = cascadeConfidence,
                            diagnostic = cascadeDiagnostic,
                            trace = cascadeTrace,
                            inferred = true
                        )
                    } else {
                        recognizedSlot(
                            pile = PileRef.Tableau(col),
                            index = cards.lastIndex,
                            bounds = bounds,
                            hit = slotHit,
                            cardOverride = cascadeCard,
                            trace = cascadeTrace,
                            inferred = false,
                            diagnostic = cascadeDiagnostic,
                            confidence = cascadeConfidence
                        )
                    }
                }
                val cardAbove = cards.lastOrNull()
                val repairedBottom = TableauCascadeSupport.repairIllegalBottom(
                    cardAbove = cardAbove,
                    bottom = card,
                    bottomConfidence = hit.confidence,
                    bottomHit = hit
                )
                if (repairedBottom !== card && repairedBottom.id != card.id) {
                    card = repairedBottom
                    slotTrace = slotTrace.withPost(
                        "bottom-repair:above=${cardAbove?.id},from=${hit.card?.id},to=${card.id}"
                    )
                }
                cards += card
                locs += locator.toCardLocation(PileRef.Tableau(col), cards.lastIndex, faceRegion)
                recognizedSlots += recognizedSlot(
                    pile = PileRef.Tableau(col),
                    index = cards.lastIndex,
                    bounds = faceRegion,
                    hit = hit,
                    cardOverride = card,
                    trace = slotTrace
                )
                val firstFaceUpIndex = cards.indexOfFirst { it.faceUp }
                if (firstFaceUpIndex >= 0) {
                    val promoted = TableauCascadeSupport.promoteTrustedRun(
                        cards.drop(firstFaceUpIndex)
                    )
                    if (promoted != cards.drop(firstFaceUpIndex)) {
                        repeat(cards.size - firstFaceUpIndex) { cards.removeAt(cards.lastIndex) }
                        cards.addAll(promoted)
                    }
                }
                if (!card.known) diagnostics += "tableau$col[top]=${hit.diagnostic}"
            } else {
                val emptyBounds = BoardRegion(
                    columnRegion.left,
                    columnRegion.top,
                    columnRegion.right,
                    (columnRegion.top + cardHeight).coerceAtMost(columnRegion.bottom)
                )
                recognizedSlots += RecognizedSlot(
                    pile = PileRef.Tableau(col),
                    index = 0,
                    bounds = emptyBounds,
                    engine = SlotGuess(SlotKind.Empty),
                    confidence = 0.80f,
                    diagnostic = "empty-column",
                    inferred = false
                )
            }
            faceRegion?.let {
                diagnostics += "tableau$col.region=${"%.1f".format(it.top)}-${"%.1f".format(it.bottom)}"
            }
            val summary = cards.joinToString(",") { c ->
                when {
                    !c.faceUp -> "D"
                    c.known -> c.id
                    else -> "U"
                }
            }
            diagnostics += "tableau$col=$summary"
            return TableauColumnResult(
                col = col,
                cards = cards,
                locations = locs,
                diagnostics = diagnostics,
                recognizedSlots = recognizedSlots,
                cacheEntries = localCache,
                stats = stats,
                elapsedNanos = System.nanoTime() - columnStartNanos
            )
        }

        recognizer.ensureLoaded()
        // Start foundations + tableau before waste templates so those reads
        // overlap the sequential waste fusion work instead of waiting on it.
        val foundationFutures = locator.foundationRegions(board).mapIndexed { index, region ->
            columnExecutor.submit(Callable { computeFoundation(index, region) })
        }
        val columnFutures = locator.tableauColumnRegions(board).mapIndexed { col, columnRegion ->
            columnExecutor.submit(Callable { computeColumn(col, columnRegion) })
        }

        val tightWasteRegion = locateWasteTopRegion(bitmap, board)
        val tightWasteHit = recognizeCached(
            bitmap = bitmap,
            pile = PileRef.Waste,
            index = 0,
            region = tightWasteRegion,
            cache = newSlotCache,
            exactCardBounds = true
        )
        val legacyWasteRegion = locator.wasteTopRegion(board)
        val needsLegacyFusion =
            tightWasteHit.card == null ||
                tightWasteHit.confidence < 0.68f ||
                tightWasteHit.card?.suitAmbiguous == true
        val legacyWasteHit = if (needsLegacyFusion) {
            recognizeCached(
                bitmap = bitmap,
                pile = PileRef.Waste,
                index = 1,
                region = legacyWasteRegion,
                cache = newSlotCache
            )
        } else {
            tightWasteHit
        }
        val exactRankScores = tightWasteHit.rankScores
            ?: recognizer.exactRankTemplateScores(
                bitmap,
                tightWasteRegion,
                Rank.entries.toSet()
            )
        val exactSuitScores = tightWasteHit.suitScores
            ?: recognizer.suitTemplateScores(
                bitmap,
                tightWasteRegion,
                Suit.entries.toSet()
            )
        // The legacy crop has the most rank training data, while the complete
        // tight crop is the only one that reliably contains the suit badge.
        // Fuse them, correcting the two common clipped-glyph confusions with
        // the wide/closed center-glyph shape.
        val legacyCard = legacyWasteHit.card
        val tightCard = tightWasteHit.card
        val wasteCandidatesDisagree =
            tightCard != null &&
                legacyCard != null &&
                tightCard.id != legacyCard.id
        val baseCard = when {
            wasteCandidatesDisagree -> {
                fun candidateScore(card: Card): Float {
                    val rankScore = exactRankScores[card.rank] ?: 0f
                    val suitScore = exactSuitScores[card.suit] ?: 0f
                    return rankScore + suitScore
                }
                if (candidateScore(tightCard) >= candidateScore(legacyCard)) {
                    tightCard
                } else {
                    legacyCard
                }
            }
            else -> legacyCard ?: tightCard
        }
        val rankedExactSuits = exactSuitScores.entries.sortedByDescending { it.value }
        val exactSuitBest = rankedExactSuits.firstOrNull()
        val exactSuitSecond = rankedExactSuits.getOrNull(1)?.value ?: 0f
        val authoritativeExactSuit = exactSuitBest
            ?.takeIf { it.value >= 0.80f && it.value - exactSuitSecond >= 0.04f }
            ?.key
        val wasteInkGuess = inkGuessFromRegion(bitmap, tightWasteRegion)
        // Used to gate on legacyCard/tightCard's rank already being in
        // WASTE_OCR_RELEVANT_RANKS, on the assumption a wrong initial guess
        // would always land on one of those ranks. A real game showed a
        // waste Ten confidently misread as Eight — not in that set — so OCR
        // never ran and the correction logic below (which depends entirely
        // on ocrRank) never got a chance. Any rank can be the wrong initial
        // guess; the early-exit added to attemptWasteRankOcr already bounds
        // the cost of trying, so just always try when there's a real card.
        val wasteOcrRelevant = legacyCard != null || tightCard != null
        val wasteOcrStartNanos = System.nanoTime()
        val wasteOcrRegions = if (wasteOcrRelevant) {
            buildList {
                add(tightWasteRegion)
                add(legacyWasteRegion)
                addAll(locator.wasteOcrCardRegions(board))
            }
        } else {
            emptyList()
        }
        val ocrFingerprint = wasteOcrRegions.fold(0L) { acc, region ->
            acc * 31L + regionFingerprint(bitmap, region)
        }
        val reusableWasteOcr = if (wasteOcrRelevant) {
            cachedWasteOcr?.takeIf { cached ->
                cached.fingerprint == ocrFingerprint &&
                    cached.regions.size == wasteOcrRegions.size &&
                    cached.regions.zip(wasteOcrRegions).all { (a, b) -> regionsSimilar(a, b) }
            }
        } else {
            null
        }
        // Columns/foundations already started above. Kick OCR now so a fresh
        // waste read (the 47–430ms ML Kit path) overlaps whatever pile work
        // is still running. Cached waste is still free.
        val wasteOcrFuture = if (wasteOcrRelevant && reusableWasteOcr == null) {
            wasteOcrExecutor.submit<RankCornerOcr.AttemptResult> {
                recognizer.attemptWasteRankOcr(bitmap, wasteOcrRegions)
            }
        } else {
            null
        }
        fun finishWaste(wasteOcrAttempt: RankCornerOcr.AttemptResult?): Pair<RecognitionHit, List<Card>> {
        val wasteOcrOverride = WasteRankCorrections.ocrRankOverride(
            ocrRank = wasteOcrAttempt?.guess?.rank,
            legacyCard = legacyCard,
            tightCard = tightCard,
            baseCard = baseCard,
            exactRankScores = exactRankScores
        )
        val wasteQueenOverride = WasteRankCorrections.correctQueenOnWaste(
            legacyCard = legacyCard,
            tightCard = tightCard,
            exactRankScores = exactRankScores,
            inkGuess = wasteInkGuess
        )
        val wasteKingOverride = WasteRankCorrections.correctKingTenOnWaste(
            legacyCard = legacyCard,
            tightCard = tightCard,
            exactRankScores = exactRankScores,
            inkGuess = wasteInkGuess
        )
        val wasteFiveJackOverride = WasteRankCorrections.correctFiveJack(
            legacyCard = legacyCard,
            tightCard = tightCard,
            baseCard = baseCard,
            exactRankScores = exactRankScores,
            inkGuess = wasteInkGuess,
            ocrRank = wasteOcrAttempt?.guess?.rank
        )
        val exactEightSpadeOverride =
            legacyCard?.rank == Rank.Seven &&
                tightCard?.rank == Rank.Six &&
                (exactRankScores[Rank.Eight] ?: 0f) >= 0.90f &&
                (exactSuitScores[Suit.Spades] ?: 0f) >= 0.90f
        val exactFourOverride =
            legacyCard?.rank == Rank.Seven &&
                (exactRankScores[Rank.Four] ?: 0f) >= 0.85f &&
                (exactSuitScores[Suit.Diamonds] ?: 0f) >= 0.90f &&
                (exactSuitScores[Suit.Diamonds] ?: 0f) >
                (exactSuitScores[Suit.Hearts] ?: 0f) + 0.05f
        val wasteThreeOverride = WasteRankCorrections.correctJackThree(
            legacyCard = legacyCard,
            tightCard = tightCard,
            baseCard = baseCard,
            exactRankScores = exactRankScores,
            inkGuess = wasteInkGuess
        )
        val wasteSixOverride = WasteRankCorrections.correctSixOnWaste(
            legacyCard = legacyCard,
            tightCard = tightCard,
            baseCard = baseCard,
            exactRankScores = exactRankScores,
            ocrRank = wasteOcrAttempt?.guess?.rank
        )
        val wasteEightOverride = WasteRankCorrections.correctEightOnWaste(
            legacyCard = legacyCard,
            tightCard = tightCard,
            baseCard = baseCard,
            exactRankScores = exactRankScores,
            inkGuess = wasteInkGuess,
            ocrRank = wasteOcrAttempt?.guess?.rank
        )
        val correctedRank = when {
            wasteEightOverride != null -> wasteEightOverride
            wasteOcrOverride != null -> wasteOcrOverride
            // Prefer fuller-crop Nine over tight/OCR Six magnet before
            // correctSixOnWaste / base fusion can re-pick Six (v1.4.87 left
            // 114135×3 as fused-Six after OCR override went null).
            legacyCard?.rank == Rank.Nine &&
                (tightCard?.rank == Rank.Six ||
                    wasteOcrAttempt?.guess?.rank == Rank.Six) -> Rank.Nine
            // 190337: tight Nine, legacy Four, OCR 6 → keep Nine.
            tightCard?.rank == Rank.Nine &&
                legacyCard?.rank == Rank.Four &&
                wasteOcrAttempt?.guess?.rank == Rank.Six -> Rank.Nine
            wasteSixOverride != null -> wasteSixOverride
            wasteQueenOverride != null -> wasteQueenOverride
            wasteKingOverride != null -> wasteKingOverride
            exactEightSpadeOverride -> Rank.Eight
            exactFourOverride -> Rank.Four
            wasteFiveJackOverride != null -> wasteFiveJackOverride
            wasteThreeOverride != null -> wasteThreeOverride
            legacyCard?.rank == Rank.Seven &&
                tightCard?.rank == Rank.Jack &&
                (exactRankScores[Rank.Jack] ?: 0f) >
                (exactRankScores[Rank.Seven] ?: 0f) + 0.35f -> Rank.Jack
            legacyCard?.rank == Rank.Nine &&
                tightCard?.rank == Rank.Ten &&
                (exactRankScores[Rank.Ten] ?: 0f) >= 0.85f &&
                (exactRankScores[Rank.Ten] ?: 0f) >
                (exactRankScores[Rank.Nine] ?: 0f) + 0.10f -> Rank.Ten
            legacyCard?.rank == Rank.Seven &&
                tightCard?.rank == Rank.Eight &&
                (exactRankScores[Rank.Eight] ?: 0f) >= 0.85f &&
                (exactRankScores[Rank.Eight] ?: 0f) >
                (exactRankScores[Rank.Seven] ?: 0f) + 0.10f -> Rank.Eight
            legacyCard?.rank == Rank.Seven &&
                tightCard?.rank == Rank.Six &&
                tightCard.suit != legacyCard.suit &&
                (exactRankScores[Rank.Eight] ?: 0f) < 0.48f -> Rank.Six
            legacyCard?.rank == Rank.Jack &&
                tightCard?.rank == Rank.Four &&
                legacyWasteHit.confidence >= 0.68f -> Rank.Jack
            legacyCard?.rank == Rank.Six &&
                tightCard?.rank == Rank.Four &&
                legacyWasteHit.confidence >= 0.68f -> Rank.Six
            legacyCard != null &&
                tightCard != null &&
                legacyCard.rank != tightCard.rank &&
                legacyWasteHit.confidence >= 0.70f &&
                legacyWasteHit.confidence >= tightWasteHit.confidence &&
                (exactRankScores[legacyCard.rank] ?: legacyWasteHit.confidence) >=
                (exactRankScores[tightCard.rank] ?: tightWasteHit.confidence) - 0.12f ->
                legacyCard.rank
            else -> baseCard?.rank
        }
        val correctedSuit = if ((wasteQueenOverride != null || wasteKingOverride != null ||
                exactEightSpadeOverride) &&
            authoritativeExactSuit != null
        ) {
            authoritativeExactSuit
        } else if (exactFourOverride) {
            Suit.Diamonds
        } else {
            tightCard?.suit ?: baseCard?.suit
        }
        val wasteBlackSuitOverride = correctedRank?.let { rank ->
            WasteRankCorrections.correctBlackSuitOnWaste(
                rank = rank,
                legacyCard = legacyCard,
                tightCard = tightCard
            )
        } ?: WasteRankCorrections.preferWasteExactBlackSuit(
            fusedSuit = correctedSuit ?: tightCard?.suit ?: baseCard?.suit,
            fusedAmbiguous = baseCard?.suitAmbiguous == true,
            exactBest = authoritativeExactSuit,
            exactBestScore = exactSuitBest?.value ?: 0f,
            exactSecondScore = exactSuitSecond
        )
        val finalCorrectedSuit = wasteBlackSuitOverride ?: correctedSuit
        val (fusedCard, fusionPostTrace) = if (baseCard != null && correctedRank != null) {
            val candidate = baseCard.copy(
                rank = correctedRank,
                suit = finalCorrectedSuit ?: baseCard.suit,
                suitAmbiguous = wasteBlackSuitOverride == null && baseCard.suitAmbiguous
            )
            if (wasteBlackSuitOverride != null) {
                candidate to RecognitionTrace.EMPTY.withPost(
                    "waste-black-suit:${correctedSuit ?: baseCard.suit}->$wasteBlackSuitOverride"
                )
            } else {
                resolveCardSuitWithTrace(
                    bitmap,
                    tightWasteRegion,
                    candidate,
                    RecognitionTrace.EMPTY
                )
            }
        } else {
            synthesizeWasteFromScores(
                bitmap = bitmap,
                legacyRegion = legacyWasteRegion,
                tightRegion = tightWasteRegion,
                legacyHit = legacyWasteHit,
                tightHit = tightWasteHit,
                rankScores = exactRankScores,
                suitScores = exactSuitScores
            )?.let { synthesized ->
                resolveCardSuitWithTrace(
                    bitmap,
                    tightWasteRegion,
                    synthesized,
                    RecognitionTrace.EMPTY
                )
            } ?: (null to RecognitionTrace.EMPTY)
        }
        val wasteHit = if (fusedCard != null) {
            val fromFusion = baseCard != null && correctedRank != null
            RecognitionHit(
                card = fusedCard,
                confidence = maxOf(
                    legacyWasteHit.confidence,
                    tightWasteHit.confidence,
                    exactRankScores[fusedCard.rank] ?: 0f
                ).coerceAtLeast(0.55f),
                isFaceDown = false,
                isEmpty = false,
                diagnostic = if (fromFusion) {
                    "fused-${fusedCard.rank.name}-${fusedCard.suit.name}"
                } else {
                    "synthesized-${fusedCard.rank.name}-${fusedCard.suit.name}"
                },
                inferredRed = fusedCard.suit.isRed,
                trace = if (fromFusion) {
                    RecognitionTrace(
                        rankSource = "waste-fusion",
                        rankScore = exactRankScores[correctedRank] ?: legacyWasteHit.trace.rankScore,
                        rankTemplates = RecognitionTrace.formatRankScores(exactRankScores),
                        suitSource = "waste-fusion",
                        suitScore = exactSuitBest?.value,
                        suitTemplates = RecognitionTrace.formatSuitScores(exactSuitScores),
                        postSteps = buildList {
                            add("waste-fusion:legacy=${legacyCard?.id},tight=${tightCard?.id}")
                            wasteOcrAttempt?.trace?.let { add(it) }
                            if (wasteOcrOverride != null) {
                                add("waste-ocr-rank:${wasteOcrOverride.name}")
                            }
                            if (wasteBlackSuitOverride != null) {
                                add("waste-black-suit:${wasteBlackSuitOverride.name}")
                            }
                        }
                    ).merge(legacyWasteHit.trace).merge(tightWasteHit.trace).merge(fusionPostTrace)
                } else {
                    RecognitionTrace(
                        rankSource = "waste-scores",
                        rankScore = exactRankScores[fusedCard.rank],
                        rankTemplates = RecognitionTrace.formatRankScores(exactRankScores),
                        suitSource = "waste-scores",
                        suitScore = exactSuitScores[fusedCard.suit],
                        suitTemplates = RecognitionTrace.formatSuitScores(exactSuitScores)
                    ).merge(fusionPostTrace)
                }
            )
        } else {
            legacyWasteHit
        }
        val wasteRegion = if (fusedCard != null) tightWasteRegion else legacyWasteRegion
        locations[PileRef.Waste] = listOf(
            locator.toCardLocation(PileRef.Waste, 0, wasteRegion)
        )
        recognizedSlots += recognizedSlot(
            pile = PileRef.Waste,
            index = 0,
            bounds = wasteRegion,
            hit = wasteHit,
            cardOverride = wasteHit.card
        )
        val wasteCards = listOfNotNull(cardFromHit(wasteHit))
        diagnostics += "waste=${wasteHit.diagnostic}:${wasteHit.card}"
        diagnostics +=
            "waste-regions=exact:${"%.0f".format(tightWasteRegion.left)}-" +
            "${"%.0f".format(tightWasteRegion.right)}:${tightWasteHit.diagnostic}," +
            "legacy:${"%.0f".format(legacyWasteRegion.left)}-" +
            "${"%.0f".format(legacyWasteRegion.right)}:${legacyWasteHit.diagnostic}"
        diagnostics += "waste-rank-scores=$exactRankScores"
        diagnostics += "waste-suit-scores=$exactSuitScores"
        if (wasteCandidatesDisagree) {
            diagnostics +=
                "waste-disagreement=tight:${tightWasteHit.card?.id}," +
                "legacy:${legacyWasteHit.card?.id}"
        }
        wasteOcrAttempt?.trace?.let { diagnostics += it }
        if (wasteOcrOverride != null) {
            diagnostics += "waste-ocr-rank=${wasteOcrOverride.name}"
        }
        return wasteHit to wasteCards
        }

        val foundationResults = foundationFutures.map { it.get() }
        val columnResults = columnFutures.map { it.get() }
        foundationResults.sortedBy { it.index }.forEach { result ->
            foundations[result.index] = result.cards
            locations[PileRef.Foundation(result.index)] = listOf(result.location)
            recognizedSlots += result.recognizedSlot
            newSlotCache.putAll(result.cacheEntries)
            recognizeCacheHits += result.stats.hits
            recognizeCacheMisses += result.stats.misses
            recognizeMissNanos += result.stats.missNanos
            missNoPriorEntry += result.stats.missNoPrior
            missFingerprintChanged += result.stats.missFingerprint
            missBoundsShifted += result.stats.missBounds
            diagnostics += result.diagnostic
        }
        columnResults.forEach { result ->
            tableau[result.col] = result.cards
            locations[PileRef.Tableau(result.col)] = result.locations
            diagnostics += result.diagnostics
            recognizedSlots += result.recognizedSlots
            newSlotCache.putAll(result.cacheEntries)
            recognizeCacheHits += result.stats.hits
            recognizeCacheMisses += result.stats.misses
            recognizeMissNanos += result.stats.missNanos
            missNoPriorEntry += result.stats.missNoPrior
            missFingerprintChanged += result.stats.missFingerprint
            missBoundsShifted += result.stats.missBounds
            tableauColumnNanos[result.col] = result.elapsedNanos
        }
        val wasteOcrAttempt = when {
            !wasteOcrRelevant -> null
            reusableWasteOcr != null -> reusableWasteOcr.result
            else -> wasteOcrFuture!!.get().also { result ->
                cachedWasteOcr = CachedWasteOcr(ocrFingerprint, wasteOcrRegions, result)
            }
        }
        diagnostics += "wasteOcr:relevant=$wasteOcrRelevant," +
            "ms=${(System.nanoTime() - wasteOcrStartNanos) / 1_000_000}," +
            "overlapped=${wasteOcrFuture != null}"
        val (wasteHit, wasteCards) = finishWaste(wasteOcrAttempt)

        val wasteConfidence = if (wasteCandidatesDisagree) {
            wasteHit.confidence.coerceAtMost(0.70f)
        } else {
            wasteHit.confidence
        }
        val confidences = mutableListOf(board.confidence, stockHit.confidence, wasteConfidence)
        val avg = confidences.average().toFloat()
        val totalCards = tableau.sumOf { it.size } + foundations.sumOf { it.size } +
            wasteCards.size + stockCards.size
        val screenSignals = SmashPlayScreenGate.analyze(bitmap, board, locator)
        val livePlayScreen = isLivePlayScreen(
            bitmap = bitmap,
            board = board,
            tableau = tableau,
            foundations = foundations,
            waste = wasteCards,
            stock = stockCards,
            screenSignals = screenSignals
        )
        diagnostics += "livePlayScreen=$livePlayScreen"
        diagnostics += "gameFooter=${screenSignals.gameControlFooter}"
        diagnostics += "lobbyScreen=${screenSignals.lobbyHomeScreen}"
        if (totalCards == 0) {
            slotHitCache = newSlotCache
            return DetectionResult(
                state = null,
                locations = locations,
                confidence = avg,
                diagnostics = diagnostics + "empty-board",
                board = board,
                recognizedSlots = recognizedSlots,
                livePlayScreen = false
            )
        }

        val knownFaceUp = tableau.sumOf { col -> col.count { it.faceUp && it.known } } +
            foundations.sumOf { pile -> pile.count { it.known } } +
            wasteCards.count { it.known }
        diagnostics += "knownFaceUp=$knownFaceUp"

        val scrubStartNanos = System.nanoTime()
        val blackScrubbed = scrubWeakBlackFoundationSuits(
            bitmap,
            locations,
            GameState(
                tableau = tableau,
                foundations = foundations,
                stock = stockCards,
                waste = wasteCards
            )
        )
        val scrubbed = scrubWeakRedFoundationSuits(bitmap, locations, blackScrubbed)
        val scrubNanos = System.nanoTime() - scrubStartNanos
        val deckConstraintStartNanos = System.nanoTime()
        val state = DeckConstraintPass.apply(
            bitmap = bitmap,
            recognizer = recognizer,
            state = scrubbed,
            recognizedSlots = recognizedSlots
        )
        val deckConstraintNanos = System.nanoTime() - deckConstraintStartNanos

        // Diagnostic-only: where detect()'s wall-clock time actually goes, to
        // find real latency targets instead of guessing at what's slow.
        val totalMs = (System.nanoTime() - detectStartNanos) / 1_000_000.0
        val tableauMs = tableauColumnNanos.sum() / 1_000_000.0
        val perColumnMs = tableauColumnNanos.indices.joinToString(",") { i ->
            "c$i=${"%.0f".format(tableauColumnNanos[i] / 1_000_000.0)}"
        }
        diagnostics += "timing:total=${"%.0f".format(totalMs)}ms," +
            "tableau=${"%.0f".format(tableauMs)}ms[$perColumnMs]," +
            "scrub=${"%.0f".format(scrubNanos / 1_000_000.0)}ms," +
            "deckConstraint=${"%.0f".format(deckConstraintNanos / 1_000_000.0)}ms," +
            "recognize:hits=$recognizeCacheHits,misses=$recognizeCacheMisses," +
            "missTime=${"%.0f".format(recognizeMissNanos / 1_000_000.0)}ms," +
            "missReason:newKey=$missNoPriorEntry,fingerprint=$missFingerprintChanged," +
            "bounds=$missBoundsShifted"

        slotHitCache = newSlotCache
        return DetectionResult(
            state = state,
            locations = locations,
            confidence = avg,
            diagnostics = diagnostics,
            board = board,
            recognizedSlots = recognizedSlots,
            livePlayScreen = livePlayScreen,
            preConstraintState = scrubbed
        )
    }

    /**
     * True when the frame looks like an in-progress Smash deal: tableau cards
     * plus teal backs / white faces in the playfield. Menus, shops, and the
     * assistant UI fail this gate so the overlay arrow stays hidden.
     */
    private fun isLivePlayScreen(
        bitmap: Bitmap,
        board: LocatedBoard,
        tableau: List<List<Card>>,
        foundations: List<List<Card>>,
        waste: List<Card>,
        stock: List<Card>,
        screenSignals: SmashPlayScreenGate.Signals
    ): Boolean {
        val occupiedCols = tableau.count { it.isNotEmpty() }
        val tableauCards = tableau.sumOf { it.size }
        val faceDown = tableau.sumOf { col -> col.count { !it.faceUp } }
        val faceUpVisible = tableau.sumOf { col -> col.count { it.faceUp } } +
            waste.size +
            foundations.count { it.isNotEmpty() }
        val enoughCards =
            (occupiedCols >= 2 && tableauCards >= 4) ||
                (occupiedCols >= 1 && faceUpVisible >= 4) ||
                (faceDown >= 3 && occupiedCols >= 2) ||
                (stock.isNotEmpty() && tableauCards >= 6)

        val cols = locator.tableauColumnRegions(board)
        val tableauBand = BoardRegion(
            left = cols.first().left,
            top = cols.first().top,
            right = cols.last().right,
            bottom = cols.last().bottom
        )
        val band = SmashColorAnalyzer.analyze(bitmap, tableauBand)
        val stockStats = SmashColorAnalyzer.analyze(bitmap, locator.stockRegion(board))
        val smashColor =
            band.tealRatio >= 0.025f ||
                stockStats.tealRatio >= 0.12f ||
                band.whiteRatio >= 0.07f

        return enoughCards &&
            smashColor &&
            screenSignals.gameControlFooter &&
            !screenSignals.lobbyHomeScreen
    }

    /**
     * Finds the right edge of the front waste card. The fan grows rightward,
     * so a fixed crop alternates between the second and third card.
     */
    private fun inkGuessFromRegion(
        bitmap: Bitmap,
        region: BoardRegion
    ): RankInkHeuristics.Guess? {
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        if (right - left < 8 || bottom - top < 8) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return try {
            RankInkHeuristics.guess(crop)
        } finally {
            crop.recycle()
        }
    }

    private fun locateWasteTopRegion(
        bitmap: Bitmap,
        board: LocatedBoard
    ): BoardRegion {
        val full = locator.wasteRegion(board)
        val expectedWidth = locator.foundationRegions(board).first().width
        val left = full.left.toInt().coerceIn(0, bitmap.width - 1)
        val right = full.right.toInt().coerceIn(left + 1, bitmap.width)
        val top = full.top.toInt().coerceIn(0, bitmap.height - 1)
        val bottom = full.bottom.toInt().coerceIn(top + 1, bitmap.height)
        var rightmostInk = -1

        for (x in left until right) {
            var cardPixels = 0
            var samples = 0
            var y = top
            while (y < bottom) {
                val color = bitmap.getPixel(x, y)
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                if (SmashColorAnalyzer.isCardWhite(r, g, b) ||
                    SmashColorAnalyzer.isRedInk(r, g, b)
                ) {
                    cardPixels++
                }
                samples++
                y += 2
            }
            if (cardPixels >= (samples * 0.06f).coerceAtLeast(2f)) {
                rightmostInk = x
            }
        }

        if (rightmostInk < 0) return locator.tightWasteTopRegion(board)
        val cardRight = (rightmostInk + 2f).coerceAtMost(full.right)
        return BoardRegion(
            left = (cardRight - expectedWidth).coerceAtLeast(full.left),
            top = full.top,
            right = cardRight,
            bottom = full.bottom
        )
    }

    /**
     * When color gates reject a fanned waste crop, template scores on the tight
     * card bounds can still identify the playable top card (e.g. 10H onto JC).
     */
    private fun synthesizeWasteFromScores(
        bitmap: Bitmap,
        legacyRegion: BoardRegion,
        tightRegion: BoardRegion,
        legacyHit: RecognitionHit,
        tightHit: RecognitionHit,
        rankScores: Map<Rank, Float>,
        suitScores: Map<Suit, Float>
    ): Card? {
        if (legacyHit.card != null || tightHit.card != null) return null
        val legacyStats = SmashColorAnalyzer.analyze(bitmap, legacyRegion)
        val tightStats = SmashColorAnalyzer.analyze(bitmap, tightRegion)
        val hasFace = SmashColorAnalyzer.looksFaceUp(legacyStats) ||
            SmashColorAnalyzer.looksFaceUp(tightStats) ||
            legacyStats.whiteRatio > 0.08f ||
            tightStats.whiteRatio > 0.08f
        if (!hasFace) return null

        val ranked = rankScores.entries.sortedByDescending { it.value }
        val rank = ranked.firstOrNull()
            ?.takeIf { it.value >= 0.58f }
            ?.takeIf { first ->
                val second = ranked.getOrNull(1)?.value ?: 0f
                first.value - second >= 0.04f || first.value >= 0.68f
            }
            ?.key ?: return null

        val red = when {
            tightStats.redInkRatio > tightStats.blackInkRatio + 0.008f -> true
            tightStats.blackInkRatio > tightStats.redInkRatio + 0.008f -> false
            legacyStats.redInkRatio > legacyStats.blackInkRatio + 0.008f -> true
            legacyStats.blackInkRatio > legacyStats.redInkRatio + 0.008f -> false
            else -> null
        }
        val suitCandidates = when (red) {
            true -> listOf(Suit.Hearts, Suit.Diamonds)
            false -> listOf(Suit.Clubs, Suit.Spades)
            null -> Suit.entries.toList()
        }
        val suited = suitCandidates.mapNotNull { suit ->
            suitScores[suit]?.let { suit to it }
        }.sortedByDescending { it.second }
        val suitPick = suited.firstOrNull()
            ?.takeIf { (suit, score) ->
                score >= 0.42f &&
                    (red == null || suit.isRed == red) &&
                    (suited.getOrNull(1)?.second?.let { second -> score - second >= 0.03f } != false ||
                        score >= 0.62f)
            }
            ?.first ?: return null

        return Card(rank, suitPick, faceUp = true, known = true)
    }

    /**
     * Finds the lowest white face in a tableau column, then derives its exact
     * top from the known card aspect ratio. This is substantially more stable
     * than stepping through the large center rank as if it were another card.
     */
    // Diagnostic-only: findPlayableFaceRegion's bottom-of-column scan has been
    // seen to land on a different row between two live captures of a board
    // that never visibly changed, shifting where the whole cascade's card
    // count gets anchored. Returns a trace of the scan (where it stopped,
    // why, and the last-white-row bookkeeping) alongside the region so a
    // real oscillation can be pinned to a specific row instead of guessed at.
    private fun findPlayableFaceRegion(
        bitmap: Bitmap,
        column: BoardRegion,
        cardHeight: Float
    ): Pair<BoardRegion?, String> {
        val rowHeight = (column.width / 24f).coerceAtLeast(2f)
        var y = column.top
        var lastWhiteBottom = -1f
        var whiteRows = 0
        var sawCardContent = false
        var blankRows = 0
        val maxBlankRows = (cardHeight * 0.22f / rowHeight).toInt().coerceAtLeast(4)
        var breakY: Float? = null
        while (y < column.bottom) {
            val row = BoardRegion(
                column.left,
                y,
                column.right,
                (y + rowHeight).coerceAtMost(column.bottom)
            )
            val stats = SmashColorAnalyzer.analyze(bitmap, row)
            val hasCardContent = stats.whiteRatio > 0.18f || stats.tealRatio > 0.12f
            if (hasCardContent) {
                sawCardContent = true
                blankRows = 0
            } else if (sawCardContent) {
                blankRows++
                if (blankRows >= maxBlankRows) {
                    breakY = y
                    break
                }
            }
            if (stats.whiteRatio > 0.28f && stats.tealRatio < 0.18f) {
                lastWhiteBottom = row.bottom
                whiteRows++
            }
            y += rowHeight
        }

        // Empty placeholders have only thin white border rows; a card face has
        // a substantial white area.
        val minimumRows = (cardHeight * 0.22f / rowHeight).toInt().coerceAtLeast(3)
        val trace = "rowHeight=${"%.2f".format(rowHeight)},lastWhiteBottom=" +
            "${"%.1f".format(lastWhiteBottom)},whiteRows=$whiteRows,minimumRows=" +
            "$minimumRows,breakY=${breakY?.let { "%.1f".format(it) } ?: "none"}"
        if (lastWhiteBottom < 0f || whiteRows < minimumRows) return null to trace

        val top = (lastWhiteBottom - cardHeight).coerceAtLeast(column.top)
        return BoardRegion(
            column.left,
            top,
            column.right,
            (top + cardHeight).coerceAtMost(column.bottom)
        ) to trace
    }

    fun regionForMove(
        locations: Map<PileRef, List<CardLocation>>,
        pile: PileRef,
        cardIndex: Int = -1,
        board: LocatedBoard? = null
    ): BoardRegion? {
        val list = locations[pile]
        if (!list.isNullOrEmpty()) {
            return if (cardIndex < 0) list.last().bounds else list.getOrNull(cardIndex)?.bounds
        }
        // Empty tableau destination (e.g. King to empty column).
        if (board != null && pile is PileRef.Tableau) {
            val cols = locator.tableauColumnRegions(board)
            val col = cols.getOrNull(pile.index) ?: return null
            val cardHeight = col.width * board.profile.cardAspect
            return BoardRegion(
                left = col.left,
                top = col.top,
                right = col.right,
                bottom = (col.top + cardHeight).coerceAtMost(col.bottom)
            )
        }
        if (board != null && pile is PileRef.Foundation) {
            return locator.foundationRegions(board).getOrNull(pile.index)
        }
        return null
    }

    fun release() {
        recognizer.release()
        columnExecutor.shutdown()
        wasteOcrExecutor.shutdown()
    }

    /**
     * Prefer geometric color from cascade math, then pick C/S or H/D.
     * Cascade header crops are noisy for black-suit ties — after v1.4.53 made
     * geom overrides countable, Clubs→Spades jumped 20→34 because weak
     * re-scores and the Spades color-placeholder were winning Evaluate.
     */
    private fun enrichGeometricCascadeSuit(
        bitmap: Bitmap,
        headerRegion: BoardRegion,
        geometric: Card,
        rejectedHit: RecognitionHit
    ): Card {
        val direct = rejectedHit.card
        // Same color family on the rejected read: keep that suit. Geometry
        // only needed the rank (or confirmed color); re-scoring C↔S on a
        // ~44px strip is how Clubs became Spades.
        if (direct != null &&
            direct.known &&
            direct.suit.isRed == geometric.suit.isRed
        ) {
            return geometric.copy(
                suit = direct.suit,
                suitAmbiguous = direct.suitAmbiguous
            )
        }

        val fromTrace = TableauCascadeSupport.enrichGeometricFromRejectedRead(
            geometric,
            rejectedHit
        )
        if (!fromTrace.suitAmbiguous && fromTrace.suit.isRed == geometric.suit.isRed) {
            return fromTrace
        }

        val family = if (geometric.suit.isRed) {
            setOf(Suit.Hearts, Suit.Diamonds)
        } else {
            setOf(Suit.Clubs, Suit.Spades)
        }
        val scores = recognizer.suitTemplateScores(bitmap, headerRegion, family)
        val best = family.maxByOrNull { scores[it] ?: 0f } ?: return ambiguousGeometric(geometric)
        val bestScore = scores[best] ?: 0f
        val partner = family.first { it != best }
        val partnerScore = scores[partner] ?: 0f
        // Stricter than normal recognition: cascade headers over-call Spades.
        if (bestScore < 0.70f || bestScore - partnerScore < 0.06f) {
            return ambiguousGeometric(geometric)
        }
        return geometric.copy(suit = best, suitAmbiguous = false)
    }

    /** Color-correct placeholder without pretending Spades/Hearts is known. */
    private fun ambiguousGeometric(geometric: Card): Card =
        geometric.copy(
            // Clubs/Diamonds as neutral placeholders — Spades/Hearts defaults
            // were biasing Evaluate (Clubs→Spades 34 after geom overrides).
            suit = if (geometric.suit.isRed) Suit.Diamonds else Suit.Clubs,
            suitAmbiguous = true
        )

    fun attemptCornerRankOcr(
        bitmap: Bitmap,
        region: BoardRegion
    ): RankCornerOcr.AttemptResult = recognizer.attemptCornerRankOcr(bitmap, region)

    fun attemptWasteRankOcr(
        bitmap: Bitmap,
        cardRegions: List<BoardRegion>
    ): RankCornerOcr.AttemptResult = recognizer.attemptWasteRankOcr(bitmap, cardRegions)

    /**
     * Black-suit near-ties that happen to match a foundation look "legal" but
     * are the main source of ClubsΓåöSpades false arrows. Demote those sources
     * to suitAmbiguous so foundation moves are suppressed.
     */
    private fun scrubWeakBlackFoundationSuits(
        bitmap: Bitmap,
        locations: Map<PileRef, List<CardLocation>>,
        state: GameState
    ): GameState {
        fun scrubPile(
            cards: List<Card>,
            pile: PileRef
        ): List<Card> {
            if (cards.isEmpty()) return cards
            val card = cards.last()
            if (!card.known || card.suit.isRed || card.suitAmbiguous) return cards
            val placesOnBlackFoundation = state.foundations.any { pileCards ->
                val top = pileCards.lastOrNull() ?: return@any false
                !top.suit.isRed && card.canPlaceOnFoundation(top)
            }
            if (!placesOnBlackFoundation) return cards
            val bounds = locations[pile]?.lastOrNull()?.bounds ?: return cards
            val cropLeft = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
            val cropTop = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
            val cropRight = bounds.right.toInt().coerceIn(cropLeft + 1, bitmap.width)
            val cropBottom = bounds.bottom.toInt().coerceIn(cropTop + 1, bitmap.height)
            if (cropRight - cropLeft >= 8 && cropBottom - cropTop >= 8) {
                val crop = Bitmap.createBitmap(
                    bitmap,
                    cropLeft,
                    cropTop,
                    cropRight - cropLeft,
                    cropBottom - cropTop
                )
                try {
                    val shape = SuitBadgeHeuristics.guessBlackSuit(crop)
                    if (shape != null &&
                        shape.suit == card.suit &&
                        shape.margin >= CardRecognizer.BLACK_SHAPE_MIN_MARGIN
                    ) {
                        return cards
                    }
                } finally {
                    crop.recycle()
                }
            }
            val scores = recognizer.suitTemplateScores(
                bitmap,
                bounds,
                setOf(Suit.Clubs, Suit.Spades)
            )
            val club = scores[Suit.Clubs] ?: 0f
            val spade = scores[Suit.Spades] ?: 0f
            val margin = kotlin.math.abs(club - spade)
            val leader = if (club >= spade) Suit.Clubs else Suit.Spades
            val bestScore = maxOf(club, spade)
            // Match resolveBlackSuit confidence: only suppress when we would not
            // have committed this suit in the first place.
            val confident =
                leader == card.suit &&
                    (margin >= 0.050f || (margin >= 0.025f && bestScore >= 0.84f))
            if (!confident) {
                return cards.dropLast(1) + card.copy(suitAmbiguous = true)
            }
            // Extra guard for 2ΓåÆA foundation: club/spade swaps here are the
            // most common illegal arrows. Require shape not to strongly disagree.
            val aceFoundation = state.foundations.any { pileCards ->
                val top = pileCards.lastOrNull() ?: return@any false
                top.rank == Rank.Ace &&
                    !top.suit.isRed &&
                    card.rank == Rank.Two &&
                    card.canPlaceOnFoundation(top)
            }
            if (aceFoundation &&
                cropRight - cropLeft >= 8 &&
                cropBottom - cropTop >= 8
            ) {
                val crop = Bitmap.createBitmap(
                    bitmap,
                    cropLeft,
                    cropTop,
                    cropRight - cropLeft,
                    cropBottom - cropTop
                )
                try {
                    val shape = SuitBadgeHeuristics.guessBlackSuit(crop)
                    if (shape != null &&
                        shape.suit != card.suit &&
                        shape.margin >= 0.06f
                    ) {
                        return cards.dropLast(1) + card.copy(suitAmbiguous = true)
                    }
                } finally {
                    crop.recycle()
                }
            }
            return cards
        }

        val tableau = state.tableau.mapIndexed { index, cards ->
            scrubPile(cards, PileRef.Tableau(index))
        }
        val waste = scrubPile(state.waste, PileRef.Waste)
        if (tableau == state.tableau && waste == state.waste) return state
        return state.copy(tableau = tableau, waste = waste)
    }

    /**
     * Red-suit counterpart of [scrubWeakBlackFoundationSuits]: Hearts↔Diamonds
     * near-ties that happen to match a foundation look "legal" but are a source
     * of false arrows just like Clubs↔Spades. Demote those sources to
     * suitAmbiguous so foundation moves are suppressed.
     */
    private fun scrubWeakRedFoundationSuits(
        bitmap: Bitmap,
        locations: Map<PileRef, List<CardLocation>>,
        state: GameState
    ): GameState {
        fun scrubPile(
            cards: List<Card>,
            pile: PileRef
        ): List<Card> {
            if (cards.isEmpty()) return cards
            val card = cards.last()
            if (!card.known || !card.suit.isRed || card.suitAmbiguous) return cards
            val placesOnRedFoundation = state.foundations.any { pileCards ->
                val top = pileCards.lastOrNull() ?: return@any false
                top.suit.isRed && card.canPlaceOnFoundation(top)
            }
            if (!placesOnRedFoundation) return cards
            val bounds = locations[pile]?.lastOrNull()?.bounds ?: return cards
            val cropLeft = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
            val cropTop = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
            val cropRight = bounds.right.toInt().coerceIn(cropLeft + 1, bitmap.width)
            val cropBottom = bounds.bottom.toInt().coerceIn(cropTop + 1, bitmap.height)
            if (cropRight - cropLeft >= 8 && cropBottom - cropTop >= 8) {
                val crop = Bitmap.createBitmap(
                    bitmap,
                    cropLeft,
                    cropTop,
                    cropRight - cropLeft,
                    cropBottom - cropTop
                )
                try {
                    val shape = SuitBadgeHeuristics.guessRedSuit(crop)
                    if (shape != null &&
                        shape.suit == card.suit &&
                        shape.margin >= CardRecognizer.RED_SHAPE_MIN_MARGIN
                    ) {
                        return cards
                    }
                } finally {
                    crop.recycle()
                }
            }
            val scores = recognizer.suitTemplateScores(
                bitmap,
                bounds,
                setOf(Suit.Hearts, Suit.Diamonds)
            )
            val heart = scores[Suit.Hearts] ?: 0f
            val diamond = scores[Suit.Diamonds] ?: 0f
            val margin = kotlin.math.abs(heart - diamond)
            val leader = if (heart >= diamond) Suit.Hearts else Suit.Diamonds
            val bestScore = maxOf(heart, diamond)
            // Match resolveRedSuit confidence: only suppress when we would not
            // have committed this suit in the first place.
            val confident =
                leader == card.suit &&
                    (margin >= 0.050f || (margin >= 0.025f && bestScore >= 0.84f))
            if (!confident) {
                return cards.dropLast(1) + card.copy(suitAmbiguous = true)
            }
            return cards
        }

        val tableau = state.tableau.mapIndexed { index, cards ->
            scrubPile(cards, PileRef.Tableau(index))
        }
        val waste = scrubPile(state.waste, PileRef.Waste)
        if (tableau == state.tableau && waste == state.waste) return state
        return state.copy(tableau = tableau, waste = waste)
    }

    private fun resolveCardSuitWithTrace(
        bitmap: Bitmap,
        region: BoardRegion,
        card: Card,
        trace: RecognitionTrace
    ): Pair<Card, RecognitionTrace> {
        if (!card.known) return card to trace
        if (!card.suitAmbiguous &&
            trace.suitScore != null &&
            trace.suitScore >= 0.72f &&
            trace.suitSource != null &&
            !trace.suitSource.startsWith("suit-ambiguous")
        ) {
            return card to trace
        }
        // resolveBlackSuit below re-scores against a narrower crop (region, usually
        // the trimmed rank-header strip) than the first pass used - its badge
        // locator clamps its pip search band to a 12px floor on a crop this short,
        // which can truncate a club's lobes down to just its top curve and produce
        // much weaker, noisier scores than the first pass got from the fuller crop
        // it was recognized on. A real golden sample showed this: first pass
        // C0.90/S0.91 (correctly declined as a genuine tie), second pass C0.58/
        // S0.62 on the narrow crop - and that weaker, less reliable read still won
        // outright and flipped a clean King of Clubs to King of Spades. When the
        // first pass already had strong absolute signal on both candidates, trust
        // its ambiguous verdict instead of letting a structurally weaker recheck
        // override it.
        if (!card.suit.isRed &&
            card.suitAmbiguous &&
            parseBlackSuitTemplateMax(trace.suitTemplates) >= STRONG_AMBIGUOUS_SUIT_FLOOR
        ) {
            return card to trace
        }
        val beforeSuit = card.suit
        val beforeAmbiguous = card.suitAmbiguous
        val resolved: Card
        val blackDebug: String?
        if (card.suit.isRed) {
            resolved = resolveRedSuit(bitmap, region, card)
            blackDebug = null
        } else {
            val (blackResolved, debug) = resolveBlackSuit(bitmap, region, card)
            resolved = blackResolved
            blackDebug = debug
        }
        val post = when {
            resolved.suit != beforeSuit ->
                "post-${if (beforeSuit.isRed) "red" else "black"}-suit:$beforeSuit->${resolved.suit}"
            resolved.suitAmbiguous && !beforeAmbiguous ->
                "post-${if (beforeSuit.isRed) "red" else "black"}-suit:marked-ambiguous"
            !resolved.suitAmbiguous && beforeAmbiguous ->
                "post-${if (beforeSuit.isRed) "red" else "black"}-suit:cleared-ambiguous"
            else -> null
        }
        var updatedTrace = post?.let { trace.withPost(it) } ?: trace
        if (blackDebug != null) {
            updatedTrace = updatedTrace.withPost("black-tiebreak2:$blackDebug")
        }
        return resolved to updatedTrace
    }

    private fun resolveRedSuit(
        bitmap: Bitmap,
        region: BoardRegion,
        card: Card
    ): Card {
        if (!card.known || !card.suit.isRed) return card
        val cropLeft = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val cropTop = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val cropRight = region.right.toInt().coerceIn(cropLeft + 1, bitmap.width)
        val cropBottom = region.bottom.toInt().coerceIn(cropTop + 1, bitmap.height)
        val shape = if (cropRight - cropLeft >= 8 && cropBottom - cropTop >= 8) {
            val crop = Bitmap.createBitmap(
                bitmap,
                cropLeft,
                cropTop,
                cropRight - cropLeft,
                cropBottom - cropTop
            )
            try {
                SuitBadgeHeuristics.guessRedSuit(crop)
            } finally {
                crop.recycle()
            }
        } else {
            null
        }
        val suitScores = recognizer.suitTemplateScores(
            bitmap,
            region,
            setOf(Suit.Hearts, Suit.Diamonds)
        )
        val heartScore = suitScores[Suit.Hearts] ?: 0f
        val diamondScore = suitScores[Suit.Diamonds] ?: 0f
        val margin = kotlin.math.abs(heartScore - diamondScore)
        val pairwiseLead = when {
            heartScore >= 0.45f && diamondScore >= 0.45f && margin >= CardRecognizer.RED_SUIT_MARGIN ->
                if (heartScore > diamondScore) Suit.Hearts else Suit.Diamonds
            else -> null
        }
        val templateLeader = when {
            diamondScore > heartScore + 0.025f -> Suit.Diamonds
            heartScore > diamondScore + 0.025f -> Suit.Hearts
            else -> null
        }
        val bestScore = maxOf(heartScore, diamondScore)
        val templateConfident = templateLeader != null &&
            (margin >= 0.035f || (margin >= 0.022f && bestScore >= 0.78f))
        val shapeConfident = shape != null && shape.margin >= 0.08f
        val shapeStrong = shape != null && shape.margin >= 0.12f
        val shapeSuit = shape?.suit
        val shapeMargin = shape?.margin ?: 0f
        val diamondLeads = diamondScore > heartScore + 0.035f
        val heartLeads = heartScore > diamondScore + 0.035f
        if (shapeStrong && shapeSuit != null && margin < CardRecognizer.RED_SUIT_MARGIN) {
            return card.copy(
                suit = shapeSuit,
                suitAmbiguous = shapeMargin < 0.14f
            )
        }
        if (margin < CardRecognizer.RED_SUIT_MARGIN &&
            maxOf(heartScore, diamondScore) >= 0.72f &&
            shapeStrong &&
            shapeSuit != null &&
            (
                (shapeSuit == Suit.Hearts && heartScore + 0.08f >= diamondScore) ||
                    (shapeSuit == Suit.Diamonds && diamondScore + 0.08f >= heartScore)
                )
        ) {
            return card.copy(
                suit = shapeSuit,
                suitAmbiguous = shapeMargin < 0.14f
            )
        }
        val resolved = when {
            shapeSuit != null && templateLeader == shapeSuit ->
                shapeSuit
            templateConfident && templateLeader != null -> {
                if (shapeSuit != null &&
                    shapeSuit != templateLeader &&
                    shapeMargin >= 0.18f &&
                    (
                        (shapeSuit == Suit.Hearts && heartLeads) ||
                            (shapeSuit == Suit.Diamonds && diamondLeads)
                        )
                ) {
                    shapeSuit
                } else {
                    templateLeader
                }
            }
            pairwiseLead != null && templateLeader == pairwiseLead ->
                pairwiseLead
            templateLeader != null && margin >= 0.022f && bestScore >= 0.72f ->
                templateLeader
            templateLeader != null && margin >= 0.028f ->
                templateLeader
            shapeConfident &&
                shapeSuit == Suit.Diamonds &&
                shapeMargin >= 0.12f &&
                diamondLeads ->
                Suit.Diamonds
            shapeConfident &&
                shapeSuit == Suit.Hearts &&
                shapeMargin >= 0.14f &&
                heartLeads ->
                Suit.Hearts
            shapeStrong && shapeSuit != null ->
                shapeSuit
            else -> {
                val fallback = when {
                    diamondLeads -> Suit.Diamonds
                    heartLeads -> Suit.Hearts
                    pairwiseLead != null -> pairwiseLead
                    templateLeader != null && margin >= 0.018f && bestScore >= 0.68f -> templateLeader
                    templateLeader != null -> templateLeader
                    else -> card.suit
                }
                val ambiguous = margin < CardRecognizer.RED_SUIT_MARGIN ||
                    (shapeSuit != null && shapeSuit != fallback && shapeMargin >= 0.12f)
                return card.copy(
                    suit = fallback,
                    suitAmbiguous = (ambiguous && shapeSuit == null) || margin < 0.035f
                )
            }
        }
        return card.copy(suit = resolved, suitAmbiguous = false)
    }

    private fun resolveBlackSuit(
        bitmap: Bitmap,
        region: BoardRegion,
        card: Card
    ): Pair<Card, String?> {
        if (!card.known || card.suit.isRed) return card to null
        val scores = recognizer.blackSuitTemplateScores(bitmap, region)
        val clubScore = scores.fullClub
        val spadeScore = scores.fullSpade
        val margin = scores.fullMargin
        val cropLeft = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val cropTop = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val cropRight = region.right.toInt().coerceIn(cropLeft + 1, bitmap.width)
        val cropBottom = region.bottom.toInt().coerceIn(cropTop + 1, bitmap.height)
        val cardCrop = if (cropRight - cropLeft >= 8 && cropBottom - cropTop >= 8) {
            Bitmap.createBitmap(
                bitmap,
                cropLeft,
                cropTop,
                cropRight - cropLeft,
                cropBottom - cropTop
            )
        } else {
            null
        }
        try {
            val tiebreakShape = cardCrop?.let {
                SuitBadgeHeuristics.guessBlackSuit(it, CardRecognizer.TOP_BLACK_SHAPE_VETO_MARGIN)
            }
            val shape = tiebreakShape?.takeIf { it.margin >= CardRecognizer.BLACK_SHAPE_MIN_MARGIN }
            val fullLeader = if (scores.fullSpade > scores.fullClub) Suit.Spades else Suit.Clubs
            val topLeader = if (scores.topSpade > scores.topClub) Suit.Spades else Suit.Clubs
            var preferTopHalf = fullLeader != topLeader &&
                scores.topMargin > scores.fullMargin &&
                scores.topMargin >= CardRecognizer.TOP_BLACK_SUIT_MARGIN
            if (preferTopHalf &&
                fullLeader == Suit.Spades &&
                topLeader == Suit.Clubs &&
                cardCrop != null &&
                recognizer.shouldVetoTopHalfForSpadeTip(scores, cardCrop, fullLeader, topLeader)
            ) {
                preferTopHalf = false
            }
            val debugLines = mutableListOf<String>()
            val resolved = if (margin < CardRecognizer.BLACK_SUIT_TOP_TIEBREAK_MAX || preferTopHalf) {
                val (leader, ambiguous) = recognizer.resolveBlackSuitLeader(
                    scores,
                    cardCrop,
                    tiebreakShape
                ) { debugLines += it }
                if (leader != null) {
                    card.copy(suit = leader, suitAmbiguous = ambiguous)
                } else {
                    resolveBlackSuitLegacy(
                        card,
                        clubScore,
                        spadeScore,
                        bitmap,
                        region,
                        shape
                    )
                }
            } else {
                resolveBlackSuitLegacy(
                    card,
                    clubScore,
                    spadeScore,
                    bitmap,
                    region,
                    shape
                )
            }
            val recovered = recognizer.recoverLowConfidenceSpade(
                leader = resolved.suit,
                clubScore = clubScore,
                spadeScore = spadeScore,
                margin = margin,
                crop = cardCrop,
                rank = card.rank,
                topClubScore = scores.topClub,
                topSpadeScore = scores.topSpade,
                topMargin = scores.topMargin
            )
            if (recovered != null && recovered.first != resolved.suit) {
                debugLines += "recoverLowConfidenceSpade->${recovered.first}"
            }
            val debug = debugLines.takeIf { it.isNotEmpty() }?.joinToString(";")
            return if (recovered != null) {
                resolved.copy(
                    suit = recovered.first,
                    suitAmbiguous = recovered.second
                ) to debug
            } else {
                resolved to debug
            }
        } finally {
            cardCrop?.recycle()
        }
    }

    private fun resolveBlackSuitLegacy(
        card: Card,
        clubScore: Float,
        spadeScore: Float,
        bitmap: Bitmap,
        region: BoardRegion,
        shape: SuitBadgeHeuristics.Guess?
    ): Card {
        val suitScores = recognizer.suitTemplateScores(
            bitmap,
            region,
            setOf(Suit.Clubs, Suit.Spades)
        )
        val clubScoreFromMap = suitScores[Suit.Clubs] ?: clubScore
        val spadeScoreFromMap = suitScores[Suit.Spades] ?: spadeScore
        val marginFromMap = kotlin.math.abs(clubScoreFromMap - spadeScoreFromMap)
        val templateLeader = when {
            spadeScoreFromMap > clubScoreFromMap + 0.035f -> Suit.Spades
            clubScoreFromMap > spadeScoreFromMap + 0.035f -> Suit.Clubs
            else -> null
        }
        val bestScore = maxOf(clubScoreFromMap, spadeScoreFromMap)
        val templateConfident = templateLeader != null &&
            (marginFromMap >= 0.050f || (marginFromMap >= 0.030f && bestScore >= 0.84f))
        val shapeConfident = shape != null && shape.margin >= CardRecognizer.BLACK_SHAPE_MIN_MARGIN
        val shapeSuit = shape?.suit
        val shapeMargin = shape?.margin ?: 0f
        val spadeLeads = spadeScoreFromMap > clubScoreFromMap + 0.035f
        val clubLeads = clubScoreFromMap > spadeScoreFromMap + 0.035f
        val resolved = when {
            shapeSuit != null && templateLeader == shapeSuit ->
                shapeSuit
            templateConfident && templateLeader != null -> {
                if (shapeSuit != null &&
                    shapeSuit != templateLeader &&
                    shapeMargin >= CardRecognizer.BLACK_SHAPE_MIN_MARGIN &&
                    (
                        (shapeSuit == Suit.Spades && spadeLeads) ||
                            (shapeSuit == Suit.Clubs && clubLeads)
                        )
                ) {
                    shapeSuit
                } else {
                    templateLeader
                }
            }
            templateLeader != null && marginFromMap >= 0.024f && bestScore >= 0.76f ->
                templateLeader
            templateLeader != null && marginFromMap >= 0.030f ->
                templateLeader
            shapeConfident &&
                shapeSuit == Suit.Spades &&
                shapeMargin >= CardRecognizer.BLACK_SHAPE_MIN_MARGIN &&
                spadeLeads ->
                Suit.Spades
            shapeConfident &&
                shapeSuit == Suit.Clubs &&
                shapeMargin >= CardRecognizer.BLACK_SHAPE_MIN_MARGIN &&
                clubLeads ->
                Suit.Clubs
            else -> {
                val fallback = when {
                    spadeLeads -> Suit.Spades
                    clubLeads -> Suit.Clubs
                    templateLeader != null && marginFromMap >= 0.020f && bestScore >= 0.72f -> templateLeader
                    templateLeader != null -> templateLeader
                    else -> card.suit
                }
                return card.copy(
                    suit = fallback,
                    suitAmbiguous = marginFromMap < CardRecognizer.BLACK_SUIT_MARGIN && shapeSuit == null
                )
            }
        }
        return card.copy(suit = resolved, suitAmbiguous = false)
    }

    private fun recognizeCached(
        bitmap: Bitmap,
        pile: PileRef,
        index: Int,
        region: BoardRegion,
        cache: MutableMap<SlotKey, CachedSlotHit>,
        exactCardBounds: Boolean = false,
        inkRegion: BoardRegion? = null,
        trimmedToVisibleStrip: Boolean = false,
        // Non-null when called from a parallel tableau column (see
        // detect()'s computeColumn): routes the diagnostic counters below
        // into a column-local accumulator instead of the instance fields,
        // which would otherwise race across columns running on different
        // threads at once.
        stats: RecognizeStats? = null
    ): RecognitionHit {
        val key = SlotKey(pile, index)
        val fingerprint = regionFingerprint(bitmap, region)
        // slotHitCache holds the previous frame's results, fully built
        // before this detect() call started and reassigned only once, after
        // every tableau column has finished - safe to read here regardless
        // of which thread a parallel column runs on.
        val previous = slotHitCache[key]
        if (previous != null &&
            previous.fingerprint == fingerprint &&
            regionsSimilar(previous.bounds, region)
        ) {
            cache[key] = previous
            if (stats != null) stats.hits++ else recognizeCacheHits++
            return previous.hit
        }
        // Diagnostic-only: which reason this miss falls into, so a cache
        // that's oddly cold can be told apart from genuine board changes.
        when {
            previous == null -> if (stats != null) stats.missNoPrior++ else missNoPriorEntry++
            previous.fingerprint != fingerprint ->
                if (stats != null) stats.missFingerprint++ else missFingerprintChanged++
            else -> if (stats != null) stats.missBounds++ else missBoundsShifted++
        }
        val missStart = System.nanoTime()
        val hit = recognizer.recognize(bitmap, region, exactCardBounds, inkRegion, trimmedToVisibleStrip)
        val missElapsed = System.nanoTime() - missStart
        if (stats != null) {
            stats.missNanos += missElapsed
            stats.misses++
        } else {
            recognizeMissNanos += missElapsed
            recognizeCacheMisses++
        }
        val entry = CachedSlotHit(fingerprint, region, hit)
        cache[key] = entry
        return hit
    }

    private fun regionFingerprint(bitmap: Bitmap, region: BoardRegion): Long {
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val cols = ((right - left) / 12).coerceIn(4, 16)
        val rows = ((bottom - top) / 12).coerceIn(4, 16)
        var hash = pileFingerprintSeed
        val stepX = ((right - left) / cols).coerceAtLeast(1)
        val stepY = ((bottom - top) / rows).coerceAtLeast(1)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = bitmap.getPixel(x, y)
                // Top 4 bits/channel (16 levels, ~16-unit buckets) instead of
                // 5 (~8-unit buckets). A single sample landing near an 8-unit
                // boundary flips on ordinary capture noise almost every
                // frame, and one flipped sample changes this whole combined
                // hash — defeating the per-slot cache even when the card is
                // unchanged. 16 units is still far below any real card-color
                // difference (white/teal/red-ink/black-ink differ by 50+).
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

    private fun regionsSimilar(a: BoardRegion, b: BoardRegion): Boolean =
        kotlin.math.abs(a.left - b.left) < 6f &&
            kotlin.math.abs(a.top - b.top) < 6f &&
            kotlin.math.abs(a.right - b.right) < 6f &&
            kotlin.math.abs(a.bottom - b.bottom) < 6f

    private fun recognizedSlot(
        pile: PileRef,
        index: Int,
        bounds: BoardRegion,
        hit: RecognitionHit,
        cardOverride: Card? = null,
        trace: RecognitionTrace = hit.trace,
        inferred: Boolean = false,
        diagnostic: String = hit.diagnostic,
        confidence: Float = hit.confidence
    ): RecognizedSlot = RecognizedSlot(
        pile = pile,
        index = index,
        bounds = bounds,
        engine = slotGuessFromHit(hit, cardOverride),
        confidence = confidence,
        diagnostic = diagnostic,
        trace = trace,
        inferred = inferred
    )

    private fun cardFromHit(hit: RecognitionHit): Card? {
        if (hit.isEmpty || hit.isFaceDown) return null
        hit.card?.let { return it }
        // Face-up occupancy without a confident rank/suit match.
        val suit = when (hit.inferredRed) {
            true -> Suit.Hearts
            false -> Suit.Clubs
            null -> Suit.Clubs
        }
        return Card(Rank.Ace, suit, faceUp = true, known = false)
    }

    companion object {
        private const val pileFingerprintSeed = -0x6c62272e07bb0142L
        // See resolveCardSuitWithTrace: below this, the first pass's own black-
        // suit scores are too weak to trust its "ambiguous" verdict over a second
        // opinion; at or above it, both candidates already scored high enough
        // that a genuine tie is more likely than a bad read, and a structurally
        // weaker recheck (narrower crop) shouldn't be allowed to break it.
        private const val STRONG_AMBIGUOUS_SUIT_FLOOR = 0.80f
        // See the exposedIndex loop in computeColumn: below this, a mid-cascade
        // slot's own direct read isn't trusted over a doubly-anchored geometric
        // consensus (bottom card + leading card agreeing on the run's rank
        // count) that disagrees with it. TableauCascadeSupport.MIN_READ_CONFIDENCE
        // (0.55) alone was letting reads like rank-png@0.60 with the true rank
        // not even in the top-4 candidates win outright over a fallback that
        // would have been correct, on a real golden sample (Ten/Nine/Eight/Seven
        // read as Ten/Ten~/Ten~/Queen when the run's own endpoints agreed on a
        // clean Ten-through-Six run). A read at or above this bar is still
        // trusted even when it disagrees with geometry, since the geometry
        // could occasionally be the one that's wrong. See
        // TableauCascadeSupport.STRONG_DIRECT_READ_FLOOR.
    }
}

/**
 * Parses a [RecognitionTrace.suitTemplates] string like "S:0.91 C:0.90" (see
 * [RecognitionTrace.formatSuitScores]) and returns the higher of the Clubs/
 * Spades scores, ignoring Hearts/Diamonds entries. Returns 0f if the string is
 * null, empty, or has no parseable Clubs/Spades entry.
 */
private fun parseBlackSuitTemplateMax(suitTemplates: String?): Float {
    if (suitTemplates.isNullOrBlank()) return 0f
    return suitTemplates.split(" ")
        .mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size != 2) return@mapNotNull null
            val code = parts[0]
            if (code != "C" && code != "S") return@mapNotNull null
            parts[1].toFloatOrNull()
        }
        .maxOrNull() ?: 0f
}

private data class ResolvedCascadeSlot(
    val card: Card,
    val trace: RecognitionTrace,
    val diagnostic: String,
    val confidence: Float,
    val inferred: Boolean
)
