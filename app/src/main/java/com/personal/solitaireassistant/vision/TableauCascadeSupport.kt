package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import kotlin.math.abs

internal object TableauCascadeSupport {
    const val MIN_READ_CONFIDENCE = 0.55f
    const val STRONG_DIRECT_READ_FLOOR = 0.75f
    private const val WEAK_JACK_FLOOR = 0.65f
    private const val BOTTOM_ANCHOR_FLOOR = 0.80f
    private const val BOTTOM_ONLY_WEAK_FLOOR = 0.80f

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
            suit = if (inferredRed) Suit.Hearts else Suit.Spades,
            faceUp = true,
            known = known,
            inferred = true
        )
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

        if (bottomReadConfidence < BOTTOM_ANCHOR_FLOOR) return false

        if (directCard.rank == Rank.Jack &&
            directConfidence < WEAK_JACK_FLOOR &&
            rankMismatch
        ) {
            return true
        }

        if (directCard.rank == Rank.Ace &&
            geometric.rank != Rank.Ace &&
            directConfidence < STRONG_DIRECT_READ_FLOOR
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

    /**
     * When the bottom cascade card is read reliably, the fixed Smash overlap
     * spacing makes rank/color derivation trustworthy — lift inferred so runs
     * like 6S-5H-4S-3D become movable.
     */
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
        if (bestScore < 0.65f || bestScore - partnerScore < 0.03f) return geometric
        return geometric.copy(suit = best)
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
