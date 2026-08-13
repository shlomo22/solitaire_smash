package com.personal.solitaireassistant.pipeline

import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.vision.DetectionResult

object RecognitionBoardFormatter {
    /** Compact overlay lines: foundations, waste, tableau tops. */
    fun overlayLines(detection: DetectionResult): String {
        val state = detection.state
        if (state == null) {
            return "no board\nconf=${"%.2f".format(detection.confidence)}"
        }
        val foundations = state.foundations.joinToString(" ") { pile ->
            pile.lastOrNull()?.let { shortCode(it) } ?: "_"
        }
        val waste = state.wasteTop()?.let { shortCode(it) } ?: "_"
        val tableau = state.tableau.joinToString(" ") { col ->
            col.lastOrNull()?.let { shortCode(it) } ?: "_"
        }
        return "f: $foundations\nw: $waste\nt: $tableau"
    }

    /** Full dump saved with Flag Wrong samples. */
    fun dump(detection: DetectionResult): String {
        val state = detection.state
        return buildString {
            appendLine("confidence=${"%.3f".format(detection.confidence)}")
            if (state == null) {
                appendLine("state=null")
            } else {
                appendLine("foundations:")
                state.foundations.forEachIndexed { i, pile ->
                    appendLine("  F$i: ${pileDetail(pile)}")
                }
                appendLine("waste: ${pileDetail(state.waste)}")
                appendLine("stock: count=${state.stock.size}")
                appendLine("tableau:")
                state.tableau.forEachIndexed { i, col ->
                    appendLine("  T$i: ${pileDetail(col)}")
                }
                appendLine()
                appendLine("overlay:")
                appendLine(overlayLines(detection))
            }
            if (detection.diagnostics.isNotEmpty()) {
                appendLine()
                appendLine("diagnostics:")
                detection.diagnostics.forEach { appendLine("  $it") }
            }
        }.trimEnd()
    }

    fun shortCode(card: Card): String {
        if (!card.faceUp) return "D"
        if (!card.known) return if (card.suit.isRed) "Ur" else "Ub"
        val rank = when (card.rank) {
            Rank.Ace -> "A"
            Rank.Jack -> "J"
            Rank.Queen -> "Q"
            Rank.King -> "K"
            else -> card.rank.value.toString()
        }
        val suit = card.suit.name.first()
        val base = "$rank$suit"
        return if (card.suitAmbiguous) "$base?" else base
    }

    private fun pileDetail(cards: List<Card>): String {
        if (cards.isEmpty()) return "(empty)"
        return cards.joinToString(", ") { card ->
            val short = shortCode(card)
            val id = card.id
            if (short == id || !card.faceUp) short else "$short ($id)"
        }
    }
}
