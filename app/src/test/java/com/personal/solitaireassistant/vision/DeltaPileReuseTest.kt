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
