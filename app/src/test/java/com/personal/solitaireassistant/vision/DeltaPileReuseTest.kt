package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeltaPileReuseTest {
    @Test
    fun wasteOnlyDeltaReusesTableauAndMatchesFullDetect() {
        val bmp = loadGolden("20260814_125606")
        val detector = GameStateDetector(
            ApplicationProvider.getApplicationContext(),
            minConfidence = 0.5f
        )
        val full = detector.detect(bmp)
        assertNotNull(full.state)
        val delta = detector.detect(bmp, changedRegions = setOf("waste"))
        assertNotNull(delta.state)
        val deltaNote = delta.diagnostics.first { it.startsWith("delta:") }
        assertTrue(deltaNote, deltaNote.contains("recompute=waste"))
        assertTrue(deltaNote, deltaNote.contains("reused="))
        assertTrue(deltaNote, deltaNote.contains("t0") && deltaNote.contains("t6"))
        assertTrue(deltaNote, deltaNote.contains("stock"))
        assertEquals(full.state!!.tableau, delta.state!!.tableau)
        assertEquals(full.state!!.waste, delta.state!!.waste)
        assertEquals(full.state!!.foundations, delta.state!!.foundations)
        detector.release()
        bmp.recycle()
    }

    @Test
    fun tableauOnlyDeltaReusesWaste() {
        val bmp = loadGolden("20260814_125606")
        val detector = GameStateDetector(
            ApplicationProvider.getApplicationContext(),
            minConfidence = 0.5f
        )
        val full = detector.detect(bmp)
        val delta = detector.detect(bmp, changedRegions = setOf("t3"))
        val deltaNote = delta.diagnostics.first { it.startsWith("delta:") }
        assertTrue(deltaNote, deltaNote.contains("recompute=t3"))
        assertTrue(deltaNote, delta.diagnostics.any { it.startsWith("waste=reused:") })
        assertEquals(full.state!!.waste, delta.state!!.waste)
        for (col in 0..6) {
            if (col == 3) continue
            assertEquals("col $col", full.state!!.tableau[col], delta.state!!.tableau[col])
        }
        detector.release()
        bmp.recycle()
    }

    @Test
    fun liveColumnRecomputeSkipsMidTemplatesWhenAnchorsAgree() {
        val bmp = loadGolden("20260825_131538")
        val detector = GameStateDetector(
            ApplicationProvider.getApplicationContext(),
            minConfidence = 0.5f
        )
        val full = detector.detect(bmp)
        val live = detector.detect(
            bmp,
            changedRegions = setOf("t0", "t1", "t2", "t3", "t4", "t5", "t6")
        )
        val skips = live.diagnostics.filter { it.contains("liveMidSkip=") }
        assertTrue("expected at least one live-mid skip on 131538, got ${live.diagnostics.filter { it.startsWith("tableau") }}", skips.isNotEmpty())
        assertFalse(
            "Evaluate/full detect must still template-match middles",
            full.diagnostics.any { it.contains("liveMidSkip=") }
        )
        for (col in 0..6) {
            val fullTop = full.state!!.tableau[col].lastOrNull { it.faceUp && it.known }
            val liveTop = live.state!!.tableau[col].lastOrNull { it.faceUp && it.known }
            assertEquals("exposed col $col", fullTop?.id, liveTop?.id)
        }
        detector.release()
        bmp.recycle()
    }

    @Test
    fun leadingAndBottomAnchorPairRunsInParallel() {
        val bmp = loadGolden("20260825_131538")
        val detector = GameStateDetector(
            ApplicationProvider.getApplicationContext(),
            minConfidence = 0.5f
        )
        val first = detector.detect(bmp)
        val second = detector.detect(bmp)
        val pairNotes = first.diagnostics.filter { it.contains("anchorPair=") }
        assertTrue(
            "expected tableauN.anchorPair on 131538, got ${first.diagnostics.filter { it.startsWith("tableau") }}",
            pairNotes.isNotEmpty()
        )
        assertTrue(
            "at least one column should overlap leading+bottom, got $pairNotes",
            pairNotes.any { it.endsWith("=parallel") }
        )
        assertEquals(first.state!!.tableau, second.state!!.tableau)
        detector.release()
        bmp.recycle()
    }

    @Test
    fun nullChangedRegionsIsFullRecompute() {
        val bmp = loadGolden("20260814_125606")
        val detector = GameStateDetector(
            ApplicationProvider.getApplicationContext(),
            minConfidence = 0.5f
        )
        detector.detect(bmp)
        val again = detector.detect(bmp)
        val deltaNote = again.diagnostics.first { it.startsWith("delta:") }
        assertTrue(deltaNote, deltaNote.contains("reused="))
        assertFalse(deltaNote, deltaNote.contains("reused=t0") || deltaNote.endsWith("reused=t0"))
        // reused= with nothing after, or only empty
        val reused = deltaNote.substringAfter("reused=")
        assertEquals("", reused)
        detector.release()
        bmp.recycle()
    }

    private fun loadGolden(id: String): android.graphics.Bitmap {
        val stream = javaClass.classLoader!!.getResourceAsStream("golden/$id.png")
        requireNotNull(stream) { "missing $id.png" }
        return requireNotNull(BitmapFactory.decodeStream(stream))
    }
}
