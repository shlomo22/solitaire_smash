package com.personal.solitaireassistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import com.personal.solitaireassistant.settings.UserTemplateStore
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
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

data class GlyphCrops(
    val cardCrop: Bitmap,
    val rankCorner: Bitmap,
    val suitBadge: Bitmap,
    val rankGlyph: Bitmap?
)

/**
 * Solitaire Smash card recognizer.
 * Matches isolated rank-corner and suit-badge templates independently.
 */
class CardRecognizer(
    private val context: Context,
    var minConfidence: Float = 0.65f
) {
    private val userTemplateStore = UserTemplateStore(context)
    private val bitmapRankCornerTemplates = mutableMapOf<Rank, MutableList<LongArray>>()
    private val bitmapRankGlyphTemplates = mutableMapOf<Rank, MutableList<LongArray>>()
    private val bitmapSuitBadgeTemplates = mutableMapOf<Suit, MutableList<LongArray>>()
    private val rankCornerOpenCv = mutableMapOf<Rank, MutableList<Mat>>()
    private val suitBadgeOpenCv = mutableMapOf<Suit, MutableList<Mat>>()
    private var emptyTemplate: Mat? = null
    private var faceDownTemplate: Mat? = null
    private var loaded = false
    private var openCvReady = false

    fun reloadTemplates() {
        releaseTemplateData()
        loaded = false
        ensureLoaded()
    }

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        loadBitmapTemplates()
        openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (_: Throwable) {
            false
        }
        if (!openCvReady) {
            Log.w(TAG, "OpenCV unavailable — using ink-mask matching only")
            return
        }
        try {
            Mat(2, 2, org.opencv.core.CvType.CV_8UC1).release()
        } catch (t: Throwable) {
            Log.w(TAG, "OpenCV initLocal succeeded but natives missing", t)
            openCvReady = false
            return
        }
        try {
            loadOpenCvTemplates()
            emptyTemplate = loadGrayAsset("templates/empty_slot.png")
            faceDownTemplate = loadGrayAsset("templates/face_down.png")
            Log.i(
                TAG,
                "Templates loaded rankCorner=${bitmapRankCornerTemplates.size} " +
                    "suitBadge=${bitmapSuitBadgeTemplates.size} " +
                    "rankGlyph=${bitmapRankGlyphTemplates.size}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "OpenCV template load failed", t)
            openCvReady = false
        }
    }

    fun recognize(
        bitmap: Bitmap,
        region: BoardRegion,
        exactCardBounds: Boolean = false
    ): RecognitionHit {
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

        val crop = crop(bitmap, region) ?: return RecognitionHit(
            null, 0.4f, false, true, "crop-failed"
        )
        if (exactCardBounds) {
            neutralizeHintArrowGreen(crop)
        }
        return try {
            if (openCvReady &&
                stats.tealRatio > 0.15f &&
                stats.whiteRatio < 0.22f &&
                templateFaceDown(crop)
            ) {
                return RecognitionHit(null, 0.88f, true, false, "face-down-template")
            }
            val rankHit = bestRankMatch(crop, exactCardBounds)
            val suitHit = bestSuitMatch(crop, inkRed)
            val inkGuess = RankInkHeuristics.guess(crop)
            val rank = resolveRankWithInk(rankHit, inkGuess)
            if (suitHit != null && rank != null && rank.second >= minRankScore()) {
                val conf = minOf(rank.second, suitHit.second).coerceAtMost(1f)
                RecognitionHit(
                    card = Card(rank.first, suitHit.first, faceUp = true, known = true),
                    confidence = conf,
                    isFaceDown = false,
                    isEmpty = false,
                    diagnostic = "match-${rank.first.name}-${suitHit.first.name}@" +
                        "%.2f".format(conf),
                    inferredRed = suitHit.first.isRed
                )
            } else {
                RecognitionHit(
                    card = null,
                    confidence = rank?.second ?: suitHit?.second ?: 0.4f,
                    isFaceDown = false,
                    isEmpty = false,
                    diagnostic = buildString {
                        append("partial-")
                        append(rank?.let { "${it.first.name}@${"%.2f".format(it.second)}" } ?: "rank?")
                        append('-')
                        append(suitHit?.let { "${it.first.name}@${"%.2f".format(it.second)}" } ?: "suit?")
                    },
                    inferredRed = suitHit?.first?.isRed ?: inkRed
                )
            }
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    fun extractGlyphCrops(bitmap: Bitmap, region: BoardRegion): GlyphCrops? {
        val cardCrop = crop(bitmap, region) ?: return null
        val rankCorner = TemplateGlyphRegions.cropRankCorner(cardCrop) ?: run {
            cardCrop.recycle()
            return null
        }
        val suitBadge = TemplateGlyphRegions.cropSuitBadge(cardCrop) ?: run {
            rankCorner.recycle()
            cardCrop.recycle()
            return null
        }
        val rankGlyph = TemplateGlyphRegions.cropRankGlyph(cardCrop)
        return GlyphCrops(cardCrop, rankCorner, suitBadge, rankGlyph)
    }

    fun rankCornerScores(source: Bitmap, ranks: Set<Rank> = Rank.entries.toSet()): Map<Rank, Float> {
        ensureLoaded()
        val masks = cornerSourceMasks(source)
        return scoreRankTemplates(masks, ranks, bitmapRankCornerTemplates, bitmapRankGlyphTemplates)
    }

    fun suitBadgeScores(source: Bitmap, suits: Set<Suit> = Suit.entries.toSet()): Map<Suit, Float> {
        ensureLoaded()
        val masks = badgeSourceMasks(source)
        return scoreSuitTemplates(masks, suits)
    }

    fun exactRankTemplateScores(
        bitmap: Bitmap,
        region: BoardRegion,
        ranks: Set<Rank>
    ): Map<Rank, Float> {
        val cardCrop = crop(bitmap, region) ?: return emptyMap()
        return try {
            val corner = TemplateGlyphRegions.cropRankCorner(cardCrop) ?: return emptyMap()
            rankCornerScores(corner, ranks)
        } finally {
            cardCrop.recycle()
        }
    }

    fun suitTemplateScores(
        bitmap: Bitmap,
        region: BoardRegion,
        suits: Set<Suit>
    ): Map<Suit, Float> {
        val cardCrop = crop(bitmap, region) ?: return emptyMap()
        return try {
            val badge = TemplateGlyphRegions.cropSuitBadge(cardCrop) ?: return emptyMap()
            suitBadgeScores(badge, suits)
        } finally {
            cardCrop.recycle()
        }
    }

    fun rankShapeGuess(bitmap: Bitmap, region: BoardRegion): RankInkHeuristics.Guess? {
        val cardCrop = crop(bitmap, region) ?: return null
        return try {
            RankInkHeuristics.guess(cardCrop)
        } finally {
            cardCrop.recycle()
        }
    }

    fun release() {
        releaseTemplateData()
        loaded = false
        openCvReady = false
    }

    private fun releaseTemplateData() {
        rankCornerOpenCv.values.flatten().forEach { it.release() }
        suitBadgeOpenCv.values.flatten().forEach { it.release() }
        emptyTemplate?.release()
        faceDownTemplate?.release()
        bitmapRankCornerTemplates.clear()
        bitmapRankGlyphTemplates.clear()
        bitmapSuitBadgeTemplates.clear()
        rankCornerOpenCv.clear()
        suitBadgeOpenCv.clear()
        emptyTemplate = null
        faceDownTemplate = null
    }

    private fun loadBitmapTemplates() {
        Rank.entries.forEach { rank ->
            val corner = mutableListOf<LongArray>()
            loadUserBitmaps(userTemplateStore.listRankCornerFiles(rank), corner)
            loadAssetBitmaps(rankCornerAssetNames(rank), corner)
            loadAssetBitmaps(legacyRankAssetNames(rank), corner)
            if (corner.isNotEmpty()) bitmapRankCornerTemplates[rank] = corner

            if (rank in UserTemplateStore.GLYPH_RANKS) {
                val glyph = mutableListOf<LongArray>()
                loadUserBitmaps(userTemplateStore.listRankGlyphFiles(rank), glyph)
                loadAssetBitmaps(rankGlyphAssetNames(rank), glyph)
                if (glyph.isNotEmpty()) bitmapRankGlyphTemplates[rank] = glyph
            }
        }
        Suit.entries.forEach { suit ->
            val badge = mutableListOf<LongArray>()
            loadUserBitmaps(userTemplateStore.listSuitBadgeFiles(suit), badge)
            loadAssetBitmaps(suitBadgeAssetNames(suit), badge)
            loadAssetBitmaps(legacySuitAssetNames(suit), badge)
            if (badge.isNotEmpty()) bitmapSuitBadgeTemplates[suit] = badge
        }
    }

    private fun loadOpenCvTemplates() {
        Rank.entries.forEach { rank ->
            val mats = mutableListOf<Mat>()
            userTemplateStore.listRankCornerFiles(rank).forEach { file ->
                loadGrayFile(file)?.let { mats += it }
            }
            rankCornerAssetNames(rank).forEach { name ->
                loadGrayAsset("templates/$name")?.let { mats += it }
            }
            legacyRankAssetNames(rank).forEach { name ->
                loadGrayAsset("templates/$name")?.let { mats += it }
            }
            if (mats.isNotEmpty()) rankCornerOpenCv[rank] = mats
        }
        Suit.entries.forEach { suit ->
            val mats = mutableListOf<Mat>()
            userTemplateStore.listSuitBadgeFiles(suit).forEach { file ->
                loadGrayFile(file)?.let { mats += it }
            }
            suitBadgeAssetNames(suit).forEach { name ->
                loadGrayAsset("templates/$name")?.let { mats += it }
            }
            legacySuitAssetNames(suit).forEach { name ->
                loadGrayAsset("templates/$name")?.let { mats += it }
            }
            if (mats.isNotEmpty()) suitBadgeOpenCv[suit] = mats
        }
    }

    private fun loadUserBitmaps(files: List<File>, out: MutableList<LongArray>) {
        files.take(MAX_USER_TEMPLATES).forEach { file ->
            BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                out += inkMask(bitmap)
                bitmap.recycle()
            }
        }
    }

    private fun loadAssetBitmaps(names: List<String>, out: MutableList<LongArray>) {
        names.forEach { name ->
            loadBitmapAsset("templates/$name")?.let { bitmap ->
                out += inkMask(bitmap)
                bitmap.recycle()
            }
        }
    }

    private fun rankCornerAssetNames(rank: Rank): List<String> =
        listAssetNames("rank_corner_${rank.name.lowercase()}")

    private fun rankGlyphAssetNames(rank: Rank): List<String> =
        listAssetNames("rank_glyph_${rank.name.lowercase()}")

    private fun legacyRankAssetNames(rank: Rank): List<String> =
        listAssetNames("rank_${rank.name.lowercase()}")

    private fun suitBadgeAssetNames(suit: Suit): List<String> =
        listAssetNames("suit_badge_${suit.name.lowercase()}")

    private fun legacySuitAssetNames(suit: Suit): List<String> =
        listAssetNames("suit_${suit.name.lowercase()}")

    private fun listAssetNames(prefix: String): List<String> =
        context.assets.list("templates")
            .orEmpty()
            .filter { name ->
                name == "$prefix.png" ||
                    (name.startsWith("${prefix}_alt") && name.endsWith(".png"))
            }
            .sortedWith(compareBy({ it != "$prefix.png" }, { it }))
            .take(MAX_ASSET_TEMPLATES)

    private fun bestRankMatch(
        cardCrop: Bitmap,
        exactCardBounds: Boolean
    ): Pair<Rank, Float>? {
        val corner = TemplateGlyphRegions.cropRankCorner(cardCrop) ?: return null
        return try {
            val cornerHit = pickBestRank(
                cornerSourceMasks(corner),
                Rank.entries.toSet(),
                bitmapRankCornerTemplates,
                bitmapRankGlyphTemplates
            )
            val glyphCrop = TemplateGlyphRegions.cropRankGlyph(cardCrop)
            val glyphHit = if (glyphCrop != null) {
                try {
                    val glyphRanks = if (bitmapRankGlyphTemplates.isNotEmpty()) {
                        UserTemplateStore.GLYPH_RANKS
                    } else {
                        Rank.entries.toSet()
                    }
                    pickBestRank(
                        cornerSourceMasks(glyphCrop),
                        glyphRanks,
                        bitmapRankCornerTemplates,
                        bitmapRankGlyphTemplates
                    )
                } finally {
                    glyphCrop.recycle()
                }
            } else {
                null
            }
            mergeRankHits(cornerHit, glyphHit, exactCardBounds)
        } finally {
            corner.recycle()
        }
    }

    private fun mergeRankHits(
        cornerHit: Pair<Rank, Float>?,
        glyphHit: Pair<Rank, Float>?,
        exactCardBounds: Boolean
    ): Pair<Rank, Float>? {
        if (cornerHit == null) return glyphHit
        if (glyphHit == null) return cornerHit
        if (cornerHit.first == glyphHit.first) {
            return cornerHit.first to maxOf(cornerHit.second, glyphHit.second)
        }
        // Center glyph is more reliable for chunky 5–9 Smash ranks when the
        // corner match is soft or the two disagree by only one step.
        val closeNeighbors =
            kotlin.math.abs(cornerHit.first.value - glyphHit.first.value) <= 2
        val cornerSoft = cornerHit.second < 0.72f
        if (closeNeighbors &&
            (glyphHit.second >= cornerHit.second + 0.03f ||
                (cornerSoft && glyphHit.second >= 0.52f) ||
                (exactCardBounds && cornerSoft && glyphHit.second >= cornerHit.second - 0.02f))
        ) {
            return glyphHit
        }
        return cornerHit
    }

    private fun bestSuitMatch(
        cardCrop: Bitmap,
        inkRed: Boolean?
    ): Pair<Suit, Float>? {
        val badge = TemplateGlyphRegions.cropSuitBadge(cardCrop) ?: return null
        return try {
            val masks = badgeSourceMasks(badge)
            pickBestSuit(masks, inkRed)
        } finally {
            badge.recycle()
        }
    }

    private fun cornerSourceMasks(source: Bitmap): List<LongArray> =
        listOf(inkMask(source))

    private fun badgeSourceMasks(source: Bitmap): List<LongArray> {
        val masks = mutableListOf(inkMask(source))
        if (source.width >= 12 && source.height >= 12) {
            for (dx in listOf(-0.04f, 0f, 0.04f)) {
                val shift = (source.width * dx).toInt()
                if (shift == 0) continue
                val left = shift.coerceAtLeast(0)
                val width = (source.width - kotlin.math.abs(shift)).coerceAtLeast(8)
                masks += inkMask(Bitmap.createBitmap(source, left, 0, width, source.height))
            }
        }
        return masks
    }

    private fun scoreRankTemplates(
        sourceMasks: List<LongArray>,
        ranks: Set<Rank>,
        cornerTemplates: Map<Rank, List<LongArray>>,
        glyphTemplates: Map<Rank, List<LongArray>>
    ): Map<Rank, Float> =
        ranks.mapNotNull { rank ->
            val templates = cornerTemplates[rank].orEmpty() + glyphTemplates[rank].orEmpty()
            if (templates.isEmpty()) return@mapNotNull null
            rank to templates.maxOf { template ->
                sourceMasks.maxOf { source -> maskScore(source, template) }
            }
        }.toMap()

    private fun scoreSuitTemplates(
        sourceMasks: List<LongArray>,
        suits: Set<Suit>
    ): Map<Suit, Float> =
        suits.mapNotNull { suit ->
            val templates = bitmapSuitBadgeTemplates[suit] ?: return@mapNotNull null
            suit to templates.maxOf { template ->
                sourceMasks.maxOf { source -> maskScore(source, template) }
            }
        }.toMap()

    private fun pickBestRank(
        sourceMasks: List<LongArray>,
        ranks: Set<Rank>,
        cornerTemplates: Map<Rank, List<LongArray>>,
        glyphTemplates: Map<Rank, List<LongArray>>
    ): Pair<Rank, Float>? {
        val scores = scoreRankTemplates(sourceMasks, ranks, cornerTemplates, glyphTemplates)
        return pickTopRankWithMargin(scores, minRankScore(), rankMargin())
    }

    private fun pickBestSuit(
        sourceMasks: List<LongArray>,
        inkRed: Boolean?
    ): Pair<Suit, Float>? {
        val filtered = bitmapSuitBadgeTemplates.filterKeys { suit ->
            inkRed == null || suit.isRed == inkRed
        }
        val scores = filtered.mapValues { (_, templates) ->
            templates.maxOf { template ->
                sourceMasks.maxOf { source -> maskScore(source, template) }
            }
        }
        return pickTopSuitWithMargin(scores, minSuitScore(), suitMargin())
    }

    private fun pickTopRankWithMargin(
        scores: Map<Rank, Float>,
        minScore: Float,
        minMargin: Float
    ): Pair<Rank, Float>? {
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0f
        if (best.value < minScore) return null
        if (best.value - second < minMargin && best.value < minConfidence) return null
        return best.key to best.value
    }

    /**
     * Re-scores nearby ranks on the exact card crop when glyphs are easily confused.
     * Corrects to the clearly stronger neighbor but keeps the original best guess when
     * the difference is small, so cards still stay recognized and produce hints.
     */
    fun refineAmbiguousRank(bitmap: Bitmap, region: BoardRegion, card: Card): Card {
        if (!card.known || !card.recognized) return card
        val twoEight = disambiguateTwoEight(bitmap, region, card)
        if (twoEight != card) return twoEight
        if (card.rank.value !in Rank.Five.value..Rank.Ten.value) return card
        val candidates = Rank.entries.filter {
            kotlin.math.abs(it.value - card.rank.value) <= 1
        }.toSet()
        val scores = exactRankTemplateScores(bitmap, region, candidates)
        if (scores.isEmpty()) return card
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.first()
        if (best.key == card.rank) return card
        val currentScore = scores[card.rank] ?: 0f
        // Only override the original match when the neighbor is clearly stronger.
        val margin = if (card.rank.value in Rank.Five.value..Rank.Nine.value &&
            best.key.value in Rank.Five.value..Rank.Nine.value
        ) {
            0.035f
        } else {
            0.06f
        }
        return if (best.value - currentScore >= margin) card.copy(rank = best.key) else card
    }

    /**
     * Smash 2 and 8 templates are close; prefer the ink-shape guess when scores
     * are within a small band, otherwise keep the stronger template.
     */
    private fun disambiguateTwoEight(
        bitmap: Bitmap,
        region: BoardRegion,
        card: Card
    ): Card {
        if (card.rank != Rank.Two && card.rank != Rank.Eight) return card
        val scores = exactRankTemplateScores(bitmap, region, setOf(Rank.Two, Rank.Eight))
        val two = scores[Rank.Two] ?: 0f
        val eight = scores[Rank.Eight] ?: 0f
        if (two < 0.45f && eight < 0.45f) return card
        val gap = kotlin.math.abs(two - eight)
        val shape = rankShapeGuess(bitmap, region)
        // Only promote Two→Eight from shape, never demote a true Eight.
        if (card.rank == Rank.Two &&
            gap <= 0.06f &&
            shape?.rank == Rank.Eight &&
            shape.confidence >= 0.45f
        ) {
            return card.copy(rank = Rank.Eight)
        }
        // Narrow Two>Eight ties on red cards: 8 has more closed mid ink.
        if (card.rank == Rank.Two && gap <= 0.045f && card.suit.isRed) {
            val crop = crop(bitmap, region) ?: return card
            return try {
                if (RankInkHeuristics.looksLikeEightMoreThanTwo(crop)) {
                    card.copy(rank = Rank.Eight)
                } else {
                    card
                }
            } finally {
                crop.recycle()
            }
        }
        return card
    }

    private fun resolveRankWithInk(
        rankHit: Pair<Rank, Float>?,
        inkGuess: RankInkHeuristics.Guess?
    ): Pair<Rank, Float>? {
        if (rankHit == null) {
            return inkGuess?.let { it.rank to it.confidence.coerceAtLeast(minRankScore()) }
        }
        if (inkGuess == null || inkGuess.rank == rankHit.first) return rankHit
        if (inkGuess.confidence < 0.45f) return rankHit
        // Prefer under-templated ranks when ink agrees — never demote Eight→Two etc.
        if (rankHit.second < 0.80f && prefersUnderTemplatedInk(rankHit.first, inkGuess.rank)) {
            return inkGuess.rank to maxOf(inkGuess.confidence, minRankScore())
        }
        if (rankHit.second < 0.70f &&
            kotlin.math.abs(rankHit.first.value - inkGuess.rank.value) <= 2 &&
            prefersUnderTemplatedInk(rankHit.first, inkGuess.rank)
        ) {
            return inkGuess.rank to maxOf(inkGuess.confidence, minRankScore())
        }
        return rankHit
    }

    private fun prefersUnderTemplatedInk(template: Rank, ink: Rank): Boolean =
        (ink == Rank.Seven && template == Rank.Nine) ||
            (ink == Rank.Ace && template == Rank.Nine) ||
            (ink == Rank.Eight && template == Rank.Two) ||
            (ink == Rank.Five && template == Rank.Six)

    private fun pickTopSuitWithMargin(
        scores: Map<Suit, Float>,
        minScore: Float,
        minMargin: Float
    ): Pair<Suit, Float>? {
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.value ?: 0f
        if (best.value < minScore) return null
        if (best.value - second < minMargin && best.value < minConfidence) return null
        return best.key to best.value
    }

    private fun minRankScore(): Float = (minConfidence * 0.65f).coerceIn(0.38f, 0.72f)

    private fun minSuitScore(): Float = (minConfidence * 0.58f).coerceIn(0.35f, 0.65f)

    private fun rankMargin(): Float = 0.018f + (1f - minConfidence) * 0.06f

    private fun suitMargin(): Float = 0.015f + (1f - minConfidence) * 0.05f

    /** Replace overlay arrow green with nearby card white so it is not scored as ink. */
    private fun neutralizeHintArrowGreen(crop: Bitmap) {
        val w = crop.width
        val h = crop.height
        val pixels = IntArray(w * h)
        crop.getPixels(pixels, 0, w, 0, 0, w, h)
        var changed = false
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (SmashColorAnalyzer.isHintArrowGreen(r, g, b)) {
                pixels[i] = (0xFF shl 24) or (0xF2 shl 16) or (0xF2 shl 8) or 0xF2
                changed = true
            }
        }
        if (changed) crop.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun inkMask(bitmap: Bitmap): LongArray {
        val size = 48
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return LongArray(size).also { rows ->
            for (y in 0 until size) {
                val sourceY = (((y + 0.5f) * height) / size)
                    .toInt()
                    .coerceIn(0, height - 1)
                val rowOffset = sourceY * width
                for (x in 0 until size) {
                    val sourceX = (((x + 0.5f) * width) / size)
                        .toInt()
                        .coerceIn(0, width - 1)
                    val c = pixels[rowOffset + sourceX]
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
        return matchTemplate(crop, tmpl) >= 0.72f
    }

    private fun matchTemplate(crop: Bitmap, template: Mat): Float {
        val src = Mat()
        val gray = Mat()
        val resized = Mat()
        val result = Mat()
        return try {
            Utils.bitmapToMat(crop, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(
                gray,
                resized,
                Size(
                    max(template.cols().toDouble(), 8.0),
                    max(template.rows().toDouble(), 8.0)
                )
            )
            if (resized.cols() < template.cols() || resized.rows() < template.rows()) return 0f
            Imgproc.matchTemplate(resized, template, result, Imgproc.TM_CCOEFF_NORMED)
            Core.minMaxLoc(result).maxVal.toFloat().coerceIn(0f, 1f)
        } catch (t: Throwable) {
            Log.w(TAG, "matchTemplate failed", t)
            0f
        } finally {
            src.release()
            gray.release()
            resized.release()
            result.release()
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

    private fun loadGrayAsset(assetPath: String): Mat? {
        return try {
            context.assets.open(assetPath).use { input ->
                val bytes = input.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
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

    private fun loadGrayFile(file: File): Mat? {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(bmp, rgba)
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            gray
        } finally {
            bmp.recycle()
        }
    }

    private fun loadBitmapAsset(assetPath: String): Bitmap? = try {
        context.assets.open(assetPath).use { input ->
            BitmapFactory.decodeStream(input)
        }
    } catch (_: IOException) {
        null
    }

    companion object {
        private const val TAG = "CardRecognizer"
        private const val MAX_ASSET_TEMPLATES = 10
        private const val MAX_USER_TEMPLATES = 8
    }
}
