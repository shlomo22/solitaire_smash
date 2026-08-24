package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import com.personal.solitaireassistant.game.PileRef

/**
 * Per-slot provenance for rank/suit decisions — written to analysis.log so
 * template gaps can be spotted without adb logcat filtering.
 */
data class RecognitionTrace(
    val rankSource: String? = null,
    val rankScore: Float? = null,
    val rankTemplates: String? = null,
    val suitSource: String? = null,
    val suitScore: Float? = null,
    val suitTemplates: String? = null,
    val postSteps: List<String> = emptyList()
) {
    fun withPost(step: String): RecognitionTrace {
        if (step in postSteps) return this
        return copy(postSteps = postSteps + step)
    }

    fun merge(other: RecognitionTrace): RecognitionTrace = copy(
        rankSource = other.rankSource ?: rankSource,
        rankScore = other.rankScore ?: rankScore,
        rankTemplates = other.rankTemplates ?: rankTemplates,
        suitSource = other.suitSource ?: suitSource,
        suitScore = other.suitScore ?: suitScore,
        suitTemplates = other.suitTemplates ?: suitTemplates,
        postSteps = postSteps + other.postSteps.filter { it !in postSteps }
    )

    fun logLine(pile: PileRef, index: Int, label: String, confidence: Float): String {
        val pileKey = pileRefKey(pile) + if (index > 0) ":$index" else ""
        val rankPart = buildString {
            append("rank=")
            append(rankSource ?: "?")
            rankScore?.let { append("@${"%.2f".format(it)}") }
            rankTemplates?.let { append(" {$it}") }
        }
        val suitPart = buildString {
            append("suit=")
            append(suitSource ?: "?")
            suitScore?.let { append("@${"%.2f".format(it)}") }
            suitTemplates?.let { append(" {$it}") }
        }
        val postPart = if (postSteps.isEmpty()) {
            ""
        } else {
            " post=[${postSteps.joinToString("; ")}]"
        }
        return "  slot $pileKey $label conf=${"%.2f".format(confidence)} $rankPart $suitPart$postPart"
    }

    companion object {
        val EMPTY = RecognitionTrace()

        fun formatRankScores(scores: Map<Rank, Float>, limit: Int = 4): String? {
            if (scores.isEmpty()) return null
            return scores.entries
                .sortedByDescending { it.value }
                .take(limit)
                .joinToString(" ") { (rank, score) ->
                    "${rankShort(rank)}:${"%.2f".format(score)}"
                }
        }

        fun formatSuitScores(scores: Map<Suit, Float>): String? {
            if (scores.isEmpty()) return null
            return scores.entries
                .sortedByDescending { it.value }
                .joinToString(" ") { (suit, score) ->
                    "${suitShort(suit)}:${"%.2f".format(score)}"
                }
        }

        private fun rankShort(rank: Rank): String = when (rank) {
            Rank.Ace -> "A"
            Rank.Jack -> "J"
            Rank.Queen -> "Q"
            Rank.King -> "K"
            Rank.Ten -> "10"
            else -> rank.value.toString()
        }

        private fun suitShort(suit: Suit): String = when (suit) {
            Suit.Hearts -> "H"
            Suit.Diamonds -> "D"
            Suit.Clubs -> "C"
            Suit.Spades -> "S"
        }

        /** Parses [formatSuitScores] output, e.g. `"D:0.86 H:0.76"`. */
        fun parseSuitScores(suitTemplates: String?): Map<Suit, Float> {
            if (suitTemplates.isNullOrBlank()) return emptyMap()
            return suitTemplates.split(" ").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size != 2) return@mapNotNull null
                val suit = when (parts[0]) {
                    "H" -> Suit.Hearts
                    "D" -> Suit.Diamonds
                    "C" -> Suit.Clubs
                    "S" -> Suit.Spades
                    else -> return@mapNotNull null
                }
                val score = parts[1].toFloatOrNull() ?: return@mapNotNull null
                suit to score
            }.toMap()
        }
    }
}

fun recognitionTraceLines(slots: List<RecognizedSlot>): List<String> =
    slots.filter { shouldLogSlotTrace(it) }.map { slot ->
        slot.trace.logLine(
            pile = slot.pile,
            index = slot.index,
            label = slot.engine.shortLabel(),
            confidence = slot.confidence
        )
    }

private fun shouldLogSlotTrace(slot: RecognizedSlot): Boolean =
    !slot.inferred &&
        (slot.engine.kind == SlotKind.FaceUp || slot.engine.kind == SlotKind.Unknown) &&
        (
            slot.trace.rankSource != null ||
                slot.trace.suitSource != null ||
                slot.trace.rankTemplates != null ||
                slot.trace.suitTemplates != null ||
                slot.trace.postSteps.isNotEmpty()
            )
