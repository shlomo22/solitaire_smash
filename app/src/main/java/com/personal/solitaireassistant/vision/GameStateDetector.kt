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

        val tightWasteRegion = locator.tightWasteTopRegion(board)
        val tightWasteHit = recognizer.recognize(bitmap, tightWasteRegion)
        val legacyWasteRegion = locator.wasteTopRegion(board)
        val legacyWasteHit = recognizer.recognize(bitmap, legacyWasteRegion)
        // The tight crop contains the complete front card, including its corner
        // rank and suit badge. Use the clipped legacy crop only as a fallback;
        // its confidence can be high even when it is reading the large glyph
        // or ink from the wrong fanned card.
        val (wasteRegion, wasteHit) = if (
            tightWasteHit.card != null && tightWasteHit.confidence >= 0.56f
        ) {
            tightWasteRegion to tightWasteHit
        } else {
            legacyWasteRegion to legacyWasteHit
        }
        locations[PileRef.Waste] = listOf(
            locator.toCardLocation(PileRef.Waste, 0, wasteRegion)
        )
        val wasteCards = listOfNotNull(cardFromHit(wasteHit))
        diagnostics += "waste=${wasteHit.diagnostic}:${wasteHit.card}"

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
                    if (boundaryStats.tealRatio <= 0.18f ||
                        boundaryStats.whiteRatio >= 0.28f
                    ) {
                        break
                    }
                    val bounds = BoardRegion(
                        columnRegion.left,
                        firstFaceTop,
                        columnRegion.right,
                        (firstFaceTop + cardHeight).coerceAtMost(columnRegion.bottom)
                    )
                    cards += Card(Rank.Ace, Suit.Clubs, faceUp = false, known = false)
                    locs += locator.toCardLocation(
                        PileRef.Tableau(col),
                        cards.lastIndex,
                        bounds
                    )
                    faceDownCount++
                    firstFaceTop += downStep
                }
                val faceUpCount = (
                    ((faceRegion.top - firstFaceTop) / faceUpStep).roundToInt() + 1
                    ).coerceIn(1, Rank.entries.size)
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

        val confidences = mutableListOf(board.confidence, stockHit.confidence, wasteHit.confidence)
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
