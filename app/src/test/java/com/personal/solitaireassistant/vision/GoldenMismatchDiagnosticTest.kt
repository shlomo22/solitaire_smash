package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenMismatchDiagnosticTest {
    @Test
    fun buildMismatchDiagnosticIncludesTraceAndProbe() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val slot = GoldenSlot(
            pile = "waste",
            index = 0,
            bounds = BoardRegion(10f, 10f, 110f, 150f),
            engine = SlotGuess(SlotKind.FaceUp, Rank.Ten, Suit.Hearts),
            truth = SlotGuess(SlotKind.FaceUp, Rank.King, Suit.Hearts)
        )
        val recognized = RecognizedSlot(
            pile = PileRef.Waste,
            index = 0,
            bounds = slot.bounds,
            engine = SlotGuess(SlotKind.FaceUp, Rank.Ten, Suit.Hearts),
            confidence = 0.60f,
            diagnostic = "match-Ten-Hearts@0.60",
            trace = RecognitionTrace(
                rankSource = "rank-glyph",
                rankScore = 0.60f,
                postSteps = listOf("ocr=miss:empty")
            )
        )
        try {
            val line = GoldenTruthEvaluator.buildMismatchDiagnostic(
                sampleId = "20260815_170300",
                truthSlot = slot,
                detected = recognized,
                bitmap = bitmap,
                detector = detector
            )
            assertTrue(line.contains("20260815_170300 waste KH vs 10H"))
            assertTrue(line.contains("rank=rank-glyph@0.60"))
            assertTrue(line.contains("ocr=miss:empty"))
            assertTrue(line.contains("probe="))
        } finally {
            bitmap.recycle()
            detector.release()
        }
    }
}
