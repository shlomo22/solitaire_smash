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
        val recognizedIndex: Int,
        val originalDiagnostic: String,
        val originalSuitScore: Float?
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
                recognizedIndex = recognizedIndex,
                originalDiagnostic = slot.diagnostic,
                originalSuitScore = slot.trace.suitScore
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
            // Two same-rank, same-color cards with different suits (a Seven
            // of Spades and a Seven of Clubs, say) is the ordinary, valid
            // board state - not a collision to resolve. A real device log
            // showed a tiebreak-confirmed 0.84-confidence Seven of Spades
            // (CardRecognizer's own black-suit tiebreak had already run and
            // committed, neither entry suitAmbiguous) get flipped to Seven
            // of Clubs here purely because the swapped score sum edged out
            // the direct sum on this pass's own cruder suitTemplateScores
            // comparison, which - unlike the tiebreak - has no shape-veto or
            // ink-ratio signal. Only reconsider a pair when at least one
            // side was already flagged uncertain by that tiebreak.
            if (!first.card.suitAmbiguous && !second.card.suitAmbiguous) return@forEach
            // Two real device traces (both after the suitAmbiguous gate above)
            // caught this pass flipping a card whose ORIGINAL suit read was
            // already strong: waste Four of Diamonds (trace.suitScore=1.00)
            // got flipped to Four of Hearts, and tableau Seven of Spades
            // (trace.suitScore=0.83) got flipped to Seven of Clubs - purely
            // because summing with an uncertain partner's scores crossed the
            // swap threshold below. The first attempt at this guard
            // re-scored both sides fresh via suitTemplateScores on
            // entry.bounds and compared THAT number to a floor, but a real
            // device Evaluate run showed the 4D case still swap even after
            // that fix shipped - this pass's own rescore evidently comes
            // back lower than the original recognition's number for at
            // least some crops (the two calls aren't guaranteed to agree:
            // different call site, possibly a different badge-location
            // result). Use the ORIGINAL recognition's own suitScore (stored
            // on the slot's trace at the time it was actually recognized -
            // literally the number the mismatch trace prints as
            // "suit=source@X") instead of re-deriving a possibly-different
            // one here.
            if ((first.originalSuitScore ?: 0f) >= CONFIDENT_CURRENT_SUIT_FLOOR ||
                (second.originalSuitScore ?: 0f) >= CONFIDENT_CURRENT_SUIT_FLOOR
            ) {
                return@forEach
            }

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
            // both slots at rank-ocr@0.62 with one's suit read originally
            // flagged suitAmbiguous (its own black-tiebreak had bailed to
            // "ambiguous") and the other's not. card.suitAmbiguous itself
            // isn't safe to compare here: GameStateDetector re-resolves black
            // suits a second time later in the pipeline (cascade cards get a
            // fresh black-tiebreak2 pass), which can clear or set the flag
            // *after* this diagnostic string was frozen - in the real case
            // both entries' suitAmbiguous had converged to false by the time
            // this pass saw them, so that comparison was a dead tie and fell
            // through to column order, silently keeping the wrong one. The
            // diagnostic string is the immutable record of the original
            // best-match call, so use its "-ambiguous" suffix directly.
            // sortedByDescending is stable, so within a tie fall back to
            // whatever card.suitAmbiguous still shows, then raw confidence.
            group.sortedWith(
                compareByDescending<Entry> { !it.originalDiagnostic.contains("-ambiguous") }
                    .thenByDescending { !it.card.suitAmbiguous }
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
        // Same fix as resolvePartnerSuitSwaps's CONFIDENT_CURRENT_SUIT_FLOOR: a real
        // device trace showed a waste Four of Diamonds with trace.suitScore=1.00 (the
        // ORIGINAL recognition's own confident number) still get reassigned to Four of
        // Hearts here, because when only one alternate suit exists (the common
        // same-color-duplicate case), this "should we even reassign" guard used a
        // freshly re-derived currentScore that came back lower than 1.00 for the same
        // crop - the two suitTemplateScores calls aren't guaranteed to agree. Prefer
        // the entry's original, already-validated suit score when we have one.
        val trustedCurrentScore = entry.originalSuitScore ?: currentScore
        // entry.confidence >= 0.70f used to gate this branch too, but that field is
        // the card's overall/rank confidence, not a suit signal - on the real 4D case
        // it sat at 0.69 (a weak OCR-assisted rank read) even though the suit read
        // itself was a confident 1.00, so the whole branch short-circuited to false
        // before trustedCurrentScore was ever consulted and the card still got
        // reassigned. Suit trust should be judged by the suit score alone.
        //
        // Comparing trustedCurrentScore against altScore (below) still mixes scales
        // when originalSuitScore is set: altScore comes from the FRESH
        // suitTemplateScores call a few lines up, and a real trace (tableau Seven of
        // Spades, originalSuitScore=0.83) showed that fresh call return a HIGHER
        // number (0.86) for the alternate suit than the original recognition ever
        // reported for the true one - so the margin check below still let it swap to
        // Seven of Clubs despite 0.83 being well above CONFIDENT_CURRENT_SUIT_FLOOR.
        // When we have a genuine original score, judge it against that same absolute
        // floor already validated in resolvePartnerSuitSwaps instead of a margin
        // against a differently-scaled fresh number.
        //
        // That floor alone caused a regression on the very next device run: a real
        // Jack of Spades (originalSuitScore=0.75, right at the floor) had originally
        // been misread as Jack of Clubs by CardRecognizer's own shape-veto override,
        // even though the raw suit scores at read time already favored Spades
        // (S:0.92 vs C:0.87) - the shape veto was itself the mistake here, not this
        // pass. Blocking reassignment unconditionally at the floor threw away that
        // "the alternate is dramatically better" signal entirely. Keep the floor as
        // the default, but still allow a swap when the fresh alternate score clears
        // it by a wide enough margin to indicate the original read was genuinely
        // wrong rather than just close/noisy.
        val chosen = when {
            candidates.size == 1 &&
                trustedCurrentScore >= CONFIDENT_CURRENT_SUIT_FLOOR &&
                altScore < trustedCurrentScore + STRONG_ALT_OVERRIDE_MARGIN -> null
            candidates.size == 1 &&
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
    // The two real cases this guards against had original trace.suitScore of
    // 1.00 (waste Four of Diamonds) and 0.83 (tableau Seven of Spades) -
    // genuinely ambiguous reads seen throughout this project's real device
    // logs (suit-ambiguous / black-tiebreak2 "ambiguous" branches) cluster
    // well below 0.70. 0.75 sits with real margin on both sides instead of
    // right at the edge of the 0.83 case.
    private const val CONFIDENT_CURRENT_SUIT_FLOOR = 0.75f
    // How much higher a freshly re-derived alternate-suit score must be than a
    // trusted original score before it's allowed to override CONFIDENT_CURRENT_SUIT_FLOOR
    // in bestAlternateAssignment. The real case that needed this: a Jack of Spades
    // misread as Jack of Clubs by a shape-veto at original-recognition time
    // (originalSuitScore=0.75, right at the floor) while the fresh rescore of the
    // same crop favored Spades by 0.17 (0.92 vs 0.75) - a gap too wide to be normal
    // measurement noise between the two calls.
    private const val STRONG_ALT_OVERRIDE_MARGIN = 0.15f
}
