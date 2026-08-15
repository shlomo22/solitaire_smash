package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit

enum class SlotKind {
    Empty,
    FaceDown,
    FaceUp,
    Unknown
}

data class SlotGuess(
    val kind: SlotKind,
    val rank: Rank? = null,
    val suit: Suit? = null,
    val suitAmbiguous: Boolean = false
) {
    fun shortLabel(): String = when (kind) {
        SlotKind.Empty -> "EMPTY"
        SlotKind.FaceDown -> "DOWN"
        SlotKind.Unknown -> "?"
        SlotKind.FaceUp -> {
            val rankLabel = when (rank) {
                Rank.Ace -> "A"
                Rank.Jack -> "J"
                Rank.Queen -> "Q"
                Rank.King -> "K"
                Rank.Ten -> "10"
                null -> "?"
                else -> rank.value.toString()
            }
            val suitLabel = when (suit) {
                Suit.Hearts -> "H"
                Suit.Diamonds -> "D"
                Suit.Clubs -> "C"
                Suit.Spades -> "S"
                null -> "?"
            }
            val mark = if (suitAmbiguous) "~" else ""
            "$rankLabel$suitLabel$mark"
        }
    }
}

data class RecognizedSlot(
    val pile: PileRef,
    val index: Int,
    val bounds: BoardRegion,
    val engine: SlotGuess,
    val confidence: Float,
    val diagnostic: String,
    val trace: RecognitionTrace = RecognitionTrace.EMPTY,
    val inferred: Boolean = false
)

fun pileRefKey(pile: PileRef): String = when (pile) {
    PileRef.Stock -> "stock"
    PileRef.Waste -> "waste"
    is PileRef.Foundation -> "foundation:${pile.index}"
    is PileRef.Tableau -> "tableau:${pile.index}"
}

fun parsePileRefKey(key: String): PileRef = when {
    key == "stock" -> PileRef.Stock
    key == "waste" -> PileRef.Waste
    key.startsWith("foundation:") ->
        PileRef.Foundation(key.substringAfter(":").toInt())
    key.startsWith("tableau:") ->
        PileRef.Tableau(key.substringAfter(":").toInt())
    else -> error("Unknown pile key: $key")
}

fun slotGuessFromHit(hit: RecognitionHit, cardOverride: Card? = null): SlotGuess {
    if (hit.isEmpty) return SlotGuess(SlotKind.Empty)
    if (hit.isFaceDown) return SlotGuess(SlotKind.FaceDown)
    val card = cardOverride ?: hit.card
    if (card == null) return SlotGuess(SlotKind.Unknown)
    if (!card.known) {
        return SlotGuess(
            kind = SlotKind.Unknown,
            rank = card.rank,
            suit = card.suit,
            suitAmbiguous = card.suitAmbiguous
        )
    }
    return SlotGuess(
        kind = SlotKind.FaceUp,
        rank = card.rank,
        suit = card.suit,
        suitAmbiguous = card.suitAmbiguous
    )
}

fun slotGuessFromCard(card: Card, faceUp: Boolean = card.faceUp): SlotGuess = when {
    !faceUp -> SlotGuess(SlotKind.FaceDown)
    !card.known -> SlotGuess(
        kind = SlotKind.Unknown,
        rank = card.rank,
        suit = card.suit,
        suitAmbiguous = card.suitAmbiguous
    )
    else -> SlotGuess(
        kind = SlotKind.FaceUp,
        rank = card.rank,
        suit = card.suit,
        suitAmbiguous = card.suitAmbiguous
    )
}
