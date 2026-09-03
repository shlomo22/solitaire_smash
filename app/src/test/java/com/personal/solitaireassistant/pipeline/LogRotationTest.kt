package com.personal.solitaireassistant.pipeline

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRotationTest {
    private val keep = 3

    @Test
    fun leavesTheLogAloneUntilItReachesTheCap() {
        val dir = tempDir()
        val log = File(dir, "analysis.log").apply { writeText("short") }

        LogRotation.rotateIfNeeded(log, maxBytes = 100L, keep = keep)

        assertEquals("short", log.readText())
        assertFalse(File(dir, "analysis.log.1").exists())
    }

    @Test
    fun oldestGenerationIsDroppedAndTheRestShiftDownInOrder() {
        val dir = tempDir()
        val log = File(dir, "analysis.log")
        // Four distinct payloads so a collapsed chain (every generation holding
        // the newest text) is distinguishable from a correct shift.
        File(dir, "analysis.log.1").writeText("gen1")
        File(dir, "analysis.log.2").writeText("gen2")
        File(dir, "analysis.log.3").writeText("gen3")
        log.writeText("current")

        LogRotation.rotateIfNeeded(log, maxBytes = 1L, keep = keep)

        assertFalse("live log should have been renamed away", log.exists())
        assertEquals("current", File(dir, "analysis.log.1").readText())
        assertEquals("gen1", File(dir, "analysis.log.2").readText())
        assertEquals("gen2", File(dir, "analysis.log.3").readText())
        assertFalse(
            "only $keep generations are kept",
            File(dir, "analysis.log.4").exists()
        )
    }

    @Test
    fun repeatedRotationsKeepAContiguousWindowOfTheNewestGenerations() {
        val dir = tempDir()
        val log = File(dir, "analysis.log")
        repeat(6) { round ->
            log.writeText("round$round")
            LogRotation.rotateIfNeeded(log, maxBytes = 1L, keep = keep)
        }

        // Rounds 0-2 have aged out; 3-5 survive, newest in .1.
        assertEquals("round5", File(dir, "analysis.log.1").readText())
        assertEquals("round4", File(dir, "analysis.log.2").readText())
        assertEquals("round3", File(dir, "analysis.log.3").readText())
    }

    @Test
    fun gapsInTheChainDoNotStopTheShift() {
        val dir = tempDir()
        val log = File(dir, "analysis.log")
        // .2 missing - can happen after a partial delete or a cleared log.
        File(dir, "analysis.log.1").writeText("gen1")
        File(dir, "analysis.log.3").writeText("gen3")
        log.writeText("current")

        LogRotation.rotateIfNeeded(log, maxBytes = 1L, keep = keep)

        assertEquals("current", File(dir, "analysis.log.1").readText())
        assertEquals("gen1", File(dir, "analysis.log.2").readText())
        assertFalse(
            "gen3 was the oldest and should have been dropped",
            File(dir, "analysis.log.3").exists()
        )
    }

    @Test
    fun theShippedCapCoversFarMoreThanTheOldTwoMegabyteOne() {
        // The 2MB cap held ~68 seconds of play at the observed ~1.1MB/min.
        assertTrue(
            "cap should be well above the 2MB that lost a game's evidence",
            AnalysisFileLogger.MAX_BYTES >= 8L * 1024L * 1024L
        )
        assertTrue(
            "more than one generation must be kept",
            AnalysisFileLogger.ROTATION_KEEP > 1
        )
    }

    private fun tempDir(): File =
        Files.createTempDirectory("logrotation").toFile().also { it.deleteOnExit() }
}
