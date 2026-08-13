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

    fun updateMinConfidence(value: Float) {
        recognizer.minConfidence = value.coerceIn(0.4f, 0.95f)
    }

    fun setIgnoreUserTemplates(value: Boolean) {
        recognizer.ignoreUserTemplates = value
    }

    fun reloadTemplates() {
        recognizer.reloadTemplates()
    }

    fun recognizerInstance(): CardRecognizer = recognizer

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
        val exactSuitScores = recognizer.suitTemplateScores(
            bitmap,
            tightWasteRegion,
            Suit.entries.toSet()
        )
        val wasteCandidatesDisagree =
            tightWasteHit.card != null &&
                legacyWasteHit.card != null &&
                tightWasteHit.card.id != legacyWasteHit.card.id
        val wasteHit = when {
            wasteCandidatesDisagree ->
                resolveWasteDisagreement(
                    bitmap,
                    tightWasteHit,
                    legacyWasteHit,
                    tightWasteRegion,
                    legacyWasteRegion,
                    exactSuitScores
                )
            tightWasteHit.card != null -> tightWasteHit
            else -> legacyWasteHit
        }
        val wasteRegion = if (wasteHit.diagnostic.contains("waste-resolved-legacy")) {
            legacyWasteRegion
        } else if (tightWasteHit.card != null || wasteHit.card != null) {
            tightWasteRegion
        } else {
            legacyWasteRegion
        }
        locations[PileRef.Waste] = listOf(
            locator.toCardLocation(PileRef.Waste, 0, wasteRegion)
        )
        val wasteCards = listOfNotNull(
            cardFromHit(wasteHit)?.let { card ->
                val refined = recognizer.refineAmbiguousRank(bitmap, wasteRegion, card)
                resolveBlackSuit(bitmap, wasteRegion, refined, exactSuitScores)
            }
        )
        diagnostics += "waste=${wasteHit.diagnostic}:${wasteHit.card}"
        diagnostics +=
            "waste-regions=exact:${"%.0f".format(tightWasteRegion.left)}-" +
            "${"%.0f".format(tightWasteRegion.right)}:${tightWasteHit.diagnostic}," +
            "legacy:${"%.0f".format(legacyWasteRegion.left)}-" +
            "${"%.0f".format(legacyWasteRegion.right)}:${legacyWasteHit.diagnostic}"
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
            val foundationCard = cardFromHit(hit)?.let { card ->
                resolveBlackSuit(bitmap, region, card)
            }
            foundations[index] = listOfNotNull(foundationCard)
            diagnostics += "foundation$index=${hit.diagnostic}:${foundationCard ?: hit.card}"
        }

        locator.tableauColumnRegions(board).forEachIndexed { col, columnRegion ->
            val cards = mutableListOf<Card>()
            val locs = mutableListOf<CardLocation>()
            val cardWidth = columnRegion.width
            val cardHeight = cardWidth * board.profile.cardAspect
            val downStep = cardHeight * board.profile.faceDownOverlap
            val faceRegions = findAllPlayableFaceRegions(bitmap, columnRegion, cardHeight)

            if (faceRegions.isNotEmpty()) {
                val topFace = faceRegions.first()
                // Count only genuinely teal headers above the uppermost face-up card.
                var y = columnRegion.top
                var faceDownCount = 0
                while (y + 4f < topFace.top && faceDownCount < 6) {
                    val strip = BoardRegion(
                        columnRegion.left,
                        y,
                        columnRegion.right,
                        (y + downStep).coerceAtMost(topFace.top)
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

                faceRegions.reversed().forEach { faceRegion ->
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
                        known = false,
                        recognized = false
                    )
                    if (inkRed != null && card.suit.isRed != inkRed) {
                        card = card.copy(suit = if (inkRed) Suit.Hearts else Suit.Spades)
                    }
                    if (card.known && hit.card != null) {
                        card = card.copy(recognized = true)
                    }
                    card = resolveBlackSuit(bitmap, faceRegion, card)
                    card = recognizer.refineAmbiguousRank(bitmap, faceRegion, card)
                    cards += card
                    locs += locator.toCardLocation(PileRef.Tableau(col), cards.lastIndex, faceRegion)
                    if (!card.known) diagnostics += "tableau$col[face]=${hit.diagnostic}"
                }
            }
            tableau[col] = cards
            locations[PileRef.Tableau(col)] = locs
            if (faceRegions.isNotEmpty()) {
                val top = faceRegions.first()
                val bottom = faceRegions.last()
                diagnostics += "tableau$col.region=${"%.1f".format(top.top)}-" +
                    "${"%.1f".format(bottom.bottom)} faces=${faceRegions.size}"
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

        val wasteConfidence = wasteHit.confidence
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
     * Finds every face-up card body in a tableau column. Uses a narrowed horizontal
     * scan so neighbor columns do not bleed into recognition.
     */
    private fun findAllPlayableFaceRegions(
        bitmap: Bitmap,
        column: BoardRegion,
        cardHeight: Float
    ): List<BoardRegion> {
        val scanColumn = narrowColumnRegion(column)
        val rowHeight = (scanColumn.width / 24f).coerceAtLeast(2f)
        var y = scanColumn.top
        var lastWhiteBottom = -1f
        var whiteRows = 0
        var sawCardContent = false
        var blankRows = 0
        val maxBlankRows = (cardHeight * 0.22f / rowHeight).toInt().coerceAtLeast(4)
        val minimumRows = (cardHeight * 0.22f / rowHeight).toInt().coerceAtLeast(3)
        val faceBottoms = mutableListOf<Float>()

        fun commitFace() {
            if (lastWhiteBottom >= 0f && whiteRows >= minimumRows) {
                faceBottoms += lastWhiteBottom
            }
            lastWhiteBottom = -1f
            whiteRows = 0
        }

        while (y < scanColumn.bottom) {
            val row = BoardRegion(
                scanColumn.left,
                y,
                scanColumn.right,
                (y + rowHeight).coerceAtMost(scanColumn.bottom)
            )
            val stats = SmashColorAnalyzer.analyze(bitmap, row)
            val hasCardContent = stats.whiteRatio > 0.18f || stats.tealRatio > 0.12f
            if (hasCardContent) {
                sawCardContent = true
                blankRows = 0
            } else if (sawCardContent) {
                blankRows++
                if (blankRows >= maxBlankRows) {
                    commitFace()
                    sawCardContent = false
                    blankRows = 0
                }
            }
            if (stats.whiteRatio > 0.28f && stats.tealRatio < 0.18f) {
                lastWhiteBottom = row.bottom
                whiteRows++
            }
            y += rowHeight
        }
        commitFace()

        if (faceBottoms.isEmpty()) return emptyList()

        val regions = faceBottoms.map { bottom ->
            val top = (bottom - cardHeight).coerceAtLeast(column.top)
            BoardRegion(
                column.left,
                top,
                column.right,
                (top + cardHeight).coerceAtMost(column.bottom)
            )
        }
        return mergeOverlappingFaceRegions(regions, cardHeight)
    }

    /** Rank ink can split one card into two bands — merge bands closer than half a card. */
    private fun mergeOverlappingFaceRegions(
        faces: List<BoardRegion>,
        cardHeight: Float
    ): List<BoardRegion> {
        if (faces.size <= 1) return faces
        val sorted = faces.sortedBy { it.top }
        val merged = mutableListOf<BoardRegion>()
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.top - current.bottom < cardHeight * 0.35f) {
                current = if (next.bottom >= current.bottom) next else current
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun narrowColumnRegion(column: BoardRegion, fraction: Float = 0.74f): BoardRegion {
        val inset = column.width * (1f - fraction) / 2f
        return BoardRegion(
            left = column.left + inset,
            top = column.top,
            right = column.right - inset,
            bottom = column.bottom
        )
    }

    private fun resolveWasteDisagreement(
        bitmap: Bitmap,
        tight: RecognitionHit,
        legacy: RecognitionHit,
        tightRegion: BoardRegion,
        legacyRegion: BoardRegion,
        suitScores: Map<Suit, Float>
    ): RecognitionHit {
        val tightCard = tight.card
        val legacyCard = legacy.card
        when {
            tightCard == null -> return legacy
            legacyCard == null -> return tight
            tightCard.id == legacyCard.id ->
                return if (tight.confidence >= legacy.confidence) tight else legacy
            tightCard.rank == legacyCard.rank ->
                return if (tight.confidence >= legacy.confidence) tight else legacy
        }

        val ranks = setOf(tightCard.rank, legacyCard.rank)
        val tightScores = recognizer.exactRankTemplateScores(bitmap, tightRegion, ranks)
        val legacyScores = recognizer.exactRankTemplateScores(bitmap, legacyRegion, ranks)
        val combined = ranks.associateWith { rank ->
            maxOf(tightScores[rank] ?: 0f, legacyScores[rank] ?: 0f)
        }
        val bestEntry = combined.maxByOrNull { it.value }
            ?: return if (tight.confidence >= legacy.confidence) tight else legacy
        val secondBest = combined.filterKeys { it != bestEntry.key }.maxOfOrNull { it.value } ?: 0f
        if (bestEntry.value - secondBest < 0.03f) {
            // Truly tied — fall back to the stronger of the two original hits so a
            // hint can still be produced instead of dropping the waste card.
            return if (tight.confidence >= legacy.confidence) tight else legacy
        }

        val preferTight = (tightScores[bestEntry.key] ?: 0f) >= (legacyScores[bestEntry.key] ?: 0f)
        val region = if (preferTight) tightRegion else legacyRegion
        val baseSuit = if (preferTight) tightCard.suit else legacyCard.suit
        var resolved = Card(
            bestEntry.key,
            baseSuit,
            faceUp = true,
            known = true,
            recognized = true
        )
        resolved = resolveBlackSuit(bitmap, region, resolved, suitScores)
        resolved = recognizer.refineAmbiguousRank(bitmap, region, resolved)
        val tag = if (preferTight) "tight" else "legacy"
        return RecognitionHit(
            card = resolved,
            confidence = bestEntry.value,
            isFaceDown = false,
            isEmpty = false,
            diagnostic = "waste-resolved-$tag-${resolved.rank.name}-${resolved.suit.name}@" +
                "%.2f".format(bestEntry.value),
            inferredRed = resolved.suit.isRed
        )
    }

    /**
     * Lowest visible face in a column — last entry from [findAllPlayableFaceRegions].
     */
    private fun findPlayableFaceRegion(
        bitmap: Bitmap,
        column: BoardRegion,
        cardHeight: Float
    ): BoardRegion? = findAllPlayableFaceRegions(bitmap, column, cardHeight).lastOrNull()

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

    private fun resolveBlackSuit(
        bitmap: Bitmap,
        region: BoardRegion,
        card: Card,
        suitScores: Map<Suit, Float>? = null
    ): Card {
        if (!card.known || card.suit.isRed) return card
        val scores = suitScores ?: recognizer.suitTemplateScores(
            bitmap,
            region,
            setOf(Suit.Clubs, Suit.Spades)
        )
        val clubScore = scores[Suit.Clubs] ?: 0f
        val spadeScore = scores[Suit.Spades] ?: 0f
        val lowRank = card.rank.value <= Rank.Three.value
        val minScore = if (lowRank) 0.48f else 0.55f
        val margin = if (lowRank) 0.02f else 0.04f
        if (clubScore < minScore && spadeScore < minScore) return card
        return when {
            clubScore - spadeScore >= margin -> card.copy(suit = Suit.Clubs)
            spadeScore - clubScore >= margin -> card.copy(suit = Suit.Spades)
            else -> card
        }
    }

    private fun cardFromHit(hit: RecognitionHit): Card? {
        if (hit.isEmpty || hit.isFaceDown) return null
        hit.card?.let { return it.copy(recognized = true) }
        // Face-up occupancy without a confident rank/suit match.
        val suit = when (hit.inferredRed) {
            true -> Suit.Hearts
            false -> Suit.Spades
            null -> Suit.Clubs
        }
        return Card(Rank.Ace, suit, faceUp = true, known = false, recognized = false)
    }
}
