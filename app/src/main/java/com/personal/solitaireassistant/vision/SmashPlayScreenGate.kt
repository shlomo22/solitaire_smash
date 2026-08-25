package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.personal.solitaireassistant.game.BoardRegion

/**
 * Distinguishes an in-progress Solitaire Smash deal from lobby / shop screens.
 *
 * Live play always shows End / Undo / Rules text buttons along the bottom edge.
 * The home lobby instead shows a light nav bar (Home tab, store icons) and
 * large green tournament PLAY buttons in the mid-screen band.
 */
object SmashPlayScreenGate {
    data class Signals(
        val gameControlFooter: Boolean,
        val lobbyHomeScreen: Boolean,
        val debug: DebugMetrics = DebugMetrics()
    )

    data class DebugMetrics(
        val labeledButtons: Int = 0,
        val footerLuma: Float = 0f,
        val navBarLuma: Float = 0f,
        val navCenterPink: Float = 0f,
        val playButtonRatio: Float = 0f,
        val leftWhite: Float = 0f,
        val centerWhite: Float = 0f,
        val rightWhite: Float = 0f
    )

    private val gameControlFooter = RectF(0f, 0.795f, 1f, 0.965f)
    private val gameControlLeft = RectF(0.04f, 0.805f, 0.30f, 0.955f)
    private val gameControlCenter = RectF(0.36f, 0.805f, 0.64f, 0.955f)
    private val gameControlRight = RectF(0.70f, 0.805f, 0.96f, 0.955f)
    private val lobbyNavBar = RectF(0f, 0.875f, 1f, 1f)
    private val lobbyNavCenter = RectF(0.36f, 0.885f, 0.64f, 1f)
    private val tournamentListBand = RectF(0f, 0.30f, 1f, 0.72f)

    fun analyze(bitmap: Bitmap, board: LocatedBoard, locator: BoardLocator): Signals {
        val footer = SmashColorAnalyzer.analyze(bitmap, locator.map(board, gameControlFooter))
        val left = SmashColorAnalyzer.analyze(bitmap, locator.map(board, gameControlLeft))
        val center = SmashColorAnalyzer.analyze(bitmap, locator.map(board, gameControlCenter))
        val right = SmashColorAnalyzer.analyze(bitmap, locator.map(board, gameControlRight))
        val navBar = SmashColorAnalyzer.analyze(bitmap, locator.map(board, lobbyNavBar))
        val navCenterRegion = locator.map(board, lobbyNavCenter)
        val navCenterPink = analyzeNavCenterPink(bitmap, navCenterRegion)
        val tournamentBand = analyzeTournamentBand(bitmap, locator.map(board, tournamentListBand))

        val labeledButtons = listOf(left, center, right).count { looksLikeGameControlButton(it) }
        val lobbyHomeScreen = looksLikeLobbyNavBar(navBar, navCenterPink, tournamentBand)
        // In-play boards keep a dark End/Undo/Rules strip (~luma 0.33). The home
        // lobby's bottom nav bar is much lighter (~0.58) even when misread cards
        // populate the tableau geometry slots above it.
        val gameControlFooter = footer.avgLuma <= 0.48f &&
            !lobbyHomeScreen &&
            tournamentBand.playButtonRatio < 0.02f

        return Signals(
            gameControlFooter = gameControlFooter,
            lobbyHomeScreen = lobbyHomeScreen,
            debug = DebugMetrics(
                labeledButtons = labeledButtons,
                footerLuma = footer.avgLuma,
                navBarLuma = navBar.avgLuma,
                navCenterPink = navCenterPink,
                playButtonRatio = tournamentBand.playButtonRatio,
                leftWhite = left.whiteRatio,
                centerWhite = center.whiteRatio,
                rightWhite = right.whiteRatio
            )
        )
    }

    private fun looksLikeGameControlButton(stats: SmashColorAnalyzer.RegionStats): Boolean =
        stats.whiteRatio >= 0.025f &&
            stats.avgLuma in 0.18f..0.62f &&
            stats.tealRatio < 0.10f

    private fun looksLikeLobbyNavBar(
        navBar: SmashColorAnalyzer.RegionStats,
        navCenterPink: Float,
        tournamentBand: TournamentBandStats
    ): Boolean {
        val lightNavBar = navBar.avgLuma >= 0.50f
        val pinkHomeTab = navCenterPink >= 0.008f
        val greenPlayList = tournamentBand.playButtonRatio >= 0.012f
        return lightNavBar && (pinkHomeTab || greenPlayList)
    }

    private data class TournamentBandStats(
        val playButtonRatio: Float,
        val pinkAccentRatio: Float
    )

    private fun analyzeTournamentBand(bitmap: Bitmap, region: BoardRegion): TournamentBandStats {
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val step = ((right - left).coerceAtLeast(8) / 20).coerceAtLeast(1)

        var playButtons = 0
        var pink = 0
        var total = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                total++
                if (isLobbyPlayButtonGreen(r, g, b)) playButtons++
                if (isLobbyPinkAccent(r, g, b)) pink++
                x += step
            }
            y += step
        }
        if (total == 0) {
            return TournamentBandStats(0f, 0f)
        }
        val t = total.toFloat()
        return TournamentBandStats(
            playButtonRatio = playButtons / t,
            pinkAccentRatio = pink / t
        )
    }

    private fun analyzeNavCenterPink(bitmap: Bitmap, region: BoardRegion): Float {
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val step = ((right - left).coerceAtLeast(8) / 12).coerceAtLeast(1)

        var pink = 0
        var total = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                total++
                if (isLobbyPinkAccent(r, g, b)) pink++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else pink / total.toFloat()
    }

    fun isLobbyPlayButtonGreen(r: Int, g: Int, b: Int): Boolean =
        g > 165 && r < 110 && b < 110 && g > r + 70 && g > b + 50

    fun isLobbyPinkAccent(r: Int, g: Int, b: Int): Boolean =
        r > 195 && g in 55..145 && b in 95..190 && r > g + 55
}

private fun BoardLocator.map(board: LocatedBoard, rel: RectF): BoardRegion {
    val w = board.bounds.width
    val h = board.bounds.height
    return BoardRegion(
        left = board.bounds.left + rel.left * w,
        top = board.bounds.top + rel.top * h,
        right = board.bounds.left + rel.right * w,
        bottom = board.bounds.top + rel.bottom * h
    )
}
