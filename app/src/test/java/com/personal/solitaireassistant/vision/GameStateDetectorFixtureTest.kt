package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GameStateDetectorFixtureTest {
    @Test
    fun syntheticBoardProducesNonNullDetectionOrEmptyDiagnostic() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val stream = javaClass.classLoader!!.getResourceAsStream("screenshots/synthetic_board.png")
        assertNotNull("synthetic_board.png fixture missing", stream)
        val bytes = stream!!.readBytes()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(bitmap)

        val detector = GameStateDetector(context, minConfidence = 0.5f)
        val result = detector.detect(bitmap!!)
        assertNotNull(result.board)
        assertTrue(result.diagnostics.isNotEmpty())
        // With placeholder templates, state may be partial; ensure pipeline does not crash.
        println(result.diagnostics.joinToString(" | "))
        detector.release()
        bitmap.recycle()
    }
}
