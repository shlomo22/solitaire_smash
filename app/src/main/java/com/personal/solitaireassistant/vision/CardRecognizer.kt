package com.personal.solitaireassistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.IOException
import kotlin.math.max

data class RecognitionHit(
    val card: Card?,
    val confidence: Float,
    val isFaceDown: Boolean,
    val isEmpty: Boolean,
    val diagnostic: String,
    val inferredRed: Boolean? = null
)

/**
 * Solitaire Smash card recognizer.
 * Color heuristics always run; OpenCV template matching is used when native libs load.
 */
class CardRecognizer(
    private val context: Context,
    private val minConfidence: Float = 0.65f
) {
    private val bitmapRankTemplates = mutableMapOf<Rank, MutableList<LongArray>>()
    private val bitmapSuitTemplates = mutableMapOf<Suit, MutableList<LongArray>>()
    private val rankTemplates = mutableMapOf<Rank, Mat>()
    private val suitTemplates = mutableMapOf<Suit, Mat>()
    private var emptyTemplate: Mat? = null
    private var faceDownTemplate: Mat? = null
    private var loaded = false
    private var openCvReady = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        Rank.entries.forEach { rank ->
            val templates = mutableListOf<LongArray>()
            val prefix = "rank_${rank.name.lowercase()}"
            val templateNames = context.assets.list("templates")
                .orEmpty()
                .filter { name ->
                    name == "$prefix.png" ||
                        (name.startsWith("${prefix}_alt") && name.endsWith(".png"))
                }
                .sortedWith(compareBy({ it != "$prefix.png" }, { it }))
                .take(MAX_BITMAP_TEMPLATES_PER_RANK)
            templateNames.forEach { name ->
                loadBitmap("templates/$name")?.let { bitmap ->
                    templates += inkMask(bitmap)
                    bitmap.recycle()
                }
            }
            if (templates.isNotEmpty()) bitmapRankTemplates[rank] = templates
        }
        Suit.entries.forEach { suit ->
            val prefix = "suit_${suit.name.lowercase()}"
            val templates = mutableListOf<LongArray>()
            context.assets.list("templates").orEmpty()
                .filter { name ->
                    name == "$prefix.png" ||
                        (name.startsWith("${prefix}_alt") && name.endsWith(".png"))
                }
                .sortedWith(compareBy({ it != "$prefix.png" }, { it }))
                .take(MAX_BITMAP_TEMPLATES_PER_SUIT)
                .forEach { name ->
                    loadBitmap("templates/$name")?.let { bitmap ->
                        templates += inkMask(bitmap)
                        bitmap.recycle()
                    }
                }
            if (templates.isNotEmpty()) {
                bitmapSuitTemplates[suit] = templates
            }
        }
        openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (_: Throwable) {
            false
        }
        if (!openCvReady) {
            Log.w(TAG, "OpenCV unavailable — using color/glyph heuristics only")
            return
        }
        // Probe that natives actually work (Robolectric often lies via initLocal).
        try {
            Mat(2, 2, org.opencv.core.CvType.CV_8UC1).release()
        } catch (t: Throwable) {
            Log.w(TAG, "OpenCV initLocal succeeded but natives missing", t)
            openCvReady = false
            return
        }
        try {
            Rank.entries.forEach { rank ->
                loadGray("templates/rank_${rank.name.lowercase()}.png")?.let {
                    rankTemplates[rank] = it
                }
            }
            Suit.entries.forEach { suit ->
                loadGray("templates/suit_${suit.name.lowercase()}.png")?.let {
                    suitTemplates[suit] = it
                }
            }
            emptyTemplate = loadGray("templates/empty_slot.png")
            faceDownTemplate = loadGray("templates/face_down.png")
            Log.i(
                TAG,
                "Templates loaded ranks=${rankTemplates.size} suits=${suitTemplates.size}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Template load failed", t)
            openCvReady = false
        }
    }

    fun recognize(bitmap: Bitmap, region: BoardRegion): RecognitionHit {
        ensureLoaded()
        if (region.width < 6f || region.height < 6f) {
            return RecognitionHit(null, 0f, false, true, "invalid-region")
        }

        val stats = SmashColorAnalyzer.analyze(bitmap, region)

        if (SmashColorAnalyzer.looksEmpty(stats)) {
            return RecognitionHit(null, 0.82f, false, true, "empty-color")
        }
        if (SmashColorAnalyzer.looksFaceDown(stats)) {
            return RecognitionHit(null, 0.90f, true, false, "face-down-teal")
        }
        if (!SmashColorAnalyzer.looksFaceUp(stats)) {
            return RecognitionHit(null, 0.4f, false, true, "no-card-face")
        }

        val inkRed: Boolean? = when {
            stats.redInkRatio > stats.blackInkRatio + 0.005f && stats.redInkRatio > 0.012f -> true
            stats.blackInkRatio > stats.redInkRatio + 0.005f && stats.blackInkRatio > 0.012f -> false
            else -> null
        }

        if (openCvReady) {
            val crop = crop(bitmap, region)
            if (crop != null) {
                try {
                    // Never override a clear white face-up with the face-down template —
                    // on-device logs showed foundations matching face-down-template.
                    if (stats.tealRatio > 0.15f && stats.whiteRatio < 0.22f &&
                        templateFaceDown(crop)
                    ) {
                        return RecognitionHit(null, 0.88f, true, false, "face-down-template")
                    }
                    val suit = inferSuit(stats, crop, inkRed)
                    val bitmapRankHit = bestBitmapRank(crop)
                    var rankHit: Pair<Rank, Float>? = null
                    var glyph: RankInkHeuristics.Guess? = null
                    val rank = if (bitmapRankHit != null && bitmapRankHit.second >= 0.68f) {
                        bitmapRankHit
                    } else {
                        rankHit = bestRank(crop)
                        glyph = RankInkHeuristics.guess(crop)
                        pickRank(bitmapRankHit, rankHit, glyph)
                    }
                    if (suit != null && rank != null) {
                        val conf = rank.second.coerceAtMost(1f)
                        return RecognitionHit(
                            card = Card(rank.first, suit, faceUp = true, known = true),
                            confidence = conf,
                            isFaceDown = false,
                            isEmpty = false,
                            diagnostic = "match-${rank.first.name}-${suit.name}@${"%.2f".format(conf)}",
                            inferredRed = suit.isRed
                        )
                    }
                    if (suit != null) {
                        val rankHint = bitmapRankHit?.let {
                            "bitmap-${it.first.name}@${"%.2f".format(it.second)}"
                        } ?: rankHit?.let {
                            "${it.first.name}@${"%.2f".format(it.second)}"
                        } ?: glyph?.let {
                            "glyph-${it.rank.name}@${"%.2f".format(it.confidence)}"
                        } ?: "null"
                        return RecognitionHit(
                            card = null,
                            confidence = bitmapRankHit?.second ?: rankHit?.second ?: glyph?.confidence ?: 0.4f,
                            isFaceDown = false,
                            isEmpty = false,
                            diagnostic = "face-up-color-${if (suit.isRed) "red" else "black"}-rank=$rankHint",
                            inferredRed = suit.isRed
                        )
                    }
                } finally {
                    if (!crop.isRecycled) crop.recycle()
                }
            }
        }

        // Color + glyph path (Robolectric / OpenCV-less, and OpenCV miss fallback).
        val crop = crop(bitmap, region)
        try {
            val bitmapRank = crop?.let { bestBitmapRank(it) }
            val glyph = crop?.let { RankInkHeuristics.guess(it) }
            val bitmapSuit = crop?.let { bestBitmapSuit(it, inkRed) }
                ?.takeIf { it.second >= 0.45f }
                ?.first
            val suit = bitmapSuit ?: when (inkRed) {
                    true -> Suit.Hearts
                    false -> Suit.Spades
                    null -> null
                }
            val rank = pickRank(bitmapRank, null, glyph)
            if (suit != null && rank != null) {
                return RecognitionHit(
                    card = Card(rank.first, suit, faceUp = true, known = true),
                    confidence = rank.second,
                    isFaceDown = false,
                    isEmpty = false,
                    diagnostic = "bitmap-${rank.first.name}-${suit.name}@${"%.2f".format(rank.second)}",
                    inferredRed = suit.isRed
                )
            }
            return RecognitionHit(
                card = null,
                confidence = 0.55f,
                isFaceDown = false,
                isEmpty = false,
                diagnostic = "face-up-color-${inkRed?.let { if (it) "red" else "black" } ?: "unknown"}",
                inferredRed = inkRed
            )
        } finally {
            crop?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    fun release() {
        rankTemplates.values.forEach { it.release() }
        suitTemplates.values.forEach { it.release() }
        emptyTemplate?.release()
        faceDownTemplate?.release()
        bitmapRankTemplates.clear()
        bitmapSuitTemplates.clear()
        rankTemplates.clear()
        suitTemplates.clear()
        emptyTemplate = null
        faceDownTemplate = null
        loaded = false
    }

    private fun inferSuit(
        stats: SmashColorAnalyzer.RegionStats,
        crop: Bitmap,
        inkRed: Boolean?
    ): Suit? {
        val colorSuit = inkRed
        val bitmapSuit = bestBitmapSuit(crop, colorSuit)
        if (bitmapSuit != null && bitmapSuit.second >= 0.45f &&
            (colorSuit == null || bitmapSuit.first.isRed == colorSuit)
        ) {
            return bitmapSuit.first
        }
        val templateSuit = bestSuit(crop)
        if (templateSuit != null && colorSuit != null) {
            val candidates = Suit.entries.filter { it.isRed == colorSuit }
            val filtered = candidates.mapNotNull { suit ->
                suitTemplates[suit]?.let { suit to matchTemplate(crop, it, badgeOnly = true) }
            }.maxByOrNull { it.second }
            if (filtered != null && filtered.second >= 0.45f) return filtered.first
            return candidates.firstOrNull { it == templateSuit.first } ?: candidates.first()
        }
        if (templateSuit != null && templateSuit.second >= 0.5f) return templateSuit.first
        return when (colorSuit) {
            true -> Suit.Hearts
            false -> Suit.Spades
            null -> null
        }
    }

    private fun bestBitmapSuit(crop: Bitmap, isRed: Boolean? = null): Pair<Suit, Float>? {
        if (bitmapSuitTemplates.isEmpty()) return null
        val width = (crop.width * 0.38f).toInt().coerceAtLeast(8)
        val height = (crop.height * 0.31f).toInt().coerceAtLeast(8)
        val sourceMasks = mutableListOf<LongArray>()
        // Keep the rank glyph out of the upper-right suit badge mask.
        for (xFraction in listOf(0.58f, 0.61f, 0.64f)) {
            val x = (crop.width * xFraction).toInt().coerceIn(0, crop.width - 8)
            val actualWidth = width.coerceAtMost(crop.width - x)
            for (yFraction in listOf(0.0f, 0.03f, 0.06f)) {
                val y = (crop.height * yFraction).toInt().coerceIn(0, crop.height - 8)
                val actualHeight = height.coerceAtMost(crop.height - y)
                val roi = Bitmap.createBitmap(crop, x, y, actualWidth, actualHeight)
                sourceMasks += inkMask(roi)
                roi.recycle()
            }
        }
        var best: Pair<Suit, Float>? = null
        var second = 0f
        bitmapSuitTemplates
            .filterKeys { suit -> isRed == null || suit.isRed == isRed }
            .forEach { (suit, templateMasks) ->
            val score = templateMasks.maxOf { templateMask ->
                sourceMasks.maxOf { source -> maskScore(source, templateMask) }
            }
            if (best == null || score > best!!.second) {
                second = best?.second ?: 0f
                best = suit to score
            } else if (score > second) {
                second = score
            }
        }
        val top = best ?: return null
        if (top.second < 0.42f) return null
        if (top.second - second < 0.025f && top.second < 0.68f) return null
        return top
    }

    private fun pickRank(
        bitmapHit: Pair<Rank, Float>?,
        rankHit: Pair<Rank, Float>?,
        glyph: RankInkHeuristics.Guess?
    ): Pair<Rank, Float>? {
        if (bitmapHit != null && bitmapHit.second >= 0.56f) return bitmapHit
        if (bitmapHit != null && rankHit != null && bitmapHit.first == rankHit.first) {
            return bitmapHit.first to max(bitmapHit.second, rankHit.second).coerceAtLeast(0.52f)
        }
        if (bitmapHit != null && glyph != null && bitmapHit.first == glyph.rank) {
            return bitmapHit.first to max(bitmapHit.second, glyph.confidence).coerceAtLeast(0.52f)
        }
        // Prefer agreement; avoid weak unique winners (logs showed everything → Eight).
        if (rankHit != null && glyph != null && rankHit.first == glyph.rank) {
            val conf = max(rankHit.second, glyph.confidence)
            if (conf >= 0.38f) return rankHit.first to conf.coerceAtLeast(0.50f)
        }
        if (rankHit != null && rankHit.second >= 0.48f) return rankHit
        if (glyph != null && glyph.confidence >= 0.50f) return glyph.rank to glyph.confidence
        if (rankHit != null && rankHit.second >= 0.42f) return rankHit
        return null
    }

    /**
     * Translation-tolerant binary-mask matching. Unlike OpenCV, this runs in
     * fixture tests too and uses templates cropped from the exact Smash font.
     */
    private fun bestBitmapRank(crop: Bitmap): Pair<Rank, Float>? {
        if (bitmapRankTemplates.isEmpty()) return null
        val w = (crop.width * 0.70f).toInt().coerceIn(8, crop.width)
        val h = (crop.height * 0.54f).toInt().coerceAtLeast(8)
        val sourceMasks = mutableListOf<LongArray>()
        // A correctly bounded card exposes the small corner rank and the large
        // center glyph together, matching the source templates directly.
        Bitmap.createBitmap(crop, 0, 0, w, h.coerceAtMost(crop.height)).let { roi ->
            sourceMasks += inkMask(roi)
            roi.recycle()
        }
        // Candidate bounds may start on an overlapping header rather than the
        // exact card top. Search a small x/y grid for the large center glyph.
        for (xFraction in listOf(0.08f, 0.14f, 0.20f)) {
            val x = (crop.width * xFraction).toInt().coerceIn(0, crop.width - 8)
            val actualW = w.coerceAtMost(crop.width - x)
            for (yFraction in listOf(0.16f, 0.22f, 0.28f, 0.34f, 0.40f)) {
                val y = (crop.height * yFraction).toInt().coerceIn(0, crop.height - 8)
                val actualH = h.coerceAtMost(crop.height - y)
                val roi = Bitmap.createBitmap(crop, x, y, actualW, actualH)
                sourceMasks += inkMask(roi)
                roi.recycle()
            }
        }
        var best: Pair<Rank, Float>? = null
        var second = 0f
        bitmapRankTemplates.forEach { (rank, templateMasks) ->
            val score = templateMasks.maxOf { templateMask ->
                sourceMasks.maxOf { source -> maskScore(source, templateMask) }
            }
            when {
                best == null || score > best!!.second -> {
                    second = best?.second ?: 0f
                    best = rank to score
                }
                score > second -> second = score
            }
        }
        val top = best ?: return null
        if (top.second < 0.48f) return null
        if (top.second - second < 0.035f && top.second < 0.68f) return null
        return top
    }

    private fun inkMask(bitmap: Bitmap): LongArray {
        val size = 48
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, false)
        return try {
            LongArray(size).also { rows ->
                for (y in 0 until size) {
                    for (x in 0 until size) {
                        val c = scaled.getPixel(x, y)
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val luma = (r * 30 + g * 59 + b * 11) / 100
                        val isInk =
                            SmashColorAnalyzer.isRedInk(r, g, b) ||
                                SmashColorAnalyzer.isBlackInk(r, g, b) ||
                                luma < 135
                        if (isInk) rows[y] = rows[y] or (1L shl x)
                    }
                }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun maskScore(source: LongArray, template: LongArray): Float {
        val size = 48
        val widthMask = (1L shl size) - 1L
        val sourceInk = source.sumOf { java.lang.Long.bitCount(it) }
        var best = 0f
        for (dy in -2..2) {
            for (dx in -2..2) {
                var intersection = 0
                var templateInk = 0
                for (y in 0 until size) {
                    val templateY = y - dy
                    if (templateY !in 0 until size) continue
                    val templateRow = when {
                        dx > 0 -> (template[templateY] shl dx) and widthMask
                        dx < 0 -> template[templateY] ushr -dx
                        else -> template[templateY]
                    }
                    templateInk += java.lang.Long.bitCount(templateRow)
                    intersection += java.lang.Long.bitCount(source[y] and templateRow)
                }
                val denom = sourceInk + templateInk
                if (denom > 0) best = max(best, 2f * intersection / denom)
            }
        }
        return best
    }

    private fun templateFaceDown(crop: Bitmap): Boolean {
        val tmpl = faceDownTemplate ?: return false
        return matchTemplate(crop, tmpl, badgeOnly = false) >= 0.72f
    }

    private fun bestRank(crop: Bitmap): Pair<Rank, Float>? {
        if (rankTemplates.isEmpty()) return null
        var best: Pair<Rank, Float>? = null
        var second = 0f
        for ((rank, template) in rankTemplates) {
            val center = matchTemplate(crop, template, badgeOnly = false)
            val badge = matchTemplate(crop, template, badgeOnly = true)
            val score = max(center, badge)
            when {
                best == null || score > best.second -> {
                    second = best?.second ?: 0f
                    best = rank to score
                }
                score > second -> second = score
            }
        }
        val top = best ?: return null
        // Reject ambiguous / generic matches (Eight was winning everything at ~0.3).
        if (top.second < 0.42f) return null
        if (top.second - second < 0.06f && top.second < 0.55f) return null
        return top
    }

    private fun bestSuit(crop: Bitmap): Pair<Suit, Float>? {
        if (suitTemplates.isEmpty()) return null
        var best: Pair<Suit, Float>? = null
        for ((suit, template) in suitTemplates) {
            val score = matchTemplate(crop, template, badgeOnly = true)
            if (best == null || score > best.second) best = suit to score
        }
        return best
    }

    private fun matchTemplate(crop: Bitmap, template: Mat, badgeOnly: Boolean): Float {
        val src = Mat()
        val gray = Mat()
        val resized = Mat()
        val result = Mat()
        var roi: Mat? = null
        var tmpl: Mat? = null
        return try {
            Utils.bitmapToMat(crop, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            roi = if (badgeOnly) {
                val w = (gray.cols() * 0.50).toInt().coerceAtLeast(8)
                val h = (gray.rows() * 0.42).toInt().coerceAtLeast(8)
                Mat(gray, Rect(0, 0, w.coerceAtMost(gray.cols()), h.coerceAtMost(gray.rows())))
            } else {
                val x = (gray.cols() * 0.08).toInt()
                val y = (gray.rows() * 0.28).toInt()
                val w = (gray.cols() * 0.70).toInt().coerceAtLeast(8)
                val h = (gray.rows() * 0.60).toInt().coerceAtLeast(8)
                Mat(
                    gray,
                    Rect(
                        x.coerceAtMost(gray.cols() - 8),
                        y.coerceAtMost(gray.rows() - 8),
                        w.coerceAtMost(gray.cols() - x),
                        h.coerceAtMost(gray.rows() - y)
                    )
                )
            }
            Imgproc.resize(
                roi,
                resized,
                Size(
                    max(template.cols().toDouble(), 8.0),
                    max(template.rows().toDouble(), 8.0)
                )
            )
            val tw = template.cols().coerceAtMost(resized.cols())
            val th = template.rows().coerceAtMost(resized.rows())
            tmpl = if (tw != template.cols() || th != template.rows()) {
                Mat().also { Imgproc.resize(template, it, Size(tw.toDouble(), th.toDouble())) }
            } else {
                template
            }
            if (resized.cols() < tmpl!!.cols() || resized.rows() < tmpl.rows()) return 0f
            Imgproc.matchTemplate(resized, tmpl, result, Imgproc.TM_CCOEFF_NORMED)
            Core.minMaxLoc(result).maxVal.toFloat().coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "matchTemplate failed", t)
            0f
        } finally {
            src.release()
            gray.release()
            resized.release()
            result.release()
            roi?.release()
            if (tmpl != null && tmpl !== template) tmpl.release()
        }
    }

    private fun crop(bitmap: Bitmap, region: BoardRegion): Bitmap? {
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 8 || h < 8) return null
        return Bitmap.createBitmap(bitmap, left, top, w, h)
    }

    private fun loadGray(assetPath: String): Mat? {
        return try {
            context.assets.open(assetPath).use { input ->
                val bytes = input.readBytes()
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return null
                val rgba = Mat()
                Utils.bitmapToMat(bmp, rgba)
                val gray = Mat()
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                rgba.release()
                bmp.recycle()
                gray
            }
        } catch (_: IOException) {
            null
        } catch (t: Throwable) {
            Log.w(TAG, "Failed loading $assetPath", t)
            null
        }
    }

    private fun loadBitmap(assetPath: String): Bitmap? = try {
        context.assets.open(assetPath).use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
        }
    } catch (_: IOException) {
        null
    }

    companion object {
        private const val TAG = "CardRecognizer"
        private const val MAX_BITMAP_TEMPLATES_PER_RANK = 5
        private const val MAX_BITMAP_TEMPLATES_PER_SUIT = 3
    }
}
