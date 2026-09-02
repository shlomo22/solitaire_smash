package com.personal.solitaireassistant.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BoardRegionFingerprintsTest {
    private fun filled(size: Int, value: Int = 1) = IntArray(size) { value }

    private fun cloneWithFlips(src: IntArray, flips: Int): IntArray {
        val out = src.copyOf()
        for (i in 0 until flips) out[i] = out[i] xor 0x111
        return out
    }

    private fun identicalBoard(): Pair<BoardRegionFingerprints.Snapshot, BoardRegionFingerprints.Snapshot> {
        val regions = BoardRegionFingerprints.SPECS.map { filled(it.cols * it.rows) }.toTypedArray()
        return BoardRegionFingerprints.Snapshot(regions) to
            BoardRegionFingerprints.Snapshot(regions.map { it.copyOf() }.toTypedArray())
    }

    @Test
    fun specsCoverWasteStockFoundationsAndTableau() {
        val names = BoardRegionFingerprints.SPECS.map { it.name }
        assertEquals(
            listOf("waste", "stock", "f0", "f1", "f2", "f3", "t0", "t1", "t2", "t3", "t4", "t5", "t6"),
            names
        )
    }

    @Test
    fun identicalSnapshotsSkip() {
        val (a, b) = identicalBoard()
        val cmp = BoardRegionFingerprints.compare(a, b)
        assertTrue(cmp.unchanged)
        assertEquals("skip", cmp.note())
    }

    @Test
    fun wasteToleranceAbsorbsOneFlip() {
        val (prev, _) = identicalBoard()
        val wasteIdx = 0
        val nextSamples = prev.samples.mapIndexed { i, arr ->
            if (i == wasteIdx) cloneWithFlips(arr, BoardRegionFingerprints.WASTE_TOLERANCE) else arr.copyOf()
        }.toTypedArray()
        val cmp = BoardRegionFingerprints.compare(prev, BoardRegionFingerprints.Snapshot(nextSamples))
        assertTrue("one waste flip must stay inside tolerance", cmp.unchanged)
    }

    @Test
    fun wasteSwapOverToleranceForcesDetect() {
        val (prev, _) = identicalBoard()
        val nextSamples = prev.samples.mapIndexed { i, arr ->
            if (i == 0) cloneWithFlips(arr, BoardRegionFingerprints.WASTE_TOLERANCE + 1) else arr.copyOf()
        }.toTypedArray()
        val cmp = BoardRegionFingerprints.compare(prev, BoardRegionFingerprints.Snapshot(nextSamples))
        assertFalse(cmp.unchanged)
        assertEquals(listOf("waste"), cmp.changed)
        assertTrue(cmp.note().startsWith("changed:waste="))
    }

    @Test
    fun tableauToleranceIsIndependentOfWaste() {
        val (prev, _) = identicalBoard()
        val t0 = BoardRegionFingerprints.SPECS.indexOfFirst { it.name == "t0" }
        val nextSamples = prev.samples.mapIndexed { i, arr ->
            if (i == t0) cloneWithFlips(arr, BoardRegionFingerprints.TABLEAU_TOLERANCE + 1) else arr.copyOf()
        }.toTypedArray()
        val cmp = BoardRegionFingerprints.compare(prev, BoardRegionFingerprints.Snapshot(nextSamples))
        assertEquals(listOf("t0"), cmp.changed)
    }

    @Test
    fun firstFrameHasNoPreviousAndDoesNotSkip() {
        val (curr, _) = identicalBoard()
        val cmp = BoardRegionFingerprints.compare(null, curr)
        assertFalse(cmp.unchanged)
    }

    @Test
    fun countDiffsCountsMismatchedEntries() {
        assertEquals(0, BoardRegionFingerprints.countDiffs(intArrayOf(1, 2, 3), intArrayOf(1, 2, 3)))
        assertEquals(2, BoardRegionFingerprints.countDiffs(intArrayOf(1, 9, 3, 8), intArrayOf(1, 2, 3, 4)))
    }
}
