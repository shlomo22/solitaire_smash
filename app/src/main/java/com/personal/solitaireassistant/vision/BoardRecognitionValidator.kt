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
                val bottomIndex = faceUpIndices[i]
                val topIndex = faceUpIndices[i + 1]
                val bottom = column[bottomIndex]
                val top = column[topIndex]
                if (!isValidTableauPair(bottom = bottom, top = top)) {
                    violations += RecognitionViolation.CascadeBreak(
                        pile = "tableau:$pileIndex",
                        lowerIndex = bottomIndex,
                        upperIndex = topIndex,
                        lowerCard = bottom.id,
                        upperCard = top.id
                    )
                }
            }
        }
        return violations
    }

    /**
     * Column index increases toward the top of the pile. [top] sits on [bottom],
     * matching [com.personal.solitaireassistant.game.KlondikeRules] run checks.
     */
    private fun isValidTableauPair(bottom: Card, top: Card): Boolean {
        if (!bottom.known || !top.known) return true
        if (!bottom.faceUp || !top.faceUp) return true
        return top.suit.oppositeColor(bottom.suit) && top.rank.isOneBelow(bottom.rank)
    }
}
