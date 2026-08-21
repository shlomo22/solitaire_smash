package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit

/**
 * Solitaire uses one deck: each rank+suit appears at most once among known cards.
 * When independent slot reads collide (often H↔D or C↔S), pick assignments that
 * minimize duplicates using per-slot template scores.
 */
object DeckConstraintPass {
    private data class Entry(
        val pile: PileRef,
        val cardIndex: Int,
        val bounds: BoardRegion,
        var card: Card,
        val confidence: Float,
        val recognizedIndex: Int
    )

    fun apply(
        bitmap: Bitmap,
        recognizer: CardRecognizer,
        state: GameState,
        recognizedSlots: MutableList<RecognizedSlot>
    ): GameState {
        val tableau = state.tableau.map { it.toMutableList() }.toMutableList()
        val foundations = state.foundations.map { it.toMutableList() }.toMutableList()
        val waste = state.waste.toMutableList()

        val entries = collectEntries(tableau, foundations, waste, recognizedSlots)
        if (entries.size < 2) {
            return state
        }

        val resolvedByDedup = resolveDuplicateCardIds(bitmap, recognizer, entries)
        resolvePartnerSuitSwaps(bitmap, recognizer, entries, resolvedByDedup)

        entries.forEach { entry ->
            setCard(tableau, foundations, waste, entry.pile, entry.cardIndex, entry.card)
            val slot = recognizedSlots[entry.recognizedIndex]
            val beforeLabel = slot.engine.shortLabel()
            val afterLabel = slotGuessFromCard(entry.card).shortLabel()
            val updatedTrace = if (beforeLabel != afterLabel) {
                slot.trace.withPost("deck-constraint:$beforeLabel->$afterLabel")
            } else {
                slot.trace
            }
            recognizedSlots[entry.recognizedIndex] = slot.copy(
                engine = slotGuessFromCard(entry.card),
                trace = updatedTrace
            )
        }

        return state.copy(
            tableau = tableau.map { it.toList() },
            foundations = foundations.map { it.toList() },
            waste = waste.toList()
        )
    }

    private fun collectEntries(
        tableau: List<MutableList<Card>>,
        foundations: List<MutableList<Card>>,
        waste: MutableList<Card>,
        recognizedSlots: List<RecognizedSlot>
    ): MutableList<Entry> {
        val entries = mutableListOf<Entry>()
        recognizedSlots.forEachIndexed { recognizedIndex, slot ->
            if (slot.inferred) return@forEachIndexed
            if (slot.engine.kind != SlotKind.FaceUp) return@forEachIndexed
            val rank = slot.engine.rank ?: return@forEachIndexed
            val suit = slot.engine.suit ?: return@forEachIndexed
            val card = getCard(tableau, foundations, waste, slot.pile, slot.index)
                ?: return@forEachIndexed
            if (!card.faceUp || !card.known) return@forEachIndexed
            entries += Entry(
                pile = slot.pile,
                cardIndex = slot.index,
                bounds = slot.bounds,
                card = card.copy(rank = rank, suit = suit),
                confidence = slot.confidence,
                recognizedIndex = recognizedIndex
            )
        }
        return entries
    }

    private fun resolvePartnerSuitSwaps(
        bitmap: Bitmap,
        recognizer: CardRecognizer,
        entries: MutableList<Entry>,
        resolvedByDedup: Set<Int>
    ) {
        entries.groupBy { it.card.rank }.forEach { (_, group) ->
            if (group.size != 2) return@forEach
            val first = group[0]
            val second = group[1]
            // resolveDuplicateCardIds already disambiguated this exact pair using
            // the suitAmbiguous flag from the original read - a signal this pass
            // doesn't have. Re-scoring from fresh template matches here was
            // silently undoing that decision (real case: a duplicate King of
            // Spades got correctly split into KC/KS by dedup, then swapped right
            // back to the wrong pair by this pass using the same misleading raw
            // scores that caused the duplicate in the first place).
            if (first.recognizedIndex in resolvedByDedup || second.recognizedIndex in resolvedByDedup) {
                return@forEach
            }
            if (first.card.suit.isRed != second.card.suit.isRed) return@forEach
            if (first.card.suit == second.card.suit) return@forEach

            val suits = if (first.card.suit.isRed) {
                setOf(Suit.Hearts, Suit.Diamonds)
            } else {
                setOf(Suit.Clubs, Suit.Spades)
            }
            val firstScores = recognizer.suitTemplateScores(bitmap, first.bounds, suits)
            val secondScores = recognizer.suitTemplateScores(bitmap, second.bounds, suits)
            val firstAlt = partnerSuit(first.card.suit)
            val secondAlt = partnerSuit(second.card.suit)
            val direct =
                suitScore(firstScores, first.card.suit) + suitScore(secondScores, second.card.suit)
            val swapped =
                suitScore(firstScores, firstAlt) + suitScore(secondScores, secondAlt)
            val swapThreshold = if (first.card.suit.isRed) RED_SUIT_SWAP_THRESHOLD else BLACK_SUIT_SWAP_THRESHOLD
            if (swapped <= direct + swapThreshold) return@forEach
            if (first.card.suit.isRed && !redSwapShapeAgrees(bitmap, first.bounds, second.bounds, firstAlt, secondAlt)) {
                return@forEach
            }
            if (!first.card.suit.isRed && !blackSwapShapeAgrees(bitmap, first.bounds, second.bounds, firstAlt, secondAlt)) {
                return@forEach
            }

            first.card = first.card.copy(suit = firstAlt, suitAmbiguous = false)
            second.card = second.card.copy(suit = secondAlt, suitAmbiguous = false)
        }
    }

