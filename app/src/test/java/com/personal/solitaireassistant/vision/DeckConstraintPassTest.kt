package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeckConstraintPassTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun duplicateWeakRedFiveStaysRedNotClubs() {
        val taken = slot(
            pile = PileRef.Tableau(2),
            index = 0,
            rank = Rank.Five,
            suit = Suit.Clubs,
            confidence = 0.84f,
            diagnostic = "match-Five-Clubs@0.84"
        )
        val weak = slot(
            pile = PileRef.Tableau(5),
            index = 0,
            rank = Rank.Five,
            suit = Suit.Clubs,
            confidence = 0.69f,
            diagnostic = "match-Five-Clubs@0.69",
            suitTemplates = "D:0.63 C:0.60 H:0.55 S:0.52"
        )
        val recognized = mutableListOf(taken.slot, weak.slot)
        val state = GameState(
            tableau = listOf(
                emptyList(),
                emptyList(),
                listOf(taken.card),
                emptyList(),
                emptyList(),
                listOf(weak.card),
                emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        val recognizer = CardRecognizer(context)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val result = DeckConstraintPass.apply(
                bitmap = bitmap,
                recognizer = recognizer,
                state = state,
                recognizedSlots = recognized
            )
            assertEquals(Suit.Clubs, result.tableau[2].last().suit)
            assertEquals(Suit.Diamonds, result.tableau[5].last().suit)
            assertEquals(Suit.Diamonds, recognized[1].engine.suit)
        } finally {
            bitmap.recycle()
            recognizer.release()
        }
    }

    @Test
    fun duplicateWeakBlackFiveStaysBlackNotDiamonds() {
        val taken = slot(
            pile = PileRef.Tableau(2),
            index = 0,
            rank = Rank.Five,
            suit = Suit.Clubs,
            confidence = 0.84f,
            diagnostic = "match-Five-Clubs@0.84"
        )
        val weak = slot(
            pile = PileRef.Tableau(5),
            index = 0,
            rank = Rank.Five,
            suit = Suit.Clubs,
            confidence = 0.69f,
            diagnostic = "match-Five-Clubs@0.69",
            suitTemplates = "C:0.65 S:0.60 D:0.56 H:0.52"
        )
        val recognized = mutableListOf(taken.slot, weak.slot)
        val state = GameState(
            tableau = listOf(
                emptyList(),
                emptyList(),
                listOf(taken.card),
                emptyList(),
                emptyList(),
                listOf(weak.card),
                emptyList()
            ),
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        val recognizer = CardRecognizer(context)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val result = DeckConstraintPass.apply(
                bitmap = bitmap,
                recognizer = recognizer,
                state = state,
                recognizedSlots = recognized
            )
            assertEquals(Suit.Clubs, result.tableau[2].last().suit)
            assertEquals(Suit.Spades, result.tableau[5].last().suit)
            assertEquals(Suit.Spades, recognized[1].engine.suit)
        } finally {
            bitmap.recycle()
            recognizer.release()
        }
    }

    @Test
    fun ambiguousRedSuitFlipsToPartnerWhenCurrentSuitDuplicated() {
        val taken = slot(
            pile = PileRef.Tableau(0),
            index = 0,
            rank = Rank.Five,
            suit = Suit.Hearts,
            confidence = 0.84f,
            diagnostic = "match-Five-Hearts@0.84"
        )
        val ambiguous = slot(
            pile = PileRef.Tableau(1),
            index = 0,
            rank = Rank.Five,
            suit = Suit.Hearts,
            confidence = 0.69f,
            diagnostic = "match-Five-Hearts-ambiguous@0.69",
            suitAmbiguous = true
        )
        val recognized = mutableListOf(taken.slot, ambiguous.slot)
        val state = GameState(
            tableau = listOf(
                listOf(taken.card),
                listOf(ambiguous.card)
            ) + List(5) { emptyList() },
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        val recognizer = CardRecognizer(context)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val result = DeckConstraintPass.apply(
                bitmap = bitmap,
                recognizer = recognizer,
                state = state,
                recognizedSlots = recognized
            )
            assertEquals(Suit.Hearts, result.tableau[0].last().suit)
            assertEquals(Suit.Diamonds, result.tableau[1].last().suit)
            assertEquals(Suit.Diamonds, recognized[1].engine.suit)
        } finally {
            bitmap.recycle()
            recognizer.release()
        }
    }

    @Test
    fun duplicateRedCardFlipsWeakerSlotToPartnerSuit() {
        val high = slot(
            pile = PileRef.Foundation(0),
            index = 0,
            rank = Rank.Three,
            suit = Suit.Hearts,
            confidence = 0.92f
        )
        val low = slot(
            pile = PileRef.Waste,
            index = 0,
            rank = Rank.Three,
            suit = Suit.Hearts,
            confidence = 0.55f
        )
        val recognized = mutableListOf(high.slot, low.slot)
        val state = GameState(
            tableau = List(7) { emptyList() },
            foundations = listOf(listOf(high.card), emptyList(), emptyList(), emptyList()),
            stock = emptyList(),
            waste = listOf(low.card)
        )
        val recognizer = CardRecognizer(context)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val result = DeckConstraintPass.apply(
                bitmap = bitmap,
                recognizer = recognizer,
                state = state,
                recognizedSlots = recognized
            )
            assertEquals(Suit.Hearts, result.foundations[0].last().suit)
            assertEquals(Suit.Diamonds, result.waste.last().suit)
            assertEquals(Suit.Diamonds, recognized[1].engine.suit)
        } finally {
            bitmap.recycle()
            recognizer.release()
        }
    }

    @Test
    fun duplicateBlackCardFlipsWeakerSlotToPartnerSuit() {
        val high = slot(
            pile = PileRef.Tableau(0),
            index = 0,
            rank = Rank.Seven,
            suit = Suit.Spades,
            confidence = 0.88f
        )
        val low = slot(
            pile = PileRef.Tableau(1),
            index = 0,
            rank = Rank.Seven,
            suit = Suit.Spades,
            confidence = 0.50f
        )
        val recognized = mutableListOf(high.slot, low.slot)
        val state = GameState(
            tableau = listOf(
                listOf(high.card),
                listOf(low.card)
            ) + List(5) { emptyList() },
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        val recognizer = CardRecognizer(context)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val result = DeckConstraintPass.apply(
                bitmap = bitmap,
                recognizer = recognizer,
                state = state,
                recognizedSlots = recognized
            )
            assertEquals(Suit.Spades, result.tableau[0].last().suit)
            assertEquals(Suit.Clubs, result.tableau[1].last().suit)
        } finally {
            bitmap.recycle()
            recognizer.release()
        }
    }

    @Test
    fun ambiguousBlackSuitFlipsToPartnerWhenCurrentSuitDuplicated() {
        val taken = slot(
            pile = PileRef.Tableau(0),
            index = 0,
            rank = Rank.King,
            suit = Suit.Spades,
            confidence = 0.90f,
            diagnostic = "match-King-Spades@0.90"
        )
        val ambiguous = slot(
            pile = PileRef.Tableau(2),
            index = 0,
            rank = Rank.King,
            suit = Suit.Spades,
            confidence = 0.87f,
            diagnostic = "match-King-Spades-ambiguous@0.87",
            suitAmbiguous = true
        )
        val recognized = mutableListOf(taken.slot, ambiguous.slot)
        val state = GameState(
            tableau = listOf(
                listOf(taken.card),
                emptyList(),
                listOf(ambiguous.card)
            ) + List(4) { emptyList() },
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = emptyList()
        )
        val recognizer = CardRecognizer(context)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val result = DeckConstraintPass.apply(
                bitmap = bitmap,
                recognizer = recognizer,
                state = state,
                recognizedSlots = recognized
            )
            assertEquals(Suit.Spades, result.tableau[0].last().suit)
            assertEquals(Suit.Clubs, result.tableau[2].last().suit)
            assertEquals(Suit.Clubs, recognized[1].engine.suit)
        } finally {
            bitmap.recycle()
            recognizer.release()
        }
    }

    @Test
    fun wasteBlackSuitFlipsWhenConfidentClubsDuplicateElsewhere() {
        val foundationAce = slot(
            pile = PileRef.Foundation(0),
            index = 0,
            rank = Rank.Ace,
            suit = Suit.Clubs,
            confidence = 0.94f
        )
        val wasteAce = slot(
            pile = PileRef.Waste,
            index = 0,
            rank = Rank.Ace,
            suit = Suit.Clubs,
            confidence = 0.83f,
            diagnostic = "fused-Ace-Clubs"
        )
        val recognized = mutableListOf(foundationAce.slot, wasteAce.slot)
        val state = GameState(
            tableau = List(7) { emptyList() },
            foundations = listOf(listOf(foundationAce.card), emptyList(), emptyList(), emptyList()),
            stock = emptyList(),
            waste = listOf(wasteAce.card)
        )
        val recognizer = CardRecognizer(context)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val result = DeckConstraintPass.apply(
                bitmap = bitmap,
                recognizer = recognizer,
                state = state,
                recognizedSlots = recognized
            )
            assertEquals(Suit.Clubs, result.foundations[0].last().suit)
            assertEquals(Suit.Spades, result.waste.last().suit)
            assertEquals(Suit.Spades, recognized[1].engine.suit)
        } finally {
            bitmap.recycle()
            recognizer.release()
        }
    }

    @Test
    fun wasteBlackSuitKeepsClubsWhenPartnerSpadesAlreadyTaken() {
        val tableauSix = slot(
            pile = PileRef.Tableau(0),
            index = 0,
            rank = Rank.Six,
            suit = Suit.Spades,
            confidence = 0.88f
        )
        val wasteSix = slot(
            pile = PileRef.Waste,
            index = 0,
            rank = Rank.Six,
            suit = Suit.Clubs,
            confidence = 0.83f,
            diagnostic = "fused-Six-Clubs"
        )
        val recognized = mutableListOf(tableauSix.slot, wasteSix.slot)
        val state = GameState(
            tableau = listOf(listOf(tableauSix.card)) + List(6) { emptyList() },
            foundations = List(4) { emptyList() },
            stock = emptyList(),
            waste = listOf(wasteSix.card)
        )
        val recognizer = CardRecognizer(context)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            val result = DeckConstraintPass.apply(
                bitmap = bitmap,
                recognizer = recognizer,
                state = state,
                recognizedSlots = recognized
            )
            assertEquals(Suit.Spades, result.tableau[0].last().suit)
            assertEquals(Suit.Clubs, result.waste.last().suit)
            assertEquals(Suit.Clubs, recognized[1].engine.suit)
        } finally {
            bitmap.recycle()
            recognizer.release()
        }
    }

    private data class SlotFixture(
        val card: Card,
        val slot: RecognizedSlot
    )

    private fun slot(
        pile: PileRef,
        index: Int,
        rank: Rank,
        suit: Suit,
        confidence: Float,
        diagnostic: String = "test",
        suitAmbiguous: Boolean = false,
        suitTemplates: String? = null
    ): SlotFixture {
        val card = Card(rank, suit, faceUp = true, known = true, suitAmbiguous = suitAmbiguous)
        val bounds = BoardRegion(0f, 0f, 40f, 50f)
        return SlotFixture(
            card = card,
            slot = RecognizedSlot(
                pile = pile,
                index = index,
                bounds = bounds,
                engine = SlotGuess(SlotKind.FaceUp, rank, suit),
                confidence = confidence,
                diagnostic = diagnostic,
                trace = RecognitionTrace(suitTemplates = suitTemplates)
            )
        )
    }
}

