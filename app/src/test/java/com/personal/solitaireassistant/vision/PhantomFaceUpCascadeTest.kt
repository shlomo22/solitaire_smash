package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.PileRef
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A tableau column must not report more face-up cards than it actually shows.
 *
 * The failure this locks out: `faceDownOverlap` (0.23) is ~9% short of the real
 * 48.68px face-down repeat spacing, so while the exposed run started at
 * `columnRegion.top + faceDownCount * downStep` the shortfall accumulated until
 * the computed boundary still sat inside the last teal back, and the column
 * grew a face-up card that does not exist. Every sample below was checked
 * against its own pixels; several had the phantom baked into their golden
 * *truth* as well, which is why the expectation here is the pixel count rather
 * than the labelled one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PhantomFaceUpCascadeTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Sample id, tableau column, face-up cards actually visible in the PNG. */
    private val cases = listOf(
        // teal backs, then 6C and 5D only.
        Triple("20260901_212228", 5, 2),
        // one exposed card, the 2S, sitting straight on the backs.
        Triple("20260814_233209", 6, 1),
        // 8C, 7H, 6S - truth double-labelled the 6S across two slots.
        Triple("20260819_212027", 3, 3),
        // 3D and 2C.
        Triple("20260825_131858", 5, 2),
        // JH, 10S, 9H, 8S.
        Triple("20260825_131858", 6, 4),
        // 3H and 2C, captured four times in a row.
        Triple("20260825_143717", 6, 2),
        Triple("20260825_143855", 6, 2)
    )

    @Test
    fun columnsDoNotReportMoreFaceUpCardsThanArePresent() {
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val failures = mutableListOf<String>()
        try {
            cases.forEach { (id, col, expectedFaceUp) ->
                val bitmap = loadGolden(id) ?: run {
                    failures += "$id missing fixture"
                    return@forEach
                }
                val detection = detector.detect(bitmap)
                val actual = detection.recognizedSlots.count {
                    it.pile == PileRef.Tableau(col) && it.engine.kind == SlotKind.FaceUp
                }
                if (actual != expectedFaceUp) {
                    failures += "$id tableau:$col face-up $actual, expected $expectedFaceUp"
                }
                bitmap.recycle()
            }
        } finally {
            detector.release()
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun hiddenCardCountNeverExceedsWhatKlondikeDeals() {
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val failures = mutableListOf<String>()
        try {
            cases.map { it.first }.distinct().forEach { id ->
                val bitmap = loadGolden(id) ?: return@forEach
                val detection = detector.detect(bitmap)
                (0 until 7).forEach { col ->
                    val hidden = detection.recognizedSlots.count {
                        it.pile == PileRef.Tableau(col) && it.engine.kind == SlotKind.FaceDown
                    }
                    if (hidden > col) {
                        failures += "$id tableau:$col reports $hidden hidden cards (max $col)"
                    }
                }
                bitmap.recycle()
            }
        } finally {
            detector.release()
        }
        assertEquals(emptyList<String>(), failures)
    }

    private fun loadGolden(id: String): android.graphics.Bitmap? {
        val pngStream = javaClass.classLoader!!.getResourceAsStream("golden/$id.png")
            ?: return null
        return pngStream.use { BitmapFactory.decodeStream(it) }
    }
}
