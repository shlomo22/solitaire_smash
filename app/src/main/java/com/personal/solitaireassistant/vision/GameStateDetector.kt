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
import kotlin.math.min
import kotlin.math.roundToInt

data class DetectionResult(
    val state: GameState?,
    val locations: Map<PileRef, List<CardLocation>>,
    val confidence: Float,
    val diagnostics: List<String>,
    val board: LocatedBoard?,
    val recognizedSlots: List<RecognizedSlot> = emptyList(),
    val livePlayScreen: Boolean = false
)

class GameStateDetector(
    context: Context,
    minConfidence: Float = 0.65f
) {
    private val locator = BoardLocator()
    private val recognizer = CardRecognizer(context, minConfidence)
    private var slotHitCache = mutableMapOf<SlotKey, CachedSlotHit>()

    private data class SlotKey(val pile: PileRef, val index: Int)
    private data class CachedSlotHit(
        val fingerprint: Long,
        val bounds: BoardRegion,
        val hit: RecognitionHit
    )

    @Suppress("UNUSED_PARAMETER")
    fun updateMinConfidence(value: Float) {
        // Recognizer is constructed with a threshold; pipeline passes settings for future rebuilds.
    }

    fun clearSlotCache() {
        slotHitCache.clear()
    }

    fun detect(bitmap: Bitmap): DetectionResult {
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
        val wasteOcrRelevant =
            legacyCard?.rank in WASTE_OCR_RELEVANT_RANKS ||
                tightCard?.rank in WASTE_OCR_RELEVANT_RANKS
        val wasteOcrAttempt = if (wasteOcrRelevant) {
            val wasteOcrRegions = buildList {
                add(tightWasteRegion)
                add(legacyWasteRegion)
                addAll(locator.wasteOcrCardRegions(board))
            }
            recognizer.attemptWasteRankOcr(bitmap, wasteOcrRegions)
        } else {
            null
        }
        val wasteOcrOverride = WasteRankCorrections.ocrRankOverride(
            ocrRank = wasteOcrAttempt?.guess?.rank,
            legacyCard = legacyCard,
            tightCard = tightCard,
            baseCard = baseCard
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
        val correctedRank = when {
            wasteOcrOverride != null -> wasteOcrOverride
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
                tightCard.suit != legacyCard.suit -> Rank.Six
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
        val (fusedCard, fusionPostTrace) = if (baseCard != null && correctedRank != null) {
            val candidate = baseCard.copy(
                rank = correctedRank,
                suit = correctedSuit ?: baseCard.suit
            )
            resolveCardSuitWithTrace(
                bitmap,
                tightWasteRegion,
                candidate,
                RecognitionTrace.EMPTY
            )
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

        locator.foundationRegions(board).forEachIndexed { index, region ->
            val hit = recognizeCached(
                bitmap = bitmap,
                pile = PileRef.Foundation(index),
                index = 0,
                region = region,
                cache = newSlotCache
            )
            locations[PileRef.Foundation(index)] = listOf(
                locator.toCardLocation(PileRef.Foundation(index), 0, region)
            )
            val (foundationCard, trace) = cardFromHit(hit)?.let { card ->
                resolveCardSuitWithTrace(bitmap, region, card, hit.trace)
            } ?: (null to hit.trace)
            recognizedSlots += recognizedSlot(
                pile = PileRef.Foundation(index),
                index = 0,
                bounds = region,
                hit = hit,
                cardOverride = foundationCard,
                trace = trace
            )
            foundations[index] = listOfNotNull(foundationCard)
            diagnostics += "foundation$index=${hit.diagnostic}:${foundationCard}"
        }

        locator.tableauColumnRegions(board).forEachIndexed { col, columnRegion ->
            val cards = mutableListOf<Card>()
            val locs = mutableListOf<CardLocation>()
            val cardWidth = columnRegion.width
            val cardHeight = cardWidth * board.profile.cardAspect
            val downStep = cardHeight * board.profile.faceDownOverlap
            val faceRegion = findPlayableFaceRegion(bitmap, columnRegion, cardHeight)

            if (faceRegion != null) {
                // Count only genuinely teal headers above the playable face. This
                // avoids treating exposed face-up cascades as hidden cards.
                var y = columnRegion.top
                var faceDownCount = 0
                while (y + 4f < faceRegion.top && faceDownCount < 6) {
                    val strip = BoardRegion(
                        columnRegion.left,
                        y,
                        columnRegion.right,
                        (y + downStep).coerceAtMost(faceRegion.top)
                    )
                    val stats = SmashColorAnalyzer.analyze(bitmap, strip)
                    if (SmashColorAnalyzer.looksFaceDown(stats)) {
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
                    cache = newSlotCache
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
                        break
                    }
                    val bounds = BoardRegion(
                        columnRegion.left,
                        firstFaceTop,
                        columnRegion.right,
                        (firstFaceTop + cardHeight).coerceAtMost(columnRegion.bottom)
                    )
                    val boundaryHit = recognizeCached(
                        bitmap = bitmap,
                        pile = PileRef.Tableau(col),
                        index = 100 + faceDownCount,
                        region = bounds,
                        cache = newSlotCache
                    )
                    if (boundaryStats.whiteRatio > 0.12f &&
                        !boundaryHit.isFaceDown &&
                        !boundaryHit.isEmpty
                    ) {
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
                        cache = newSlotCache,
                        exactCardBounds = true,
                        inkRegion = leadingHeaderRegion
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
                    } else {
                        cascadeRankCountNote = "leadingUnknown"
                    }
                }
                // Diagnostic-only: geometric faceUpCount is a real-pixel-distance
                // divided by an assumed per-card step, which can drift over a long
                // cascade and land every slot below the drift point on the wrong
                // physical card. Surface the raw numbers so a real miscount can be
                // told apart from a genuine recognition miss.
                slotTrace = slotTrace.withPost(
                    "cascade:firstFaceTop=${"%.1f".format(firstFaceTop)}," +
                        "bottomTop=${"%.1f".format(faceRegion.top)}," +
                        "faceUpStep=${"%.2f".format(faceUpStep)}," +
                        "geomCount=$geometricFaceUpCount,finalCount=$faceUpCount," +
                        "bottomRank=${card.rank.name},$cascadeRankCountNote"
                )
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
                    // one's. Keep the ink read scoped to the visible strip.
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
                        cache = newSlotCache,
                        exactCardBounds = true,
                        inkRegion = headerRegion
                    )
                    val slotCard = cardFromHit(slotHit) ?: slotHit.card
                    val (cascadeCard, cascadeTrace, cascadeDiagnostic, cascadeConfidence, cascadeInferred) =
                        if (TableauCascadeSupport.isReliableRead(slotHit, slotCard)) {
                            val (resolved, trace) = resolveCardSuitWithTrace(
                                bitmap,
                                bounds,
                                requireNotNull(slotCard),
                                slotHit.trace
                            )
                            ResolvedCascadeSlot(
                                card = resolved.copy(inferred = false),
                                trace = trace,
                                diagnostic = slotHit.diagnostic,
                                confidence = slotHit.confidence,
                                inferred = false
                            )
                        } else {
                            ResolvedCascadeSlot(
                                card = geometricFallback,
                                trace = RecognitionTrace.EMPTY,
                                diagnostic = "inferred-cascade",
                                confidence = if (geometricFallback.known) 0.55f else 0.20f,
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
                            inferred = false
                        )
                    }
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
            tableau[col] = cards
            locations[PileRef.Tableau(col)] = locs
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
        }

        val wasteConfidence = if (wasteCandidatesDisagree) {
            wasteHit.confidence.coerceAtMost(0.70f)
        } else {
            wasteHit.confidence
        }
        val confidences = mutableListOf(board.confidence, stockHit.confidence, wasteConfidence)
        val avg = confidences.average().toFloat()
        val totalCards = tableau.sumOf { it.size } + foundations.sumOf { it.size } +
            wasteCards.size + stockCards.size
        val livePlayScreen = isLivePlayScreen(
            bitmap = bitmap,
            board = board,
            tableau = tableau,
            foundations = foundations,
            waste = wasteCards,
            stock = stockCards
        )
        diagnostics += "livePlayScreen=$livePlayScreen"
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
        val state = DeckConstraintPass.apply(
            bitmap = bitmap,
            recognizer = recognizer,
            state = scrubbed,
            recognizedSlots = recognizedSlots
        )

        slotHitCache = newSlotCache
        return DetectionResult(
            state = state,
            locations = locations,
            confidence = avg,
            diagnostics = diagnostics,
            board = board,
            recognizedSlots = recognizedSlots,
            livePlayScreen = livePlayScreen
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
        stock: List<Card>
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

        return enoughCards && smashColor
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
    private fun findPlayableFaceRegion(
        bitmap: Bitmap,
        column: BoardRegion,
        cardHeight: Float
    ): BoardRegion? {
        val rowHeight = (column.width / 24f).coerceAtLeast(2f)
        var y = column.top
        var lastWhiteBottom = -1f
        var whiteRows = 0
        var sawCardContent = false
        var blankRows = 0
        val maxBlankRows = (cardHeight * 0.22f / rowHeight).toInt().coerceAtLeast(4)
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
                if (blankRows >= maxBlankRows) break
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
        if (lastWhiteBottom < 0f || whiteRows < minimumRows) return null

        val top = (lastWhiteBottom - cardHeight).coerceAtLeast(column.top)
        return BoardRegion(
            column.left,
            top,
            column.right,
            (top + cardHeight).coerceAtMost(column.bottom)
        )
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
    }

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
        val beforeSuit = card.suit
        val beforeAmbiguous = card.suitAmbiguous
        val resolved = if (card.suit.isRed) {
            resolveRedSuit(bitmap, region, card)
        } else {
            resolveBlackSuit(bitmap, region, card)
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
        return resolved to (post?.let { trace.withPost(it) } ?: trace)
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
    ): Card {
        if (!card.known || card.suit.isRed) return card
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
            val resolved = if (margin < CardRecognizer.BLACK_SUIT_TOP_TIEBREAK_MAX || preferTopHalf) {
                val (leader, ambiguous) = recognizer.resolveBlackSuitLeader(
                    scores,
                    cardCrop,
                    tiebreakShape
                )
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
            return if (recovered != null) {
                resolved.copy(
                    suit = recovered.first,
                    suitAmbiguous = recovered.second
                )
            } else {
                resolved
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
        inkRegion: BoardRegion? = null
    ): RecognitionHit {
        val key = SlotKey(pile, index)
        val fingerprint = regionFingerprint(bitmap, region)
        slotHitCache[key]?.let { previous ->
            if (previous.fingerprint == fingerprint && regionsSimilar(previous.bounds, region)) {
                cache[key] = previous
                return previous.hit
            }
        }
        val hit = recognizer.recognize(bitmap, region, exactCardBounds, inkRegion)
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
        inferred: Boolean = false
    ): RecognizedSlot = RecognizedSlot(
        pile = pile,
        index = index,
        bounds = bounds,
        engine = slotGuessFromHit(hit, cardOverride),
        confidence = hit.confidence,
        diagnostic = hit.diagnostic,
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

        /**
         * WasteRankCorrections can only ever change the fused rank for these
         * six ranks (correctKingTenOnWaste, correctQueenOnWaste,
         * correctFiveJack, correctJackThree, and ocrRankOverride's
         * isConfusionPair whitelist all gate on a subset of them). For any
         * other waste rank, corner OCR is provably a no-op on the outcome —
         * skip the up-to-12-region ML Kit probe entirely rather than pay its
         * latency for a result nothing downstream can use.
         */
        private val WASTE_OCR_RELEVANT_RANKS =
            setOf(Rank.Three, Rank.Five, Rank.Ten, Rank.Jack, Rank.Queen, Rank.King)
    }
}

private data class ResolvedCascadeSlot(
    val card: Card,
    val trace: RecognitionTrace,
    val diagnostic: String,
    val confidence: Float,
    val inferred: Boolean
)
