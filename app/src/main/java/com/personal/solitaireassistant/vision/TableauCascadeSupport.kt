package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit

internal object TableauCascadeSupport {
    const val MIN_READ_CONFIDENCE = 0.55f

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
