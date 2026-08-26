package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import kotlin.math.abs

internal object TableauCascadeSupport {
    const val MIN_READ_CONFIDENCE = 0.55f
    const val STRONG_DIRECT_READ_FLOOR = 0.75f
    /** Adjacent glyph confusions need a higher bar to beat geometry. */
    const val ADJACENT_CONFUSION_FLOOR = 0.94f
    /** Color-family flips (Clubs→Diamonds). Allow strong wrong-color reads. */
    const val COLOR_MISMATCH_FLOOR = 0.92f
    private const val WEAK_JACK_FLOOR = 0.65f
    private const val BOTTOM_ANCHOR_FLOOR = 0.80f
    private const val BOTTOM_ONLY_WEAK_FLOOR = 0.80f
    private const val ILLEGAL_BOTTOM_OVERRIDE_FLOOR = 0.90f

    fun geometricCascadeCard(
        bottomCard: Card,
        distanceFromBottom: Int
    ): Card {
        val inferredValue = bottomCard.rank.value + distanceFromBottom
        val inferredRed = if (distanceFromBottom % 2 == 0) {
            bottomCard.suit.isRed
        } else {
            !bottomCard.suit.isRed
        }
        val known = bottomCard.known && inferredValue <= Rank.King.value
        return Card(
            rank = if (known) Rank.fromValue(inferredValue) else Rank.Ace,
            // Clubs/Diamonds placeholders — Spades/Hearts defaults biased
            // Evaluate toward Clubs→Spades once geom overrides became scored.
            suit = if (inferredRed) Suit.Diamonds else Suit.Clubs,
            faceUp = true,
            known = known,
            inferred = true,
            suitAmbiguous = true
        )
    }

    /**
     * Card that should sit immediately under [upper] in a legal tableau run
     * (rank one below, opposite color). Suit is only a color placeholder —
     * use [enrichGeometricFromRejectedRead] when template scores are available.
     */
    fun geometricCardBelow(upper: Card): Card? {
        if (!upper.known || upper.rank.value <= Rank.Ace.value) return null
        return Card(
            rank = Rank.fromValue(upper.rank.value - 1),
            suit = if (upper.suit.isRed) Suit.Clubs else Suit.Diamonds,
            faceUp = true,
            known = true,
            inferred = true,
            suitAmbiguous = true
        )
    }

    /**
     * When the fully-visible bottom card cannot legally stack under the card
     * above it, prefer the geometric card-below unless the bottom read is
     * extremely strong. New golden 20260826_070842: 5♥-4♠ over a real 3♦
     * misread as 8♦ — geometry from 4♠ recovers 3 of red.
     */
    fun repairIllegalBottom(
        cardAbove: Card?,
        bottom: Card,
        bottomConfidence: Float,
        bottomHit: RecognitionHit
    ): Card {
        if (cardAbove == null || !cardAbove.faceUp || !cardAbove.known) return bottom
        if (!bottom.known) return bottom
        if (bottom.canStackOnTableau(cardAbove)) return bottom
        if (bottomConfidence >= ILLEGAL_BOTTOM_OVERRIDE_FLOOR) return bottom
        val expected = geometricCardBelow(cardAbove) ?: return bottom
        return enrichGeometricFromRejectedRead(expected, bottomHit).copy(inferred = false)
    }

    fun isReliableRead(hit: RecognitionHit, card: Card?): Boolean =
        card?.known == true &&
            hit.confidence >= MIN_READ_CONFIDENCE &&
            !hit.isFaceDown &&
            !hit.isEmpty

