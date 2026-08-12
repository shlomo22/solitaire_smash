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
import kotlin.math.roundToInt

data class DetectionResult(
    val state: GameState?,
    val locations: Map<PileRef, List<CardLocation>>,
    val confidence: Float,
    val diagnostics: List<String>,
    val board: LocatedBoard?
)

class GameStateDetector(
    context: Context,
    minConfidence: Float = 0.65f
) {
    private val locator = BoardLocator()
    private val recognizer = CardRecognizer(context, minConfidence)

    @Suppress("UNUSED_PARAMETER")
    fun updateMinConfidence(value: Float) {
        // Recognizer is constructed with a threshold; pipeline passes settings for future rebuilds.
    }

    fun detect(bitmap: Bitmap): DetectionResult {
        val board = locator.locate(bitmap)
        val diagnostics = mutableListOf<String>()
        diagnostics += "boardConfidence=${"%.2f".format(board.confidence)}"
        diagnostics += "profile=${board.profile.name}"

        val locations = linkedMapOf<PileRef, List<CardLocation>>()
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
        val stockCards = when {
            stockHit.isEmpty -> emptyList()
            else -> listOf(Card(Rank.Ace, Suit.Spades, faceUp = false, known = false))
        }
        diagnostics += "stock=${stockHit.diagnostic}"

        val tightWasteRegion = locateWasteTopRegion(bitmap, board)
        val tightWasteHit = recognizer.recognize(
            bitmap,
            tightWasteRegion,
            exactCardBounds = true
        )
        val legacyWasteRegion = locator.wasteTopRegion(board)
        val legacyWasteHit = recognizer.recognize(bitmap, legacyWasteRegion)
        val exactRankScores = recognizer.exactRankTemplateScores(
            bitmap,
            tightWasteRegion,
            Rank.entries.toSet()
        )
        val exactSuitScores = recognizer.suitTemplateScores(
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
        val baseCard = legacyCard ?: tightCard
        val rankedExactSuits = exactSuitScores.entries.sortedByDescending { it.value }
        val exactSuitBest = rankedExactSuits.firstOrNull()
        val exactSuitSecond = rankedExactSuits.getOrNull(1)?.value ?: 0f
        val authoritativeExactSuit = exactSuitBest
            ?.takeIf { it.value >= 0.80f && it.value - exactSuitSecond >= 0.04f }
            ?.key
        val exactTenOverride =
            legacyCard?.rank == Rank.King &&
                (exactRankScores[Rank.Ten] ?: 0f) >= 0.90f
        val exactQueenOverride =
            legacyCard?.rank == Rank.Ten &&
                (exactRankScores[Rank.Queen] ?: 0f) >= 0.90f
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
        val correctedRank = when {
            exactTenOverride -> Rank.Ten
            exactQueenOverride -> Rank.Queen
            exactEightSpadeOverride -> Rank.Eight
            exactFourOverride -> Rank.Four
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
            else -> baseCard?.rank
        }
        val correctedSuit = if ((exactTenOverride ||
                exactQueenOverride ||
                exactEightSpadeOverride) &&
            authoritativeExactSuit != null
        ) {
            authoritativeExactSuit
        } else if (exactFourOverride) {
            Suit.Diamonds
        } else {
            tightCard?.suit ?: baseCard?.suit
        }
        val fusedCard = if (baseCard != null && correctedRank != null) {
            baseCard.copy(
                rank = correctedRank,
                suit = correctedSuit ?: baseCard.suit
            )
        } else {
            null
        }
        val wasteHit = if (fusedCard != null) {
            RecognitionHit(
                card = fusedCard,
                confidence = maxOf(legacyWasteHit.confidence, tightWasteHit.confidence),
                isFaceDown = false,
                isEmpty = false,
                diagnostic = "fused-${fusedCard.rank.name}-${fusedCard.suit.name}",
                inferredRed = fusedCard.suit.isRed
            )
        } else {
            legacyWasteHit
        }
        val wasteRegion = if (fusedCard != null) tightWasteRegion else legacyWasteRegion
        val wasteCandidatesDisagree =
            tightWasteHit.card != null &&
                legacyWasteHit.card != null &&
                tightWasteHit.card.id != legacyWasteHit.card.id
        locations[PileRef.Waste] = listOf(
            locator.toCardLocation(PileRef.Waste, 0, wasteRegion)
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

        locator.foundationRegions(board).forEachIndexed { index, region ->
            val hit = recognizer.recognize(bitmap, region)
            locations[PileRef.Foundation(index)] = listOf(
                locator.toCardLocation(PileRef.Foundation(index), 0, region)
            )
            foundations[index] = listOfNotNull(cardFromHit(hit))
            diagnostics += "foundation$index=${hit.diagnostic}:${hit.card}"
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
                        faceDownCount++
                    }
                    y += downStep
                }

                val hit = recognizer.recognize(bitmap, faceRegion)
                val inkRed = hit.inferredRed ?: hit.card?.suit?.isRed
                var card = cardFromHit(hit) ?: Card(
                    Rank.Ace,
                    when (inkRed) {
                        true -> Suit.Hearts
                        false -> Suit.Spades
                        null -> Suit.Clubs
                    },
                    faceUp = true,
                    known = false
                )
                if (inkRed != null && card.suit.isRed != inkRed) {
                    card = card.copy(suit = if (inkRed) Suit.Hearts else Suit.Spades)
                }
                if (card.known && !card.suit.isRed) {
                    val suitScores = recognizer.suitTemplateScores(
                        bitmap,
                        faceRegion,
                        setOf(Suit.Clubs, Suit.Spades)
                    )
                    val clubScore = suitScores[Suit.Clubs] ?: 0f
                    val spadeScore = suitScores[Suit.Spades] ?: 0f
                    card = when {
                        card.rank in setOf(Rank.Two, Rank.Five) &&
                            clubScore >= 0.65f &&
                            spadeScore - clubScore <= 0.15f ->
                            card.copy(suit = Suit.Clubs)
                        spadeScore >= 0.68f && spadeScore - clubScore >= 0.015f ->
                            card.copy(suit = Suit.Spades)
                        card.rank in setOf(Rank.Three, Rank.Four) &&
                            spadeScore >= 0.80f &&
                            clubScore - spadeScore <= 0.01f ->
                            card.copy(suit = Suit.Spades)
                        else -> card
                    }
                }

                // Reconstruct the overlapped face-up run geometrically. Sampling
                // colored header strips missed leading cards in long cascades;
                // card spacing is fixed and every legal tableau run descends
                // while alternating color.
                val faceUpStep = cardHeight * board.profile.faceUpOverlap
                var firstFaceTop = columnRegion.top + faceDownCount * downStep
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
                    val boundaryHit = recognizer.recognize(bitmap, bounds)
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
                    faceDownCount++
                    firstFaceTop += downStep
                }
                val faceUpDistance = (faceRegion.top - firstFaceTop) / faceUpStep
                var faceUpCount = (faceUpDistance.roundToInt() + 1)
                    .coerceIn(1, Rank.entries.size)
                if (card.known) {
                    val leadingRegion = BoardRegion(
                        columnRegion.left,
                        firstFaceTop,
                        columnRegion.right,
                        (firstFaceTop + cardHeight).coerceAtMost(columnRegion.bottom)
                    )
                    val leadingHit = recognizer.recognize(
                        bitmap,
                        leadingRegion,
                        exactCardBounds = true
                    )
                    val leadingCard = leadingHit.card
                    val leadingHeaderStats = SmashColorAnalyzer.analyze(
                        bitmap,
                        BoardRegion(
                            columnRegion.left,
                            firstFaceTop,
                            columnRegion.right,
                            (firstFaceTop + faceUpStep * 0.9f)
                                .coerceAtMost(columnRegion.bottom)
                        )
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
                        else -> leadingHit.inferredRed ?: leadingCard?.suit?.isRed
                    }
                    if (leadingRed != null &&
                        leadingRed != expectedLeadingRed &&
                        card.rank.value + faceUpCount <= Rank.King.value
                    ) {
                        faceUpCount++
                    }
                    if (leadingCard?.known == true &&
                        leadingCard.rank.value >= card.rank.value
                    ) {
                        val rankCount = leadingCard.rank.value - card.rank.value + 1
                        val countDifference = rankCount - faceUpCount
                        if (rankCount in 1..Rank.entries.size &&
                            countDifference == 2
                        ) {
                            faceUpCount = rankCount
                        }
                    }
                }
                repeat(faceUpCount - 1) { exposedIndex ->
                    val distanceFromBottom = faceUpCount - 1 - exposedIndex
                    val inferredValue = card.rank.value + distanceFromBottom
                    val inferredRed = if (distanceFromBottom % 2 == 0) {
                        card.suit.isRed
                    } else {
                        !card.suit.isRed
                    }
                    val known = card.known && inferredValue <= Rank.King.value
                    val inferred = Card(
                        rank = if (known) Rank.fromValue(inferredValue) else Rank.Ace,
                        suit = if (inferredRed) Suit.Hearts else Suit.Spades,
                        faceUp = true,
                        known = known
                    )
                    val top = firstFaceTop + exposedIndex * faceUpStep
                    val bounds = BoardRegion(
                        columnRegion.left,
                        top,
                        columnRegion.right,
                        (top + cardHeight).coerceAtMost(columnRegion.bottom)
                    )
                    cards += inferred
                    locs += locator.toCardLocation(
                        PileRef.Tableau(col),
                        cards.lastIndex,
                        bounds
                    )
                }
                cards += card
                locs += locator.toCardLocation(PileRef.Tableau(col), cards.lastIndex, faceRegion)
                if (!card.known) diagnostics += "tableau$col[top]=${hit.diagnostic}"
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
        val state = GameState(
            tableau = tableau,
            foundations = foundations,
            stock = stockCards,
            waste = wasteCards
        )

        val totalCards = tableau.sumOf { it.size } + foundations.sumOf { it.size } +
            wasteCards.size + stockCards.size
        if (totalCards == 0) {
            return DetectionResult(
                state = null,
                locations = locations,
                confidence = avg,
                diagnostics = diagnostics + "empty-board",
                board = board
            )
        }

        val knownFaceUp = tableau.sumOf { col -> col.count { it.faceUp && it.known } } +
            foundations.sumOf { pile -> pile.count { it.known } } +
            wasteCards.count { it.known }
        diagnostics += "knownFaceUp=$knownFaceUp"

        return DetectionResult(
            state = state,
            locations = locations,
            confidence = avg,
            diagnostics = diagnostics,
            board = board
        )
    }

    /**
     * Finds the right edge of the front waste card. The fan grows rightward,
     * so a fixed crop alternates between the second and third card.
     */
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

    private fun cardFromHit(hit: RecognitionHit): Card? {
        if (hit.isEmpty || hit.isFaceDown) return null
        hit.card?.let { return it }
        // Face-up occupancy without a confident rank/suit match.
        val suit = when (hit.inferredRed) {
            true -> Suit.Hearts
            false -> Suit.Spades
            null -> Suit.Clubs
        }
        return Card(Rank.Ace, suit, faceUp = true, known = false)
    }
}