    private fun partnerSuit(suit: Suit): Suit = when (suit) {
        Suit.Hearts -> Suit.Diamonds
        Suit.Diamonds -> Suit.Hearts
        Suit.Clubs -> Suit.Spades
        Suit.Spades -> Suit.Clubs
    }

    private fun suitScore(scores: Map<Suit, Float>, suit: Suit): Float =
        scores[suit] ?: 0f

    private fun redSwapShapeAgrees(
        bitmap: Bitmap,
        firstBounds: BoardRegion,
        secondBounds: BoardRegion,
        firstAlt: Suit,
        secondAlt: Suit
    ): Boolean {
        val firstShape = redShapeGuess(bitmap, firstBounds)?.suit
        val secondShape = redShapeGuess(bitmap, secondBounds)?.suit
        if (firstShape == null && secondShape == null) return true
        if (firstShape != null && firstShape != firstAlt) return false
        if (secondShape != null && secondShape != secondAlt) return false
        return true
    }

    private fun redShapeGuess(bitmap: Bitmap, bounds: BoardRegion): SuitBadgeHeuristics.Guess? {
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
        if (right - left < 8 || bottom - top < 8) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return try {
            SuitBadgeHeuristics.guessRedSuit(crop)?.takeIf { it.margin >= 0.12f }
        } finally {
            crop.recycle()
        }
    }

    private fun blackSwapShapeAgrees(
        bitmap: Bitmap,
        firstBounds: BoardRegion,
        secondBounds: BoardRegion,
        firstAlt: Suit,
        secondAlt: Suit
    ): Boolean {
        val firstShape = blackShapeGuess(bitmap, firstBounds)?.suit
        val secondShape = blackShapeGuess(bitmap, secondBounds)?.suit
        if (firstShape == null && secondShape == null) return true
        if (firstShape != null && firstShape != firstAlt) return false
        if (secondShape != null && secondShape != secondAlt) return false
        return true
    }

