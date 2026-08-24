package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState

sealed class RecognitionViolation {
    abstract fun summary(): String

    data class DuplicateCard(
        val cardId: String,
        val locations: List<String>
    ) : RecognitionViolation() {
        override fun summary(): String = "duplicate:$cardId"
    }

    data class CascadeBreak(
        val pile: String,
        val lowerIndex: Int,
        val upperIndex: Int,
        val lowerCard: String,
        val upperCard: String
    ) : RecognitionViolation() {
        override fun summary(): String = "cascade:$pile:$lowerIndex-$upperIndex"
    }
}

object BoardRecognitionValidator {
    fun validate(state: GameState): List<RecognitionViolation> =
        findDuplicateCards(state) + findCascadeBreaks(state)

    private fun findDuplicateCards(state: GameState): List<RecognitionViolation> {
        val seen = mutableMapOf<String, MutableList<String>>()
        state.waste.forEachIndexed { index, card ->
            recordKnownCard(card, "waste:$index", seen)
        }
        state.foundations.forEachIndexed { pileIndex, pile ->
            pile.forEachIndexed { index, card ->
                recordKnownCard(card, "foundation:$pileIndex:$index", seen)
            }
        }
        state.tableau.forEachIndexed { pileIndex, column ->
            column.forEachIndexed { index, card ->
                recordKnownCard(card, "tableau:$pileIndex:$index", seen)
            }
        }
        return seen.filter { it.value.size > 1 }.map { (cardId, locations) ->
            RecognitionViolation.DuplicateCard(cardId, locations.toList())
        }
    }

    private fun recordKnownCard(
        card: Card,
        location: String,
        seen: MutableMap<String, MutableList<String>>
    ) {
        if (!card.faceUp || !card.known) return
        seen.getOrPut(card.id) { mutableListOf() }.add(location)
    }

    private fun findCascadeBreaks(state: GameState): List<RecognitionViolation> {
        val violations = mutableListOf<RecognitionViolation>()
        state.tableau.forEachIndexed { pileIndex, column ->
            val faceUpIndices = column.mapIndexedNotNull { index, card ->
                index.takeIf { card.faceUp }
            }
            for (i in 0 until faceUpIndices.lastIndex) {
                val lowerIndex = faceUpIndices[i]
                val upperIndex = faceUpIndices[i + 1]
                val lower = column[lowerIndex]
                val upper = column[upperIndex]
                if (!isValidTableauPair(lower, upper)) {
                    violations += RecognitionViolation.CascadeBreak(
                        pile = "tableau:$pileIndex",
                        lowerIndex = lowerIndex,
                        upperIndex = upperIndex,
                        lowerCard = lower.id,
                        upperCard = upper.id
                    )
                }
            }
        }
        return violations
    }

    private fun isValidTableauPair(lower: Card, upper: Card): Boolean {
        if (!lower.known || !upper.known) return true
        if (!lower.faceUp || !upper.faceUp) return true
        return lower.suit.oppositeColor(upper.suit) && lower.rank.isOneBelow(upper.rank)
    }
}
