package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Reject133147CascadeTest {
    @Test
    fun tableau7HasTwoFaceUpCardsNotThree() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val stream = javaClass.classLoader!!.getResourceAsStream(
            "Rejected/reject_20260817_133147.png"
        )
        requireNotNull(stream) { "fixture missing" }
        val bitmap = BitmapFactory.decodeStream(stream)
        requireNotNull(bitmap)

        val detector = GameStateDetector(context, minConfidence = 0.5f)
        val result = detector.detect(bitmap)
        val col6 = result.state?.tableau?.get(6).orEmpty()
        val faceUp = col6.filter { it.faceUp }
        val inferred = faceUp.count { it.inferred }

        println("tableau6 faceUp=${faceUp.size} inferred=$inferred")
        println(faceUp.joinToString { "${it.rank.name}_${it.suit.name}${if (it.inferred) "*" else ""}" })
        println(result.diagnostics.filter { it.contains("tableau6") }.joinToString(" | "))

        assertEquals("expected 2 face-up cards in column 7", 2, faceUp.size)
        assertEquals("expected 1 inferred card above the read bottom card", 1, inferred)
        detector.release()
        bitmap.recycle()
    }
}