    private fun blackShapeGuess(bitmap: Bitmap, bounds: BoardRegion): SuitBadgeHeuristics.Guess? {
        val left = bounds.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = bounds.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, bitmap.height)
        if (right - left < 8 || bottom - top < 8) return null
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return try {
            SuitBadgeHeuristics.guessBlackSuit(crop, CardRecognizer.TOP_BLACK_SHAPE_VETO_MARGIN)
                ?.takeIf { it.margin >= CardRecognizer.TOP_BLACK_SHAPE_VETO_MARGIN }
        } finally {
            crop.recycle()
        }
    }

    private fun resolveDuplicateCardIds(
        bitmap: Bitmap,
        recognizer: CardRecognizer,
        entries: MutableList<Entry>
    ): Set<Int> {
        val used = entries.map { it.card.id }.toMutableSet()
        val byId = entries.groupBy { it.card.id }
        val resolvedIndices = mutableSetOf<Int>()
        byId.filter { it.value.size > 1 }.forEach { (_, group) ->
            resolvedIndices += group.map { it.recognizedIndex }
            // Confidence alone ties two independently-confident reads more
            // often than it should - a real duplicate King of Spades showed
            // both slots at rank-ocr@0.62 while one's suit read was already
            // flagged suitAmbiguous (its own black-tiebreak had bailed to
            // "ambiguous") and the other's wasn't. sortedByDescending is
            // stable, so a tie silently kept whichever slot happened to come
            // first in column order and reassigned the other - in that case
            // the wrong one, since the non-ambiguous read was the reliable
            // one. Prefer keeping non-ambiguous reads untouched; only fall
            // back to raw confidence within the same ambiguity tier.
            group.sortedWith(
                compareByDescending<Entry> { !it.card.suitAmbiguous }
                    .thenByDescending { it.confidence }
            ).drop(1).forEach { entry ->
                val oldId = entry.card.id
                val replacement = bestAlternateAssignment(
                    bitmap = bitmap,
                    recognizer = recognizer,
                    entry = entry,
                    usedIds = used,
                    preferSameRank = true,
                    requireShapeAgreement = entry.pile is PileRef.Foundation
                ) ?: entry.card.copy(suitAmbiguous = true)
                used.remove(oldId)
                used.add(replacement.id)
                entry.card = replacement
            }
        }
        return resolvedIndices
    }

    private fun bestAlternateAssignment(
        bitmap: Bitmap,
        recognizer: CardRecognizer,
        entry: Entry,
        usedIds: Set<String>,
        preferSameRank: Boolean,
        forceSuit: Suit? = null,
        requireShapeAgreement: Boolean = false
    ): Card? {
        val rank = entry.card.rank
        val candidates = mutableListOf<Card>()
        val suits = when {
            forceSuit != null -> listOf(forceSuit)
            entry.card.suit.isRed -> listOf(Suit.Hearts, Suit.Diamonds)
            else -> listOf(Suit.Clubs, Suit.Spades)
        }
        suits.forEach { suit ->
            if (suit == entry.card.suit) return@forEach
            val card = entry.card.copy(suit = suit, suitAmbiguous = false)
            if (card.id in usedIds) return@forEach
            if (preferSameRank && card.rank != rank) return@forEach
            candidates += card
        }
        if (candidates.isEmpty()) return null

        val scores = recognizer.suitTemplateScores(
            bitmap,
            entry.bounds,
            suits.toSet()
        )
        val currentScore = scores[entry.card.suit] ?: 0f
        val pick = candidates.maxByOrNull { candidate ->
            val suitScore = scores[candidate.suit] ?: 0f
            suitScore + entry.confidence * 0.05f
        } ?: return null
        val altScore = scores[pick.suit] ?: 0f
        val minGain = if (requireShapeAgreement) 0.10f else 0.04f
        val chosen = when {
            candidates.size == 1 &&
                entry.confidence >= 0.70f &&
                currentScore >= altScore + 0.08f &&
                currentScore >= 0.58f -> null
            candidates.size == 1 -> pick
            altScore >= currentScore + minGain -> pick
            !requireShapeAgreement &&
                altScore >= 0.52f &&
                altScore >= currentScore + 0.035f -> pick
            else -> null
        } ?: return null
        if (requireShapeAgreement && !entry.card.suit.isRed) {
            val shape = blackShapeGuess(bitmap, entry.bounds)?.suit
            if (shape != null && shape != chosen.suit) return null
        }
        return chosen
    }

    private fun getCard(
        tableau: List<MutableList<Card>>,
        foundations: List<MutableList<Card>>,
        waste: MutableList<Card>,
        pile: PileRef,
        index: Int
    ): Card? = when (pile) {
        is PileRef.Tableau -> tableau.getOrNull(pile.index)?.getOrNull(index)
        is PileRef.Foundation -> foundations.getOrNull(pile.index)?.getOrNull(index)
        PileRef.Waste -> waste.getOrNull(index)
        else -> null
    }

    private fun setCard(
        tableau: MutableList<MutableList<Card>>,
        foundations: MutableList<MutableList<Card>>,
        waste: MutableList<Card>,
        pile: PileRef,
        index: Int,
        card: Card
    ) {
        when (pile) {
            is PileRef.Tableau ->
                tableau.getOrNull(pile.index)?.set(index, card)
            is PileRef.Foundation ->
                foundations.getOrNull(pile.index)?.set(index, card)
            PileRef.Waste ->
                if (index in waste.indices) waste[index] = card
            else -> Unit
        }
    }

    private const val RED_SUIT_SWAP_THRESHOLD = 0.10f
    private const val BLACK_SUIT_SWAP_THRESHOLD = 0.10f
}
