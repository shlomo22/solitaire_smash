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
