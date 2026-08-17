package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import com.personal.solitaireassistant.solver.MoveFingerprint
import com.personal.solitaireassistant.solver.MoveGenerator
import com.personal.solitaireassistant.solver.MoveSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Reject214113CascadeTest {
    @Test
    fun exposesSixSpadesOntoSevenHeartsAndQueenHeartsOntoKingSpades() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val stream = javaClass.classLoader!!.getResourceAsStream(
            "Rejected/reject_20260817_214113.png"
        )
        requireNotNull(stream) { "fixture missing" }
        val bitmap = BitmapFactory.decodeStream(stream)
        requireNotNull(bitmap)

        val detector = GameStateDetector(context, minConfidence = 0.5f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        val col5 = state.tableau[5]
        val col3 = state.tableau[3]
        println("col6=${col5.joinToString { it.id + if (it.inferred) "*" else "" }}")
        println("col4=${col3.joinToString { it.id + if (it.inferred) "*" else "" }}")
        println(result.diagnostics.filter { it.startsWith("tableau") }.joinToString(" | "))

        assertFalse(
            "6S should be movable in column 6",
            col5.any { it.rank == Rank.Six && it.suit == Suit.Spades && it.inferred }
        )
        assertFalse(
            "QH should be movable in column 2",
            state.tableau[1].any { it.rank == Rank.Queen && it.suit == Suit.Hearts && it.inferred }
        )

        val moves = MoveGenerator.generate(state)
        assertTrue(
            moves.any { MoveFingerprint.of(state, it) == "Six_Spades->Seven_Hearts" }
        )
        assertTrue(
            moves.any { MoveFingerprint.of(state, it) == "Queen_Hearts->King_Spades" }
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertNotEquals(
            "recycle should not beat productive tableau moves",
            "RECYCLE",
            MoveFingerprint.of(state, best.move)
        )
        assertTrue(
            "expected reveal move, got ${best.move.label}",
            best.move is Move.TableauToTableau
        )

        detector.release()
        bitmap.recycle()
    }
}
