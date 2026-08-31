package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmashPlayScreenGateTest {
    @Test
    fun lobbyHomeScreenIsNotLivePlay() {
        val bitmap = load("screenshots/lobby_home.jpg")
        val board = BoardLocator().locate(bitmap)
        val signals = SmashPlayScreenGate.analyze(bitmap, board, BoardLocator())
        println("lobby debug=${signals.debug}")

        assertTrue("expected lobby nav detection", signals.lobbyHomeScreen)
        assertFalse("lobby should not show game footer buttons", signals.gameControlFooter)

        bitmap.recycle()
    }

    @Test
    fun goldenGameBoardIsLivePlay() {
        val bitmap = loadGolden("20260819_211539")
        val board = BoardLocator().locate(bitmap)
        val signals = SmashPlayScreenGate.analyze(bitmap, board, BoardLocator())
        println("game debug=${signals.debug}")

        assertFalse("real deal should not look like lobby", signals.lobbyHomeScreen)
        assertTrue("real deal should expose End/Undo/Rules footer", signals.gameControlFooter)

        bitmap.recycle()
    }

    @Test
    fun goldenSamplesLookLikeLivePlayFooter() {
        val ids = listOf(
            "20260819_211539",
            "20260818_080819",
            "20260814_125606",
            "20260822_171728",
            "20260824_080444"
        )
        ids.forEach { id ->
            val bitmap = loadGolden(id)
            val board = BoardLocator().locate(bitmap)
            val signals = SmashPlayScreenGate.analyze(bitmap, board, BoardLocator())
            assertFalse("$id looked like lobby", signals.lobbyHomeScreen)
            assertTrue("$id missing game footer, debug=${signals.debug}", signals.gameControlFooter)
            bitmap.recycle()
        }
    }

    @Test
    fun deviceScreenshotShowsGameFooter() {
        val bitmap = load("screenshots/device_5.png")
        val board = BoardLocator().locate(bitmap)
        val signals = SmashPlayScreenGate.analyze(bitmap, board, BoardLocator())
        println("device5 debug=${signals.debug}")

        assertFalse(signals.lobbyHomeScreen)
        assertTrue(signals.gameControlFooter)

        bitmap.recycle()
    }

    @Test
    fun goldenGameBoardHasHiddenStatusBar() {
        val bitmap = loadGolden("20260819_211539")
        val board = BoardLocator().locate(bitmap)
        val signals = SmashPlayScreenGate.analyze(bitmap, board, BoardLocator())
        println("game debug=${signals.debug}")

        assertFalse("real deal runs immersive - no status bar", signals.statusBarVisible)

        bitmap.recycle()
    }

    @Test
    fun finishGameDialogIsNotLivePlay() {
        // Real move-history capture: an in-game "Finish Game?" confirmation
        // dialog dims the board but leaves the End/Undo/Rules footer visible
        // undimmed, so gameControlFooter alone doesn't catch it - the status
        // bar becoming visible (immersive mode released) is what does.
        val bitmap = load("screenshots/dialog_finish_game.png")
        val board = BoardLocator().locate(bitmap)
        val signals = SmashPlayScreenGate.analyze(bitmap, board, BoardLocator())
        println("dialog debug=${signals.debug}")

        assertTrue("dialog should expose the status bar", signals.statusBarVisible)

        bitmap.recycle()
    }

    @Test
    fun resultsPendingScreenIsNotLivePlay() {
        // Real move-history capture: the post-game tournament results/
        // leaderboard screen, a completely different UI from the board that
        // was still getting misread as tableau content before this fix.
        val bitmap = load("screenshots/results_pending.png")
        val board = BoardLocator().locate(bitmap)
        val signals = SmashPlayScreenGate.analyze(bitmap, board, BoardLocator())
        println("results debug=${signals.debug}")

        assertTrue("results screen should expose the status bar", signals.statusBarVisible)

        bitmap.recycle()
    }

    private fun load(path: String) =
        javaClass.classLoader!!.getResourceAsStream(path).use { stream ->
            assertNotNull("missing $path", stream)
            BitmapFactory.decodeStream(stream)!!
        }

    private fun loadGolden(id: String) =
        javaClass.classLoader!!.getResourceAsStream("golden/$id.png").use { stream ->
            assertNotNull("missing golden/$id.png", stream)
            BitmapFactory.decodeStream(stream)!!
        }
}
