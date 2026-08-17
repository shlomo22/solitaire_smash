package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import com.personal.solitaireassistant.solver.MoveFingerprint
import com.personal.solitaireassistant.solver.MoveSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Reject212926WasteTest {
    @Test
    fun wasteThreeHeartsNotMisreadAsJack() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val stream = javaClass.classLoader!!.getResourceAsStream(
            "Rejected/reject_20260817_212926.png"
        )
        requireNotNull(stream) { "fixture missing" }
        val bitmap = BitmapFactory.decodeStream(stream)
        requireNotNull(bitmap)

        val detector = GameStateDetector(context, minConfidence = 0.5f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)
        val waste = requireNotNull(state.wasteTop())

        println("waste=$waste")
        println(result.diagnostics.filter { it.contains("waste") }.joinToString(" | "))

        assertEquals(Rank.Three, waste.rank)
        assertEquals(Suit.Hearts, waste.suit)

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertNotEquals(
            "illegal Jack_Hearts->Queen_Spades waste move",
            "Jack_Hearts->Queen_Spades",
            MoveFingerprint.of(state, best.move)
        )
        assertTrue(
            "3H must not move onto QS; got ${best.move.label}",
            best.move !is Move.WasteToTableau || best.move.toColumn != 6
        )

        detector.release()
        bitmap.recycle()
    }
}