    /**
     * Prefer geometric rank/color over a direct mid-cascade read when a stronger
     * anchor disagrees. Covers doubly-anchored runs (bottom + leading agree on
     * length) and bottom-only anchors when the leading card is unknown.
     */
    fun prefersGeometricOverDirectRead(
        bottomCard: Card,
        bottomReadConfidence: Float,
        geometric: Card,
        directCard: Card?,
        directConfidence: Float,
        rankCountConsistent: Boolean
    ): Boolean {
        if (directCard == null || !directCard.known) return false
        if (!geometric.known || !bottomCard.known) return false

        val rankMismatch = directCard.rank != geometric.rank
        val colorMismatch = directCard.suit.isRed != geometric.suit.isRed
        if (!rankMismatch && !colorMismatch) return false

        if (rankCountConsistent && directConfidence < STRONG_DIRECT_READ_FLOOR) {
            return true
        }
        // Doubly-anchored adjacent glyph confusions keep scoring above 0.75 while
        // still wrong — raise the floor when both anchors agree.
        if (rankCountConsistent &&
            rankMismatch &&
            isAdjacentConfusionPair(directCard.rank, geometric.rank) &&
            directConfidence < ADJACENT_CONFUSION_FLOOR
        ) {
            return true
        }
        // Same-rank color-family flips only when the run is doubly anchored.
        // The unlocked path (v1.4.52) fixed some Clubs→Diamonds but invented
        // C↔S via placeholders and cost net accuracy on v1.4.54.
        if (rankCountConsistent &&
            !rankMismatch &&
            colorMismatch &&
            directConfidence < COLOR_MISMATCH_FLOOR
        ) {
            return true
        }
        // Color flips on a consistent run (rank may also differ).
        if (rankCountConsistent &&
            colorMismatch &&
            directConfidence < COLOR_MISMATCH_FLOOR
        ) {
            return true
        }
        // suitAmbiguous + wrong rank under a consistent run is almost always a
        // bad mid-strip read (8↔9 / 3→4 cases carried ~).
        if (rankCountConsistent &&
            rankMismatch &&
            directCard.suitAmbiguous
        ) {
            return true
        }

        if (bottomReadConfidence < BOTTOM_ANCHOR_FLOOR) return false

        if (directCard.rank == Rank.Jack &&
            directConfidence < WEAK_JACK_FLOOR &&
            rankMismatch
        ) {
            return true
        }

        if (directCard.rank == Rank.Ace &&
            geometric.rank != Rank.Ace &&
            directConfidence < 0.85f
        ) {
            return true
        }

        // Bottom-only adjacent pairs: only the historically validated set.
        // Two/Three and Six/Seven stay doubly-anchored-only — a misread Ace
        // bottom otherwise invents geometric Two and steals a correct Three
        // (Evaluate Three→Two stayed at 12 after adding 2/3 broadly).
        if (isBottomOnlyAdjacentConfusionPair(directCard.rank, geometric.rank) &&
            directConfidence < ADJACENT_CONFUSION_FLOOR
        ) {
            return true
        }

        if (directConfidence >= BOTTOM_ONLY_WEAK_FLOOR) return false

        if (rankMismatch) {
            val rankDelta = abs(directCard.rank.value - geometric.rank.value)
            if (rankDelta >= 2) return true
            if (colorMismatch) return true
        }

        return false
    }

    private fun isAdjacentConfusionPair(first: Rank, second: Rank): Boolean =
        when (setOf(first, second)) {
            setOf(Rank.Two, Rank.Three),
            setOf(Rank.Three, Rank.Four),
            setOf(Rank.Four, Rank.Five),
            setOf(Rank.Five, Rank.Six),
            setOf(Rank.Six, Rank.Seven),
            setOf(Rank.Eight, Rank.Nine),
            setOf(Rank.Jack, Rank.Queen) -> true
            else -> false
        }

    private fun isBottomOnlyAdjacentConfusionPair(first: Rank, second: Rank): Boolean =
        when (setOf(first, second)) {
            setOf(Rank.Three, Rank.Four),
            setOf(Rank.Four, Rank.Five),
            setOf(Rank.Five, Rank.Six),
            setOf(Rank.Eight, Rank.Nine),
            setOf(Rank.Jack, Rank.Queen) -> true
            else -> false
        }

    /**
     * Geometric fallback only knows red vs black — when a rejected direct read
     * still has strong suit-template scores, keep the geometric rank but adopt
     * the best matching partner suit (e.g. 4♦ not default 4♥).
     */
    fun enrichGeometricFromRejectedRead(
        geometric: Card,
        hit: RecognitionHit
    ): Card {
        if (!geometric.known) return geometric
        val scores = RecognitionTrace.parseSuitScores(hit.trace.suitTemplates)
        if (scores.isEmpty()) return geometric
        val suits = if (geometric.suit.isRed) {
            listOf(Suit.Hearts, Suit.Diamonds)
        } else {
            listOf(Suit.Clubs, Suit.Spades)
        }
        val best = suits.maxByOrNull { scores[it] ?: 0f } ?: return geometric
        val bestScore = scores[best] ?: 0f
        val partner = if (best.isRed) {
            if (best == Suit.Hearts) Suit.Diamonds else Suit.Hearts
        } else {
            if (best == Suit.Clubs) Suit.Spades else Suit.Clubs
        }
        val partnerScore = scores[partner] ?: 0f
        if (bestScore < 0.65f || bestScore - partnerScore < 0.03f) {
            // Color-correct placeholder without Spades/Hearts bias.
            return geometric.copy(
                suit = if (geometric.suit.isRed) Suit.Diamonds else Suit.Clubs,
                suitAmbiguous = true
            )
        }
        return geometric.copy(suit = best, suitAmbiguous = false)
    }

    fun promoteTrustedRun(faceUpRun: List<Card>): List<Card> {
        if (faceUpRun.size <= 1) return faceUpRun
        val bottom = faceUpRun.last()
        if (!bottom.known || bottom.inferred) return faceUpRun

        val promoted = faceUpRun.mapIndexed { index, card ->
            val distanceFromBottom = faceUpRun.lastIndex - index
            val expected = geometricCascadeCard(bottom, distanceFromBottom)
            if (card.rank == expected.rank && card.suit.isRed == expected.suit.isRed) {
                expected.copy(inferred = false)
            } else {
                card
            }
        }
        if (promoted.any { it.inferred }) return faceUpRun
        for (i in 0 until promoted.lastIndex) {
            val upper = promoted[i]
            val lower = promoted[i + 1]
            if (!lower.canStackOnTableau(upper)) return faceUpRun
        }
        return promoted
    }
}
