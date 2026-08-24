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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class BlackSuitTemplateScores(
    val fullClub: Float,
    val fullSpade: Float,
    val topClub: Float,
    val topSpade: Float
) {
    val fullMargin: Float get() = abs(fullClub - fullSpade)
    val topMargin: Float get() = abs(topClub - topSpade)
}

data class RecognitionHit(
    val card: Card?,
    val confidence: Float,
    val isFaceDown: Boolean,
    val isEmpty: Boolean,
    val diagnostic: String,
    val inferredRed: Boolean? = null,
    val trace: RecognitionTrace = RecognitionTrace.EMPTY,
    /** Populated by [recognize] for reuse (e.g. waste fusion) without re-scoring. */
    val rankScores: Map<Rank, Float>? = null,
    val suitScores: Map<Suit, Float>? = null
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
    // Same rank templates, content-cropped before the 48x48 ink-mask resample.
    // Only used for trimmedToVisibleStrip cascade cards - see the comment on
    // tightContentCrop for why the untrimmed templates don't work there.
    private val bitmapRankTemplatesTrimmed = mutableMapOf<Rank, MutableList<LongArray>>()
    private val bitmapSuitTemplates = mutableMapOf<Suit, MutableList<LongArray>>()
    private val rankTemplates = mutableMapOf<Rank, Mat>()
    private val suitTemplates = mutableMapOf<Suit, MutableList<Mat>>()
    private var emptyTemplate: Mat? = null
    private var faceDownTemplate: Mat? = null
    private var loaded = false
    private var openCvReady = false
    private var rankCornerOcr: RankCornerOcr? = null

    /** True when ML Kit text recognizer is initialized for rank OCR tiebreaks. */
    var ocrReady: Boolean = false
        private set

    // Templates are loaded lazily on first use, but recognize() is called from
    // up to 7 tableau columns at once (GameStateDetector.detect's computeColumn).
    // This body sets loaded=true BEFORE populating the template maps, so without
    // mutual exclusion a second thread arriving mid-load would see loaded==true,
    // return early, and match against still-empty maps - every card on that frame
    // silently unrecognizable. It only stays hidden today because the sequential
    // stock/waste reads happen to finish loading before any column starts, which
    // is an accident of ordering rather than a guarantee. @Synchronized makes the
    // early flag set safe: a second caller blocks at the method entry until the
    // first has fully finished loading, then sees loaded==true and returns.
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        Rank.entries.forEach { rank ->
            val templates = mutableListOf<LongArray>()
            val trimmedTemplates = mutableListOf<LongArray>()
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
                    // Cap to the top 75% of the template's own height before
                    // tight-cropping, for the trimmed variant only. Queen's
                    // template is a real outlier: its tail (Q vs O) makes it
                    // ~13% taller than the other rank templates, but a
                    // trimmed cascade card's source crop is hard-capped at
                    // faceUpStep*0.9 in GameStateDetector and never has room
                    // to show that tail at all - the real on-screen glyph
                    // there is a plain oval with no visible tail. Comparing
                    // that oval against a template that includes the tail
                    // scored Queen behind Ace on a real golden crop; capping
                    // the template to the achievable oval-only shape fixed
                    // it (swept 1.0 down to 0.55: everything from 0.65-0.85
                    // works, 0.55 breaks a different rank, so 0.75 sits with
                    // margin on both sides).
                    val cappedHeight = (bitmap.height * 0.75f).toInt().coerceAtLeast(8)
                    val capped = if (cappedHeight < bitmap.height) {
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cappedHeight)
                    } else {
                        bitmap
                    }
                    val tight = tightContentCrop(capped)
                    trimmedTemplates += inkMask(tight)
                    if (tight !== capped) tight.recycle()
                    if (capped !== bitmap) capped.recycle()
                    bitmap.recycle()
                }
            }
            if (templates.isNotEmpty()) bitmapRankTemplates[rank] = templates
            if (trimmedTemplates.isNotEmpty()) bitmapRankTemplatesTrimmed[rank] = trimmedTemplates
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
        if (rankCornerOcr == null) {
            try {
                rankCornerOcr = RankCornerOcr()
                ocrReady = true
            } catch (t: Throwable) {
                Log.w(TAG, "OCR unavailable — rank tiebreak disabled", t)
                ocrReady = false
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
                val prefix = "suit_${suit.name.lowercase()}"
                val mats = mutableListOf<Mat>()
                context.assets.list("templates").orEmpty()
                    .filter { name ->
                        name == "$prefix.png" ||
                            (name.startsWith("${prefix}_alt") && name.endsWith(".png"))
                    }
                    .sortedWith(compareBy({ it != "$prefix.png" }, { it }))
                    .take(MAX_BITMAP_TEMPLATES_PER_SUIT)
                    .forEach { name ->
                        loadGray("templates/$name")?.let { mats += it }
                    }
                if (mats.isNotEmpty()) suitTemplates[suit] = mats
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

    fun attemptCornerRankOcr(
        bitmap: Bitmap,
        region: BoardRegion,
        profile: RankCornerOcr.CornerRoiProfile = RankCornerOcr.CornerRoiProfile.DEFAULT
    ): RankCornerOcr.AttemptResult {
        ensureLoaded()
        val cardCrop = crop(bitmap, region)
            ?: return RankCornerOcr.AttemptResult(null, "ocr=miss:no-crop")
        return try {
            rankCornerOcr?.attempt(cardCrop, profile)
                ?: RankCornerOcr.AttemptResult(null, "ocr=miss:unavailable")
        } finally {
            cardCrop.recycle()
        }
    }

    fun attemptCornerRankOcrBest(
        bitmap: Bitmap,
        regions: List<BoardRegion>,
        profile: RankCornerOcr.CornerRoiProfile = RankCornerOcr.CornerRoiProfile.DEFAULT
    ): RankCornerOcr.AttemptResult {
        if (regions.isEmpty()) {
            return RankCornerOcr.AttemptResult(null, "ocr=miss:no-regions")
        }
        var bestHit: RankCornerOcr.AttemptResult? = null
        val traces = mutableListOf<String>()
        for (region in regions) {
            val attempt = attemptCornerRankOcr(bitmap, region, profile)
            traces += attempt.trace
            val guess = attempt.guess ?: continue
            val bestGuess = bestHit?.guess
            if (bestGuess == null || guess.confidence > bestGuess.confidence) {
                bestHit = attempt
            }
        }
        val trace = traces.joinToString(";")
        return bestHit?.copy(trace = trace)
            ?: RankCornerOcr.AttemptResult(null, trace)
    }

    fun attemptWasteRankOcr(
        bitmap: Bitmap,
        cardRegions: List<BoardRegion>
    ): RankCornerOcr.AttemptResult {
        if (cardRegions.isEmpty()) {
            return RankCornerOcr.AttemptResult(null, "ocr=miss:no-regions")
        }
        var bestHit: RankCornerOcr.AttemptResult? = null
        val traces = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        // Each OCR attempt is a blocking ML Kit call; trying every region x
        // whole/corner combination unconditionally (up to ~10 sequential
        // calls) is what made waste recognition the dominant per-frame cost
        // once a rank needing OCR showed up. Stop as soon as a clean, short
        // parse comes back — that's the common case (a real rank corner:
        // "K", "10", "A", a single digit) — and only keep searching the
        // remaining regions when what's found so far is still weak/ambiguous.
        run regions@{
            for (region in cardRegions) {
                val key = ocrRegionKey(region)
                if (!seen.add(key)) continue
                considerWasteOcrAttempt(
                    attempt = attemptCornerRankOcr(
                        bitmap,
                        region,
                        RankCornerOcr.CornerRoiProfile.WASTE
                    ),
                    regionTag = "whole@$key",
                    traces = traces,
                    bestHit = { bestHit },
                    updateBest = { bestHit = it }
                )
                if ((bestHit?.guess?.confidence ?: 0f) >= WASTE_OCR_EARLY_EXIT_CONFIDENCE) {
                    return@regions
                }
                val corner = BoardLocator.wasteRankCornerRegion(region)
                if (corner.width >= 8f && corner.height >= 8f) {
                    considerWasteOcrAttempt(
                        attempt = attemptCornerRankOcr(
                            bitmap,
                            corner,
                            RankCornerOcr.CornerRoiProfile.DIRECT
                        ),
                        regionTag = "corner@${ocrRegionKey(corner)}",
                        traces = traces,
                        bestHit = { bestHit },
                        updateBest = { bestHit = it }
                    )
                    if ((bestHit?.guess?.confidence ?: 0f) >= WASTE_OCR_EARLY_EXIT_CONFIDENCE) {
                        return@regions
                    }
                }
            }
        }
        val trace = traces.joinToString(";")
        return bestHit?.copy(trace = trace)
            ?: RankCornerOcr.AttemptResult(null, trace)
    }

    /**
     * Diagnostic-only: tags each waste OCR attempt with the exact pixel bounds
     * of the region it probed, so a mixed-read trace (e.g. K,K,9,9,K) can be
     * traced back to which physical crop produced which answer, rather than
     * looking like a single noisy repeated read on one card.
     */
    private fun considerWasteOcrAttempt(
        attempt: RankCornerOcr.AttemptResult,
        regionTag: String,
        traces: MutableList<String>,
        bestHit: () -> RankCornerOcr.AttemptResult?,
        updateBest: (RankCornerOcr.AttemptResult) -> Unit
    ) {
        traces += "$regionTag:${attempt.trace}"
        val guess = attempt.guess ?: return
        val bestGuess = bestHit()?.guess
        if (bestGuess == null || guess.confidence > bestGuess.confidence) {
            updateBest(attempt)
        }
    }

    private fun ocrRegionKey(region: BoardRegion): String =
        "${region.left.toInt()},${region.top.toInt()}," +
            "${region.right.toInt()},${region.bottom.toInt()}"

    fun recognize(
        bitmap: Bitmap,
        region: BoardRegion,
        exactCardBounds: Boolean = false,
        inkRegion: BoardRegion? = null,
        // True when the caller already trimmed `region` down to just this
        // card's own visible strip (a tableau cascade card partially covered
        // by the next one) rather than a full card-height crop. Distinct
        // from exactCardBounds, which the waste "tight region" also sets but
        // whose crop is genuinely full-card-height (waste has nothing
        // stacked on top of it) and needs rankSourceMasks' original ROI.
        trimmedToVisibleStrip: Boolean = false
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

        // `region` spans the full card height, but in a tableau cascade only a
        // thin header strip at the top actually belongs to this card — the rest
        // of the box is physically covered by the next card(s) stacked on top.
        // Basing the red/black ink split on the full box lets the covering
        // card's color leak into inkRed. Callers that know the true visible
        // strip pass it as inkRegion so the color read comes from pixels that
        // are actually this card.
        val inkStats = inkRegion?.let { SmashColorAnalyzer.analyze(bitmap, it) } ?: stats

        val inkRed: Boolean? = when {
            inkStats.redInkRatio > inkStats.blackInkRatio + 0.005f && inkStats.redInkRatio > 0.012f -> true
            inkStats.blackInkRatio > inkStats.redInkRatio + 0.005f && inkStats.blackInkRatio > 0.012f -> {
                // Cascade header strips on red cards often show more black ink
                // from the rank glyph outline than red pip ink - stay ambiguous
                // so all four suits score when red is present but not dominant.
                if (trimmedToVisibleStrip &&
                    inkStats.redInkRatio > 0.02f &&
                    inkStats.blackInkRatio - inkStats.redInkRatio < 0.20f
                ) {
                    null
                } else {
                    false
                }
            }
            else -> null
        }

        if (openCvReady) {
            val crop = crop(bitmap, region)
            if (crop != null) {
                // region/crop stay full cardHeight so the coarse color gates
                // above (looksFaceDown/looksEmpty/looksFaceUp) keep seeing a
                // full card's worth of pixels - trimming region itself made
                // those gates misfire on genuinely face-up cascade cards
                // (boundary bleed from the covering card is a much bigger
                // fraction of a ~44px strip than of a ~193px card). Rank
                // matching still needs the covering card's content kept out,
                // so derive a separate, shorter bitmap here from inkRegion's
                // already-correct boundaries (expressed as a fraction of
                // crop's own height) and use it only for rank scoring, not
                // suit or the color gates.
                var rankCrop: Bitmap? = null
                val effectiveRankCrop = if (trimmedToVisibleStrip && inkRegion != null && region.height > 0f) {
                    val heightFraction =
                        ((inkRegion.bottom - inkRegion.top) / region.height).coerceIn(0.05f, 1f)
                    val h = (crop.height * heightFraction).toInt().coerceIn(8, crop.height)
                    if (h < crop.height) {
                        Bitmap.createBitmap(crop, 0, 0, crop.width, h).also { rankCrop = it }
                    } else {
                        crop
                    }
                } else {
                    crop
                }
                try {
                    // Never override a clear white face-up with the face-down template —
                    // on-device logs showed foundations matching face-down-template.
                    if (stats.tealRatio > 0.15f && stats.whiteRatio < 0.22f &&
                        templateFaceDown(crop)
                    ) {
                        return RecognitionHit(null, 0.88f, true, false, "face-down-template")
                    }
                    // Cascade cards (exactCardBounds) used to always score all
                    // 4 suits, on the assumption their small header-strip crop
                    // couldn't be trusted for a red/black ink read. The
                    // inkRegion fix now feeds this from the card's own visible
                    // strip specifically to avoid contamination from the card
                    // stacked on top of it, and is decisive (non-null) as
                    // reliably as any other pile's ink read. Gate on it here
                    // too: suitCandidatesForInk already falls back to all 4
                    // suits when inkRed is null, so this only cuts scoring
                    // when the ink read is confident, never removes the hedge
                    // for the ambiguous case.
                    val suitCandidates = suitCandidatesForInk(inkRed)
                    val suitSourceMasks = suitBadgeMasks(crop, preferLocatedBadge = true)
                    val suitScoreMap = suitScoresFromMasks(suitSourceMasks, suitCandidates)
                    val rankScoreMap =
                        rankTemplateScoreMap(effectiveRankCrop, exactCardBounds, trimmedToVisibleStrip)
                    val (suit, suitTraceRaw) =
                        inferSuitWithTrace(crop, inkRed, suitScoreMap, suitSourceMasks)
                    // Diagnostic-only: expose the raw whole-card ink ratio behind
                    // inkRed, since every downstream suit-source label only ever
                    // shows the derived boolean, not the numbers that decided it.
                    val suitTrace = suitTraceRaw.withPost(
                        "ink-ratio:red=${"%.4f".format(inkStats.redInkRatio)}," +
                            "black=${"%.4f".format(inkStats.blackInkRatio)},inkRed=$inkRed" +
                            (if (inkRegion != null) ",inkRegion=header" else "")
                    )
                    val rankTemplates = RecognitionTrace.formatRankScores(rankScoreMap)
                    val rankResolution =
                        resolveRankFromCrop(effectiveRankCrop, rankScoreMap, trimmedToVisibleStrip)
                    val rank = rankResolution.rank
                    val rankTrace = buildRankTrace(
                        bitmapRankHit = rankResolution.bitmapRankHit,
                        rankHit = rankResolution.rankHit,
                        glyph = rankResolution.glyph,
                        ocrGuess = rankResolution.ocrGuess,
                        ocrTrace = rankResolution.ocrTrace,
                        picked = rank,
                        rankTemplates = rankTemplates,
                        strongBitmap = rankResolution.strongBitmap
                    )
                    val trace = rankTrace.merge(suitTrace)
                    if (suit != null && rank != null) {
                        val conf = rank.second.coerceAtMost(1f)
                        return RecognitionHit(
                            card = Card(rank.first, suit, faceUp = true, known = true),
                            confidence = conf,
                            isFaceDown = false,
                            isEmpty = false,
                            diagnostic = "match-${rank.first.name}-${suit.name}@${"%.2f".format(conf)}",
                            inferredRed = suit.isRed,
                            trace = trace,
                            rankScores = rankScoreMap,
                            suitScores = suitScoreMap
                        )
                    }
                    if (suit == null && rank != null && inkRed == false) {
                        val guess = ambiguousBlackSuit(crop)
                        val conf = rank.second.coerceAtMost(1f)
                        return RecognitionHit(
                            card = Card(
                                rank.first,
                                guess.first,
                                faceUp = true,
                                known = true,
                                suitAmbiguous = guess.second
                            ),
                            confidence = conf,
                            isFaceDown = false,
                            isEmpty = false,
                            diagnostic = "match-${rank.first.name}-${guess.first.name}-ambiguous@$conf",
                            inferredRed = false,
                            trace = trace.merge(
                                RecognitionTrace(suitSource = "suit-ambiguous-black")
                            ).let { t ->
                                guess.third?.let { t.withPost("black-tiebreak:$it") } ?: t
                            },
                            rankScores = rankScoreMap,
                            suitScores = suitScoreMap
                        )
                    }
                    if (suit == null && rank != null && inkRed == true) {
                        val guess = ambiguousRedSuit(crop)
                        val conf = rank.second.coerceAtMost(1f)
                        return RecognitionHit(
                            card = Card(
                                rank.first,
                                guess.first,
                                faceUp = true,
                                known = true,
                                suitAmbiguous = guess.second
                            ),
                            confidence = conf,
                            isFaceDown = false,
                            isEmpty = false,
                            diagnostic = "match-${rank.first.name}-${guess.first.name}-ambiguous@$conf",
                            inferredRed = true,
                            trace = trace.merge(
                                RecognitionTrace(
                                    suitSource = "suit-ambiguous-red",
                                    suitTemplates = suitTrace.suitTemplates
                                )
                            ),
                            rankScores = rankScoreMap,
                            suitScores = suitScoreMap
                        )
                    }
                    if (suit != null) {
                        val rankHint = rankResolution.bitmapRankHit?.let {
                            "bitmap-${it.first.name}@${"%.2f".format(it.second)}"
                        } ?: rankResolution.rankHit?.let {
                            "${it.first.name}@${"%.2f".format(it.second)}"
                        } ?: rankResolution.glyph?.let {
                            "glyph-${it.rank.name}@${"%.2f".format(it.confidence)}"
                        } ?: rankResolution.ocrGuess?.let {
                            "ocr-${it.rank.name}@${"%.2f".format(it.confidence)}"
                        } ?: "null"
                        return RecognitionHit(
                            card = null,
                            confidence = rankResolution.bitmapRankHit?.second
                                ?: rankResolution.rankHit?.second
                                ?: rankResolution.glyph?.confidence
                                ?: rankResolution.ocrGuess?.confidence
                                ?: 0.4f,
                            isFaceDown = false,
                            isEmpty = false,
                            diagnostic = "face-up-color-${if (suit.isRed) "red" else "black"}-rank=$rankHint",
                            inferredRed = suit.isRed,
                            trace = trace
                        )
                    }
                } finally {
                    rankCrop?.takeUnless { it.isRecycled }?.recycle()
                    if (!crop.isRecycled) crop.recycle()
                }
            }
        }

        // Color + glyph path (Robolectric / OpenCV-less, and OpenCV miss fallback).
        val crop = crop(bitmap, region)
        var rankCropFallback: Bitmap? = null
        try {
            // Same reasoning as the OpenCV path above: trust the ink-region
            // gate here too instead of always scoring all 4 suits.
            val suitCandidates = suitCandidatesForInk(inkRed)
            val suitSourceMasks = crop?.let { suitBadgeMasks(it, preferLocatedBadge = true) }
            // Same region/crop-stays-full-height reasoning as the OpenCV path
            // above - derive a separate shorter bitmap for rank matching only.
            val effectiveRankCrop = if (crop != null &&
                trimmedToVisibleStrip && inkRegion != null && region.height > 0f
            ) {
                val heightFraction =
                    ((inkRegion.bottom - inkRegion.top) / region.height).coerceIn(0.05f, 1f)
                val h = (crop.height * heightFraction).toInt().coerceIn(8, crop.height)
                if (h < crop.height) {
                    Bitmap.createBitmap(crop, 0, 0, crop.width, h).also { rankCropFallback = it }
                } else {
                    crop
                }
            } else {
                crop
            }
            val rankScoreMap =
                effectiveRankCrop?.let { rankTemplateScoreMap(it, exactCardBounds, trimmedToVisibleStrip) }
                    .orEmpty()
            val suitScoreMap = suitSourceMasks?.let { suitScoresFromMasks(it, suitCandidates) }.orEmpty()
            val rankTemplates = RecognitionTrace.formatRankScores(rankScoreMap)
            val rankResolution = effectiveRankCrop?.let {
                resolveRankFromCrop(it, rankScoreMap, trimmedToVisibleStrip)
            }
            val rank = rankResolution?.rank
            val bitmapSuit = if (crop != null && suitSourceMasks != null) {
                bestBitmapSuit(crop, inkRed, suitSourceMasks)
            } else {
                null
            }?.takeIf { it.second >= 0.45f }
            val shapeBlack = if (inkRed == false && crop != null) {
                SuitBadgeHeuristics.guessBlackSuit(crop)
                    ?.takeIf {
                        it.margin >= BLACK_SHAPE_MIN_MARGIN && it.confidence >= 0.52f
                    }
            } else {
                null
            }
            val shapeRed = if (inkRed == true && crop != null) {
                SuitBadgeHeuristics.guessRedSuit(crop)
                    ?.takeIf { it.margin >= 0.12f && it.confidence >= 0.52f }
            } else {
                null
            }
            val suit = bitmapSuit?.first ?: shapeBlack?.suit ?: shapeRed?.suit
            val suitTrace = when {
                bitmapSuit != null -> RecognitionTrace(
                    suitSource = "suit-png",
                    suitScore = bitmapSuit.second,
                    suitTemplates = RecognitionTrace.formatSuitScores(suitScoreMap)
                )
                shapeBlack != null -> RecognitionTrace(
                    suitSource = "suit-shape-black",
                    suitScore = shapeBlack.confidence,
                    suitTemplates = RecognitionTrace.formatSuitScores(suitScoreMap)
                )
                shapeRed != null -> RecognitionTrace(
                    suitSource = "suit-shape-red",
                    suitScore = shapeRed.confidence,
                    suitTemplates = RecognitionTrace.formatSuitScores(suitScoreMap)
                )
                else -> RecognitionTrace(suitTemplates = RecognitionTrace.formatSuitScores(suitScoreMap))
            }
            val rankTrace = buildRankTrace(
                bitmapRankHit = rankResolution?.bitmapRankHit,
                rankHit = rankResolution?.rankHit,
                glyph = rankResolution?.glyph,
                ocrGuess = rankResolution?.ocrGuess,
                ocrTrace = rankResolution?.ocrTrace,
                picked = rank,
                rankTemplates = rankTemplates,
                strongBitmap = rankResolution?.strongBitmap == true
            )
            val trace = rankTrace.merge(suitTrace)
            if (suit != null && rank != null) {
                return RecognitionHit(
                    card = Card(rank.first, suit, faceUp = true, known = true),
                    confidence = rank.second,
                    isFaceDown = false,
                    isEmpty = false,
                    diagnostic = "bitmap-${rank.first.name}-${suit.name}@${"%.2f".format(rank.second)}",
                    inferredRed = suit.isRed,
                    trace = trace,
                    rankScores = rankScoreMap,
                    suitScores = suitScoreMap
                )
            }
            if (suit == null && rank != null && inkRed == false && crop != null) {
                val guess = ambiguousBlackSuit(crop)
                return RecognitionHit(
                    card = Card(
                        rank.first,
                        guess.first,
                        faceUp = true,
                        known = true,
                        suitAmbiguous = guess.second
                    ),
                    confidence = rank.second,
                    isFaceDown = false,
                    isEmpty = false,
                    diagnostic = "bitmap-${rank.first.name}-${guess.first.name}-ambiguous",
                    inferredRed = false,
                    trace = trace.merge(
                        RecognitionTrace(suitSource = "suit-ambiguous-black")
                    ).let { t ->
                        guess.third?.let { t.withPost("black-tiebreak:$it") } ?: t
                    }
                )
            }
            if (suit == null && rank != null && inkRed == true && crop != null) {
                val guess = ambiguousRedSuit(crop)
                return RecognitionHit(
                    card = Card(
                        rank.first,
                        guess.first,
                        faceUp = true,
                        known = true,
                        suitAmbiguous = guess.second
                    ),
                    confidence = rank.second,
                    isFaceDown = false,
                    isEmpty = false,
                    diagnostic = "bitmap-${rank.first.name}-${guess.first.name}-ambiguous",
                    inferredRed = true,
                    trace = trace.merge(
                        RecognitionTrace(suitSource = "suit-ambiguous-red")
                    )
                )
            }
            return RecognitionHit(
                card = null,
                confidence = 0.55f,
                isFaceDown = false,
                isEmpty = false,
                diagnostic = "face-up-color-${inkRed?.let { if (it) "red" else "black" } ?: "unknown"}",
                inferredRed = inkRed,
                trace = trace
            )
        } finally {
            rankCropFallback?.takeUnless { it.isRecycled }?.recycle()
            crop?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    fun rankShapeGuess(
        bitmap: Bitmap,
        region: BoardRegion
    ): RankInkHeuristics.Guess? {
        val cardCrop = crop(bitmap, region) ?: return null
        return try {
            RankInkHeuristics.guess(cardCrop)
        } finally {
            cardCrop.recycle()
        }
    }

    fun exactRankTemplateScores(
        bitmap: Bitmap,
        region: BoardRegion,
        ranks: Set<Rank>
    ): Map<Rank, Float> {
        ensureLoaded()
        val cardCrop = crop(bitmap, region) ?: return emptyMap()
        return try {
            val width = (cardCrop.width * 0.70f).toInt().coerceIn(8, cardCrop.width)
            val height = (cardCrop.height * 0.54f).toInt().coerceIn(8, cardCrop.height)
            val roi = Bitmap.createBitmap(cardCrop, 0, 0, width, height)
            try {
                val source = inkMask(roi)
                ranks.mapNotNull { rank ->
                    bitmapRankTemplates[rank]?.let { templates ->
                        rank to templates.maxOf { template -> maskScore(source, template) }
                    }
                }.toMap()
            } finally {
                roi.recycle()
            }
        } finally {
            cardCrop.recycle()
        }
    }

    fun suitTemplateScores(
        bitmap: Bitmap,
        region: BoardRegion,
        suits: Set<Suit>
    ): Map<Suit, Float> {
        ensureLoaded()
        val cardCrop = crop(bitmap, region) ?: return emptyMap()
        return try {
            suitTemplateScoresFromCrop(cardCrop, suits)
        } finally {
            cardCrop.recycle()
        }
    }

    fun suitTemplateScoresFromCrop(
        cardCrop: Bitmap,
        suits: Set<Suit>
    ): Map<Suit, Float> {
        ensureLoaded()
        val sourceMasks = suitBadgeMasks(cardCrop, preferLocatedBadge = true)
        return suitScoresFromMasks(sourceMasks, suits)
    }

    // Shared with bestBitmapSuit below: badge-mask extraction (suitBadgeMasks,
    // specifically its locateBadge OpenCV call) is the expensive part of suit
    // recognition, and recognize() used to trigger it twice per card — once
    // here for the diagnostic score map, once independently inside
    // bestBitmapSuit for the actual decision. Both now take the same
    // precomputed masks instead.
    private fun suitScoresFromMasks(
        sourceMasks: List<LongArray>,
        suits: Set<Suit>
    ): Map<Suit, Float> =
        suits.mapNotNull { suit ->
            bitmapSuitTemplates[suit]?.let { templates ->
                suit to maxSuitTemplateScore(sourceMasks, templates, topHalf = false)
            }
        }.toMap()

    fun blackSuitTemplateScores(bitmap: Bitmap, region: BoardRegion): BlackSuitTemplateScores {
        ensureLoaded()
        val cardCrop = crop(bitmap, region) ?: return BlackSuitTemplateScores(0f, 0f, 0f, 0f)
        return try {
            blackSuitScoresFromCrop(cardCrop)
        } finally {
            cardCrop.recycle()
        }
    }

    fun blackSuitAmbiguous(
        bitmap: Bitmap,
        region: BoardRegion
    ): Boolean {
        val scores = suitTemplateScores(bitmap, region, setOf(Suit.Clubs, Suit.Spades))
        val club = scores[Suit.Clubs] ?: 0f
        val spade = scores[Suit.Spades] ?: 0f
        val best = max(club, spade)
        val margin = abs(club - spade)
        return best < 0.72f || margin < BLACK_SUIT_MARGIN
    }

    fun release() {
        rankTemplates.values.forEach { it.release() }
        suitTemplates.values.flatten().forEach { it.release() }
        emptyTemplate?.release()
        faceDownTemplate?.release()
        bitmapRankTemplates.clear()
        bitmapRankTemplatesTrimmed.clear()
        bitmapSuitTemplates.clear()
        rankTemplates.clear()
        suitTemplates.clear()
        emptyTemplate = null
        faceDownTemplate = null
        rankCornerOcr?.close()
        rankCornerOcr = null
        ocrReady = false
        loaded = false
    }

    private fun suitCandidatesForInk(inkRed: Boolean?): Set<Suit> = when (inkRed) {
        true -> setOf(Suit.Hearts, Suit.Diamonds)
        false -> setOf(Suit.Clubs, Suit.Spades)
        null -> Suit.entries.toSet()
    }

    private data class RankResolution(
        val rank: Pair<Rank, Float>?,
        val bitmapRankHit: Pair<Rank, Float>?,
        val rankHit: Pair<Rank, Float>?,
        val glyph: RankInkHeuristics.Guess?,
        val ocrGuess: RankCornerOcr.Guess?,
        val ocrTrace: String?,
        val strongBitmap: Boolean
    )

    private fun resolveRankFromCrop(
        crop: Bitmap,
        rankScoreMap: Map<Rank, Float>,
        trimmedToVisibleStrip: Boolean = false
    ): RankResolution {
        val bitmapRankHit = bestBitmapRank(rankScoreMap)
        val strongBitmap = bitmapRankHit != null && bitmapRankHit.second >= 0.68f
        if (strongBitmap) {
            return RankResolution(
                rank = bitmapRankHit,
                bitmapRankHit = bitmapRankHit,
                rankHit = null,
                glyph = null,
                ocrGuess = null,
                ocrTrace = null,
                strongBitmap = true
            )
        }
        val rankHit = bestRank(crop, trimmedToVisibleStrip)
        // RankInkHeuristics.guess reads the large glyph in a card's CENTER -
        // on a tableau cascade card trimmed to just its own ~45-54px visible
        // header strip there is no center glyph, only the small corner digit
        // (and mostly blank space below it). Its center-band math then lands
        // on the bottom of that corner digit plus blank space, a shape that
        // matches nothing real; the "Ten" rule is the loosest one it has
        // (aspect>1.18, density 0.10-0.30, no other constraint), so it kept
        // winning almost regardless of the true digit. This was the actual
        // cause behind all four attempts at this bug so far - none of them
        // touched this heuristic, which is entirely separate from
        // rankSourceMasks/rankTemplateScoreMap.
        val glyph = if (trimmedToVisibleStrip) null else RankInkHeuristics.guess(crop)
        val ocrAttempt = if (needsOcrTiebreak(bitmapRankHit, rankHit, glyph)) {
            val profile = if (trimmedToVisibleStrip) {
                RankCornerOcr.CornerRoiProfile.TRIMMED
            } else {
                RankCornerOcr.CornerRoiProfile.DEFAULT
            }
            rankCornerOcr?.attempt(crop, profile)
                ?: RankCornerOcr.AttemptResult(null, "ocr=miss:unavailable")
        } else {
            null
        }
        val ocrGuess = ocrAttempt?.guess
        val rank = pickRank(bitmapRankHit, rankHit, glyph, ocrGuess)
        return RankResolution(
            rank = rank,
            bitmapRankHit = bitmapRankHit,
            rankHit = rankHit,
            glyph = glyph,
            ocrGuess = ocrGuess,
            ocrTrace = ocrAttempt?.trace,
            strongBitmap = false
        )
    }

    private fun rankCandidates(
        bitmapHit: Pair<Rank, Float>?,
        rankHit: Pair<Rank, Float>?,
        glyph: RankInkHeuristics.Guess?
    ): List<Pair<Rank, Float>> = listOfNotNull(
        bitmapHit,
        rankHit,
        glyph?.let { it.rank to it.confidence }
    )

    private fun needsOcrTiebreak(
        bitmapHit: Pair<Rank, Float>?,
        rankHit: Pair<Rank, Float>?,
        glyph: RankInkHeuristics.Guess?
    ): Boolean {
        if (bitmapHit != null && bitmapHit.second >= 0.68f) return false
        val candidates = rankCandidates(bitmapHit, rankHit, glyph)
        // No template/glyph candidate at all is the strongest case for OCR,
        // not the weakest - this used to decline it, treating "nothing to
        // tiebreak" the same as "no need to tiebreak". A fresh device log
        // showed dozens of trimmed cascade cards stuck at rank=null with no
        // ocr= attempt anywhere in their trace, while the diagnostic-only
        // probe (same card, untrimmed truth bounds) read the digit fine -
        // the real pipeline's OCR was never even given a chance.
        if (candidates.isEmpty()) return true

        val sorted = candidates.sortedByDescending { it.second }
        if (sorted[0].second < 0.68f) return true
        if (sorted.size >= 2 && sorted[0].second - sorted[1].second < 0.08f) return true

        val ranks = candidates.map { it.first }.toSet()
        if (ranks.contains(Rank.Ten) && ranks.contains(Rank.Queen)) return true
        if (ranks.contains(Rank.King) && ranks.contains(Rank.Ten)) return true
        if (ranks.contains(Rank.Jack) && ranks.contains(Rank.Three)) return true
        if (ranks.size >= 2) return true
        return false
    }

    private fun buildRankTrace(
        bitmapRankHit: Pair<Rank, Float>?,
        rankHit: Pair<Rank, Float>?,
        glyph: RankInkHeuristics.Guess?,
        ocrGuess: RankCornerOcr.Guess? = null,
        ocrTrace: String? = null,
        picked: Pair<Rank, Float>?,
        rankTemplates: String?,
        strongBitmap: Boolean
    ): RecognitionTrace {
        val trace = if (picked == null) {
            RecognitionTrace(rankTemplates = rankTemplates)
        } else {
            val source = when {
                strongBitmap -> "rank-png-strong"
                ocrGuess != null && picked.first == ocrGuess.rank &&
                    bitmapRankHit != null && picked.first == bitmapRankHit.first &&
                    rankHit != null && picked.first == rankHit.first -> "rank-ocr+png+opencv"
                ocrGuess != null && picked.first == ocrGuess.rank &&
                    bitmapRankHit != null && picked.first == bitmapRankHit.first -> "rank-ocr+png"
                ocrGuess != null && picked.first == ocrGuess.rank &&
                    rankHit != null && picked.first == rankHit.first -> "rank-ocr+opencv"
                ocrGuess != null && picked.first == ocrGuess.rank &&
                    glyph != null && picked.first == glyph.rank -> "rank-ocr+glyph"
                ocrGuess != null && picked.first == ocrGuess.rank -> "rank-ocr"
                bitmapRankHit != null && picked.first == bitmapRankHit.first &&
                    rankHit != null && picked.first == rankHit.first -> "rank-png+opencv"
                bitmapRankHit != null && picked.first == bitmapRankHit.first &&
                    glyph != null && picked.first == glyph.rank -> "rank-png+glyph"
                rankHit != null && picked.first == rankHit.first &&
                    glyph != null && picked.first == glyph.rank -> "rank-opencv+glyph"
                bitmapRankHit != null && picked.first == bitmapRankHit.first -> "rank-png"
                rankHit != null && picked.first == rankHit.first -> "rank-opencv"
                glyph != null && picked.first == glyph.rank -> "rank-glyph"
                else -> "rank-blend"
            }
            RecognitionTrace(
                rankSource = source,
                rankScore = picked.second,
                rankTemplates = rankTemplates
            )
        }
        return if (ocrTrace != null) trace.withPost(ocrTrace) else trace
    }

    private fun rankSourceMasks(
        crop: Bitmap,
        exactCardBounds: Boolean,
        trimmedToVisibleStrip: Boolean = false
    ): List<LongArray> {
        val sourceMasks = mutableListOf<LongArray>()
        if (trimmedToVisibleStrip) {
            // Three earlier attempts at this crop (all reverted) only ever
            // adjusted height and kept the 70%-of-width ROI below. Measured
            // directly against golden pixels: on a card's visible header
            // strip the rank digit's ink is a single contiguous run that
            // always ends by ~30% of card width, and the neighboring suit
            // pip never starts before ~70% - on a full ~193px card the 54%
            // height cut kept the pip below the sampled box entirely, so
            // 70% width was harmless; once the crop is trimmed to the
            // ~45-54px visible strip, that same 70% width now reaches the
            // pip's left edge. A merged digit+pip ink blob reads as a wide
            // two-glyph shape and matches "Ten" - the only two-character
            // rank - almost regardless of the true digit. That is the actual
            // root cause behind every previous attempt here; none of them
            // touched width. 50% leaves a wide margin on both sides (digit
            // ends ~30%, pip starts ~70%). Two height fractions because a
            // tall glyph like "8" measured using close to the full trimmed
            // strip while "6" needed noticeably less - maxOf over template
            // scores lets either framing win.
            // The 50%-width ROI above is deliberately wider than the digit
            // itself (keeps the suit pip out); inkMask then stretches that
            // extra whitespace right along with the digit into the 48x48
            // grid, shrinking the digit's relative size compared to the
            // snugly-cropped rank templates. Content-crop to the digit's own
            // ink before the resample so both sides get framed the same way
            // - see tightContentCrop for the measured before/after.
            val w = (crop.width * 0.50f).toInt().coerceIn(8, crop.width)
            for (hFraction in listOf(0.70f, 0.90f)) {
                val h = (crop.height * hFraction).toInt().coerceIn(8, crop.height)
                val roi = Bitmap.createBitmap(crop, 0, 0, w, h)
                val tight = tightContentCrop(roi)
                sourceMasks += inkMask(tight)
                if (tight !== roi) tight.recycle()
                roi.recycle()
            }
            return sourceMasks
        }
        val w = (crop.width * 0.70f).toInt().coerceIn(8, crop.width)
        val h = (crop.height * 0.54f).toInt().coerceAtLeast(8)
        Bitmap.createBitmap(crop, 0, 0, w, h.coerceAtMost(crop.height)).let { roi ->
            sourceMasks += inkMask(roi)
            roi.recycle()
        }
        if (!exactCardBounds) {
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
        }
        return sourceMasks
    }

    private fun rankTemplateScoreMap(
        crop: Bitmap,
        exactCardBounds: Boolean,
        trimmedToVisibleStrip: Boolean = false
    ): Map<Rank, Float> {
        val templates = if (trimmedToVisibleStrip) bitmapRankTemplatesTrimmed else bitmapRankTemplates
        if (templates.isEmpty()) return emptyMap()
        val sourceMasks = rankSourceMasks(crop, exactCardBounds, trimmedToVisibleStrip)
        return templates.mapValues { (_, templateMasks) ->
            templateMasks.maxOf { templateMask ->
                sourceMasks.maxOf { source -> maskScore(source, templateMask) }
            }
        }
    }

    private fun inferSuitWithTrace(
        crop: Bitmap,
        inkRed: Boolean?,
        suitScoreMap: Map<Suit, Float>,
        suitSourceMasks: List<LongArray>
    ): Pair<Suit?, RecognitionTrace> {
        val templateStr = RecognitionTrace.formatSuitScores(suitScoreMap)
        val blackDebugLines = mutableListOf<String>()
        val bitmapSuit = bestBitmapSuit(crop, inkRed, suitSourceMasks) { blackDebugLines += it }
        if (bitmapSuit != null && bitmapSuit.second >= 0.45f &&
            (inkRed == null || bitmapSuit.first.isRed == inkRed)
        ) {
            val base = RecognitionTrace(
                suitSource = "suit-png",
                suitScore = bitmapSuit.second,
                suitTemplates = templateStr
            )
            return bitmapSuit.first to if (blackDebugLines.isNotEmpty()) {
                base.withPost("black-tiebreak:${blackDebugLines.joinToString(";")}")
            } else {
                base
            }
        }
        if (inkRed == false) {
            SuitBadgeHeuristics.guessBlackSuit(crop)?.let { shape ->
                if (shape.margin >= BLACK_SHAPE_MIN_MARGIN && shape.confidence >= 0.52f) {
                    return shape.suit to RecognitionTrace(
                        suitSource = "suit-shape-black",
                        suitScore = shape.confidence,
                        suitTemplates = templateStr
                    )
                }
            }
        }
        if (inkRed == true) {
            SuitBadgeHeuristics.guessRedSuit(crop)?.let { shape ->
                if (shape.margin >= RED_SHAPE_MIN_MARGIN && shape.confidence >= 0.52f) {
                    return shape.suit to RecognitionTrace(
                        suitSource = "suit-shape-red",
                        suitScore = shape.confidence,
                        suitTemplates = templateStr
                    )
                }
            }
        }
        val templateSuit = bestSuit(crop)
        if (templateSuit != null && inkRed != null) {
            val candidates = Suit.entries.filter { it.isRed == inkRed }
            val filtered = candidates.mapNotNull { suit ->
                suitTemplates[suit]?.let { templates ->
                    val score = templates.maxOf { template ->
                        matchTemplate(crop, template, badgeOnly = true, suitPip = true)
                    }
                    suit to score
                }
            }.maxByOrNull { it.second }
            if (filtered != null && filtered.second >= 0.45f) {
                return filtered.first to RecognitionTrace(
                    suitSource = "suit-opencv",
                    suitScore = filtered.second,
                    suitTemplates = templateStr
                )
            }
            if (inkRed == false || inkRed == true) {
                return null to RecognitionTrace(suitTemplates = templateStr)
            }
            return candidates.firstOrNull { it == templateSuit.first } to RecognitionTrace(
                suitSource = "suit-opencv",
                suitScore = templateSuit.second,
                suitTemplates = templateStr
            )
        }
        if (templateSuit != null && templateSuit.second >= 0.5f) {
            return templateSuit.first to RecognitionTrace(
                suitSource = "suit-opencv",
                suitScore = templateSuit.second,
                suitTemplates = templateStr
            )
        }
        return null to RecognitionTrace(suitTemplates = templateStr)
    }

    private fun bestBitmapSuit(
        crop: Bitmap,
        isRed: Boolean? = null,
        sourceMasks: List<LongArray> = suitBadgeMasks(crop, preferLocatedBadge = true),
        blackDebug: ((String) -> Unit)? = null
    ): Pair<Suit, Float>? {
        if (bitmapSuitTemplates.isEmpty()) return null
        var clubScore = 0f
        var spadeScore = 0f
        var heartScore = 0f
        var diamondScore = 0f
        var best: Pair<Suit, Float>? = null
        var second = 0f
        bitmapSuitTemplates
            .filterKeys { suit -> isRed == null || suit.isRed == isRed }
            .forEach { (suit, templateMasks) ->
            val score = templateMasks.maxOf { templateMask ->
                sourceMasks.maxOf { source -> maskScore(source, templateMask) }
            }
            when (suit) {
                Suit.Clubs -> clubScore = score
                Suit.Spades -> spadeScore = score
                Suit.Hearts -> heartScore = score
                Suit.Diamonds -> diamondScore = score
            }
            if (best == null || score > best!!.second) {
                second = best?.second ?: 0f
                best = suit to score
            } else if (score > second) {
                second = score
            }
        }
        if (isRed == false) {
            val topClub = topBlackScore(sourceMasks, Suit.Clubs)
            val topSpade = topBlackScore(sourceMasks, Suit.Spades)
            val fullMargin = kotlin.math.abs(clubScore - spadeScore)
            if (fullMargin < BLACK_SUIT_TOP_TIEBREAK_MAX) {
                val tiebreakShape = SuitBadgeHeuristics.guessBlackSuit(
                    crop,
                    TOP_BLACK_SHAPE_VETO_MARGIN
                )
                val scores = BlackSuitTemplateScores(clubScore, spadeScore, topClub, topSpade)
                val (leader, ambiguous) = resolveThinBlackLeader(scores, crop, tiebreakShape, blackDebug)
                if (leader == null) return null
                val leaderScore = if (leader == Suit.Spades) topSpade else topClub
                return leader to leaderScore
            }
            val shape = SuitBadgeHeuristics.guessBlackSuit(crop)
            val topMargin = kotlin.math.abs(topClub - topSpade)
            val topLeader = if (topSpade > topClub) Suit.Spades else Suit.Clubs
            // The full-score margin alone can look "thin enough to override" while the
            // top-region score (isolating just the pip's tip/shoulders) is decisively
            // confident in the template leader - two real cases (QC misread as QS, 10C
            // misread as 10S) had full margin ~0.06-0.07 but top margin 0.14 favoring the
            // correct, already-leading suit. Don't let the shape heuristic override a
            // leader the top score independently confirms this strongly.
            val topConfirmsLeader = best?.first != null &&
                topLeader == best!!.first &&
                topMargin >= TOP_BLACK_SUIT_MARGIN
            // REVERTED (v1.4.14): the BLACK_SHAPE_DECISIVE_MARGIN escape added in
            // v1.4.12 was validated only against waste-pile badges (43x51 crop) and
            // caused a real regression on tableau cascade cards, a different crop
            // scale the geometric shape heuristic is NOT reliable at. Real traces:
            // tableau Queen of Clubs (full=C0.93/S0.86, top=C0.94/S0.82 - both
            // signals already correctly favored Clubs, topConfirmsLeader was
            // protecting that correct read exactly as designed) got flipped to
            // Queen of Spades anyway once shapeDecisive was allowed to bypass
            // topConfirmsLeader - same pattern on 9C->9S and 8C->8S~. That single
            // change alone drove Clubs->Spades confusions from 5 to 23 on the next
            // device run. Back to the pre-v1.4.12 gate: never let the shape
            // heuristic override when both template signals already agree.
            if (shape != null &&
                shape.margin >= BLACK_SHAPE_MIN_MARGIN &&
                !topConfirmsLeader &&
                (fullMargin < BLACK_SUIT_MARGIN * 1.6f || shape.suit == best?.first)
            ) {
                blackDebug?.invoke(
                    "full=C${"%.2f".format(clubScore)}/S${"%.2f".format(spadeScore)}," +
                        "top=C${"%.2f".format(topClub)}/S${"%.2f".format(topSpade)}," +
                        "branch=wideMarginShapeOverride->${shape.suit}"
                )
                best = shape.suit to max(clubScore, spadeScore) + 0.03f
                second = min(clubScore, spadeScore)
            } else {
                blackDebug?.invoke(
                    "full=C${"%.2f".format(clubScore)}/S${"%.2f".format(spadeScore)}," +
                        "top=C${"%.2f".format(topClub)}/S${"%.2f".format(topSpade)}," +
                        "branch=wideMarginDirect->${best?.first}"
                )
            }
        }
        if (isRed == true) {
            val shape = SuitBadgeHeuristics.guessRedSuit(crop)
            val templateMargin = kotlin.math.abs(heartScore - diamondScore)
            val templateLead = when {
                diamondScore > heartScore + 0.02f -> Suit.Diamonds
                heartScore > diamondScore + 0.02f -> Suit.Hearts
                else -> null
            }
            if (templateMargin < RED_SUIT_MARGIN) {
                if (shape != null && shape.margin >= RED_SHAPE_MIN_MARGIN) {
                    return shape.suit to max(heartScore, diamondScore) + 0.04f
                }
                return null
            }
            if (shape != null && shape.margin >= RED_SHAPE_MIN_MARGIN) {
                val preferShape = when {
                    shape.suit == templateLead -> true
                    shape.suit == Suit.Diamonds &&
                        templateLead == Suit.Hearts &&
                        shape.margin >= 0.16f &&
                        templateMargin < RED_SUIT_MARGIN * 1.25f -> true
                    else -> false
                }
                if (preferShape) {
                    best = shape.suit to max(heartScore, diamondScore) + 0.04f
                    second = min(heartScore, diamondScore)
                }
            }
        }
        val top = best ?: return null
        if (top.second < 0.42f) return null
        val requiredMargin = when (isRed) {
            false -> BLACK_SUIT_MARGIN
            true -> RED_SUIT_MARGIN
            null -> 0.025f
        }
        val confidenceFloor = when (isRed) {
            false -> 0.76f
            true -> 0.66f
            null -> 0.68f
        }
        if (isRed == true && top.second - second < requiredMargin) return null
        if (isRed == false && top.second - second < requiredMargin) return null
        if (top.second - second < requiredMargin && top.second < confidenceFloor) return null
        return top
    }

    private fun suitBadgeMasks(
        crop: Bitmap,
        preferLocatedBadge: Boolean
    ): List<LongArray> {
        val masks = mutableListOf<LongArray>()
        if (preferLocatedBadge) {
            val located = SuitBadgeHeuristics.locateBadge(crop)
            if (located != null) {
                try {
                    masks += inkMask(located.bitmap)
                    val pad = max(1, min(located.width, located.height) / 8)
                    val left = (located.left - pad).coerceAtLeast(0)
                    val top = (located.top - pad).coerceAtLeast(0)
                    val right = (located.left + located.width + pad).coerceAtMost(crop.width)
                    val bottom = (located.top + located.height + pad).coerceAtMost(crop.height)
                    if (right - left >= 8 && bottom - top >= 8) {
                        val expanded = Bitmap.createBitmap(
                            crop,
                            left,
                            top,
                            right - left,
                            bottom - top
                        )
                        masks += inkMask(expanded)
                        expanded.recycle()
                    }
                } finally {
                    if (!located.bitmap.isRecycled) located.bitmap.recycle()
                }
            }
            if (masks.isNotEmpty()) return masks
            val x = (crop.width * 0.58f).toInt().coerceIn(0, crop.width - 8)
            val y = 0
            val w = (crop.width * 0.34f).toInt().coerceAtLeast(8).coerceAtMost(crop.width - x)
            val h = (crop.height * 0.24f).toInt().coerceAtLeast(8).coerceAtMost(crop.height)
            val roi = Bitmap.createBitmap(crop, x, y, w, h)
            masks += inkMask(roi)
            roi.recycle()
            return masks
        }
        val width = (crop.width * 0.38f).toInt().coerceAtLeast(8)
        val height = (crop.height * 0.31f).toInt().coerceAtLeast(8)
        val xFractions = listOf(0.06f, 0.10f, 0.54f, 0.58f, 0.61f, 0.64f)
        val yFractions = listOf(0.0f, 0.03f, 0.06f)
        for (xFraction in xFractions) {
            val x = (crop.width * xFraction).toInt().coerceIn(0, crop.width - 8)
            val actualWidth = width.coerceAtMost(crop.width - x)
            for (yFraction in yFractions) {
                val y = (crop.height * yFraction).toInt().coerceIn(0, crop.height - 8)
                val actualHeight = height.coerceAtMost(crop.height - y)
                val roi = Bitmap.createBitmap(crop, x, y, actualWidth, actualHeight)
                masks += inkMask(roi)
                roi.recycle()
            }
        }
        return masks
    }

    /** Triple of resolved suit, whether the guess is low-confidence, and a tiebreak trace. */
    private fun ambiguousBlackSuit(crop: Bitmap): Triple<Suit, Boolean, String?> {
        val shape = SuitBadgeHeuristics.guessBlackSuit(crop)
        if (shape != null && shape.margin >= BLACK_SHAPE_MIN_MARGIN) {
            return Triple(shape.suit, shape.margin < 0.45f, null)
        }
        val tiebreakShape = SuitBadgeHeuristics.guessBlackSuit(crop, TOP_BLACK_SHAPE_VETO_MARGIN)
        val scores = blackSuitScoresFromCrop(crop)
        val debugLines = mutableListOf<String>()
        val (leader, ambiguous) = resolveBlackSuitLeader(scores, crop, tiebreakShape) { debugLines += it }
        val debug = debugLines.takeIf { it.isNotEmpty() }?.joinToString(";")
        if (leader != null) {
            return Triple(leader, ambiguous, debug)
        }
        val loose = bestBitmapSuitLoose(crop, red = false)
        if (loose != null) return Triple(loose, true, debug)
        val fallback = shape?.suit
            ?: if (scores.fullSpade >= scores.fullClub) Suit.Spades else Suit.Clubs
        return Triple(fallback, true, debug)
    }

    private fun ambiguousRedSuit(crop: Bitmap): Pair<Suit, Boolean> {
        val (heartScore, diamondScore) = redBitmapScores(crop)
        val margin = kotlin.math.abs(heartScore - diamondScore)
        if (max(heartScore, diamondScore) >= 0.40f && margin >= 0.025f) {
            val suit = if (diamondScore > heartScore) Suit.Diamonds else Suit.Hearts
            return suit to (margin < RED_SUIT_MARGIN)
        }
        val shape = SuitBadgeHeuristics.guessRedSuit(crop)
        if (shape != null &&
            shape.margin >= RED_SHAPE_MIN_MARGIN &&
            !(shape.suit == Suit.Hearts && diamondScore > heartScore + 0.02f)
        ) {
            return shape.suit to (shape.margin < 0.14f)
        }
        val loose = bestBitmapSuitLoose(crop, red = true)
        if (loose != null) return loose to true
        val fallback = shape?.suit
            ?: if (diamondScore >= heartScore) Suit.Diamonds else Suit.Hearts
        return fallback to true
    }

    private fun blackBitmapScores(crop: Bitmap): Pair<Float, Float> {
        val scores = blackSuitScoresFromCrop(crop)
        return scores.fullClub to scores.fullSpade
    }

    private fun blackSuitScoresFromCrop(crop: Bitmap): BlackSuitTemplateScores {
        if (bitmapSuitTemplates.isEmpty()) {
            return BlackSuitTemplateScores(0f, 0f, 0f, 0f)
        }
        val sourceMasks = suitBadgeMasks(crop, preferLocatedBadge = true)
        val fullClub = maxSuitTemplateScore(sourceMasks, Suit.Clubs, topHalf = false)
        val fullSpade = maxSuitTemplateScore(sourceMasks, Suit.Spades, topHalf = false)
        val topClub = maxSuitTemplateScore(sourceMasks, Suit.Clubs, topHalf = true)
        val topSpade = maxSuitTemplateScore(sourceMasks, Suit.Spades, topHalf = true)
        return BlackSuitTemplateScores(fullClub, fullSpade, topClub, topSpade)
    }

    private fun maxSuitTemplateScore(
        sourceMasks: List<LongArray>,
        suit: Suit,
        topHalf: Boolean
    ): Float {
        val templates = bitmapSuitTemplates[suit] ?: return 0f
        return maxSuitTemplateScore(sourceMasks, templates, topHalf)
    }

    private fun maxSuitTemplateScore(
        sourceMasks: List<LongArray>,
        templates: List<LongArray>,
        topHalf: Boolean
    ): Float {
        if (templates.isEmpty()) return 0f
        return templates.maxOf { template ->
            val templateMask = if (topHalf) {
                SuitBadgeHeuristics.topHalfInkMask(template, TOP_BLACK_FRACTION)
            } else {
                template
            }
            sourceMasks.maxOf { source ->
                val sourceMask = if (topHalf) {
                    SuitBadgeHeuristics.topHalfInkMask(source, TOP_BLACK_FRACTION)
                } else {
                    source
                }
                maskScore(sourceMask, templateMask)
            }
        }
    }

    private fun topBlackScore(sourceMasks: List<LongArray>, suit: Suit): Float =
        maxSuitTemplateScore(sourceMasks, suit, topHalf = true)

    /** Returns leader suit and whether the read should stay ambiguous. */
    fun resolveBlackSuitLeader(
        scores: BlackSuitTemplateScores,
        crop: Bitmap? = null,
        tiebreakShape: SuitBadgeHeuristics.Guess? = null,
        debug: ((String) -> Unit)? = null
    ): Pair<Suit?, Boolean> {
        if (max(scores.fullClub, scores.fullSpade) < 0.40f &&
            max(scores.topClub, scores.topSpade) < 0.40f
        ) {
            debug?.invoke("branch=belowFloor->ambiguous")
            return null to true
        }
        val fullLeader = if (scores.fullSpade > scores.fullClub) Suit.Spades else Suit.Clubs
        val topLeader = if (scores.topSpade > scores.topClub) Suit.Spades else Suit.Clubs
        val preferTopHalf = fullLeader != topLeader &&
            scores.topMargin > scores.fullMargin &&
            scores.topMargin >= TOP_BLACK_SUIT_MARGIN &&
            !shouldVetoTopHalfForSpadeTip(scores, crop, fullLeader, topLeader)
        if (preferTopHalf || scores.fullMargin < BLACK_SUIT_TOP_TIEBREAK_MAX) {
            return resolveThinBlackLeader(scores, crop, tiebreakShape, debug)
        }
        debug?.invoke("branch=fullLeaderDirect->$fullLeader")
        return fullLeader to false
    }

    private fun resolveThinBlackLeader(
        scores: BlackSuitTemplateScores,
        crop: Bitmap?,
        tiebreakShape: SuitBadgeHeuristics.Guess?,
        debug: ((String) -> Unit)? = null
    ): Pair<Suit?, Boolean> {
        debug?.invoke(
            "full=C${"%.2f".format(scores.fullClub)}/S${"%.2f".format(scores.fullSpade)}," +
                "top=C${"%.2f".format(scores.topClub)}/S${"%.2f".format(scores.topSpade)}," +
                "shape=${tiebreakShape?.suit?.name ?: "none"}" +
                (tiebreakShape?.let { "@${"%.2f".format(it.margin)}" } ?: "")
        )
        if (scores.topMargin < TOP_BLACK_SUIT_MARGIN) {
            if (tiebreakShape != null && tiebreakShape.margin >= TOP_BLACK_SHAPE_VETO_MARGIN) {
                if (tiebreakShape.suit == Suit.Clubs &&
                    crop != null &&
                    spadeShapeBlocksClubVeto(crop) &&
                    !widePeakClubShapeVetoApplies(scores)
                ) {
                    debug?.invoke("branch=lowTopMargin-clubShapeBlockedBySpadeShape->ambiguous")
                    return null to true
                }
                debug?.invoke("branch=lowTopMargin-shapeLeader->${tiebreakShape.suit}")
                return tiebreakShape.suit to (tiebreakShape.margin < TOP_BLACK_SHAPE_VETO_MARGIN * 1.5f)
            }
            debug?.invoke("branch=lowTopMargin-noShape->ambiguous")
            return null to true
        }
        val topLeader = if (scores.topSpade > scores.topClub) Suit.Spades else Suit.Clubs
        if (topLeader == Suit.Spades &&
            tiebreakShape?.suit == Suit.Clubs &&
            tiebreakShape.margin >= TOP_BLACK_SHAPE_VETO_MARGIN
        ) {
            if (crop != null && SuitBadgeHeuristics.blackSuitClubLobeEvidence(crop)) {
                debug?.invoke("branch=topSpade-clubLobeEvidence->Clubs")
                return Suit.Clubs to (tiebreakShape.margin < TOP_BLACK_SHAPE_VETO_MARGIN * 1.5f)
            }
            val widePeakOnly = tiebreakShape.margin < 0.38f
            if (widePeakOnly && !widePeakClubShapeVetoApplies(scores)) {
                debug?.invoke("branch=topSpade-widePeakOnlyNoVeto->Spades")
                return Suit.Spades to (scores.topMargin < TOP_BLACK_SUIT_MARGIN * 1.25f)
            }
            if (crop != null &&
                spadeShapeBlocksClubVeto(crop) &&
                !widePeakClubShapeVetoApplies(scores)
            ) {
                debug?.invoke("branch=topSpade-spadeShapeBlocksVeto->Spades")
                return Suit.Spades to (scores.topMargin < TOP_BLACK_SUIT_MARGIN * 1.25f)
            }
            debug?.invoke("branch=topSpade-clubShapeVetoWins->Clubs")
            return Suit.Clubs to (tiebreakShape.margin < TOP_BLACK_SHAPE_VETO_MARGIN * 1.5f)
        }
        if (topLeader == Suit.Clubs &&
            crop != null &&
            spadeShapeBlocksClubVeto(crop) &&
            !SuitBadgeHeuristics.blackSuitClubLobeEvidence(crop) &&
            scores.topSpade + 0.02f >= scores.topClub
        ) {
            debug?.invoke("branch=topClub-spadeShapeOverride->Spades")
            return Suit.Spades to (scores.topMargin < TOP_BLACK_SUIT_MARGIN * 1.25f)
        }
        // A prior attempt trusted tiebreakShape==Spades here once its margin
        // passed TOP_BLACK_SHAPE_VETO_MARGIN*1.5 (~0.42) and was reverted after
        // an offline Python replica of this decision tree claimed the same
        // shapeMargin (1.08) occurred on both a confirmed-Spades and a
        // confirmed-Clubs card. Re-checked against real on-device
        // analysis.log traces (not the replica, which had drifted from the
        // Kotlin shape scorer) instead: every real topLeader==Clubs case with
        // tiebreakShape==Spades splits cleanly into two non-overlapping
        // clusters - genuine Spades cards (foundation/waste Aces/Kings/Twos,
        // shape margin 1.08, fullClub~0.81-0.83) vs. genuine Clubs cards
        // (tableau Sixes/Sevens, shape margin 0.50, fullClub~0.92-0.93).
        // Require both signals so a single noisy score can't flip a card on
        // its own.
        if (topLeader == Suit.Clubs &&
            tiebreakShape?.suit == Suit.Spades &&
            tiebreakShape.margin >= BLACK_TOP_CLUB_STRONG_SPADE_SHAPE_MARGIN &&
            scores.fullClub <= BLACK_TOP_CLUB_STRONG_SPADE_FULL_CLUB_MAX
        ) {
            debug?.invoke("branch=topClub-strongSpadeShapeOverride->Spades")
            return Suit.Spades to false
        }
        debug?.invoke("branch=topLeaderDirect->$topLeader")
        return topLeader to (scores.topMargin < TOP_BLACK_SUIT_MARGIN * 1.25f)
    }

    /** Keep full spades when top-half clubs only win on a spade-tip badge. */
    fun shouldVetoTopHalfForSpadeTip(
        scores: BlackSuitTemplateScores,
        crop: Bitmap?,
        fullLeader: Suit,
        topLeader: Suit
    ): Boolean {
        if (fullLeader != Suit.Spades || topLeader != Suit.Clubs || crop == null) return false
        if (!spadeShapeBlocksClubVeto(crop)) return false
        if (SuitBadgeHeuristics.blackSuitClubLobeEvidence(crop)) return false
        return scores.topSpade + 0.02f >= scores.topClub
    }

    /** Blocks club shape veto on spade tips (incl. wide peak + narrow shoulders). */
    private fun spadeShapeBlocksClubVeto(crop: Bitmap): Boolean {
        if (SuitBadgeHeuristics.blackSuitShoulderSpadeTip(crop)) return true
        return SuitBadgeHeuristics.blackSuitNarrowShoulderSpadePeak(crop)
    }

    /**
     * Wide-peak shape alone is unreliable; only veto spade templates when club
     * templates are competitive and the margin pattern matches known club failures.
     */
    private fun widePeakClubTemplatesCompetitive(scores: BlackSuitTemplateScores): Boolean {
        return scores.fullClub >= 0.858f ||
            (scores.fullClub >= 0.845f && scores.fullMargin < 0.038f)
    }

    private fun widePeakClubShapeVetoApplies(scores: BlackSuitTemplateScores): Boolean {
        if (!widePeakClubTemplatesCompetitive(scores)) return false
        if (scores.topMargin >= 0.100f && scores.fullClub >= 0.859f) return true
        if (scores.fullMargin < 0.038f && scores.topMargin < 0.048f) return true
        // A former third rule (fullMargin<0.048 && topMargin in [0.075,0.100) &&
        // fullClub>=0.859) covered this exact card skin's genuine Spade badge:
        // an offline replay of every golden black-suit slot found 10 independent,
        // visually-verified Spades (different ranks/samples, one hand-checked
        // against its actual card art) all landing in that band and getting
        // vetoed to Clubs, while removing the rule fixed all 10 with no case in
        // the set where a genuinely Clubs-scoring card (fullClub actually
        // leading, not just above 0.859) depended on it - this rule only ever
        // gates the topLeader==Spades path, which correctly-scoring Clubs cards
        // don't reach. See the black-tiebreak trace investigation for the data.
        return false
    }

    /**
     * Foundation and other tiny badges: wide-peak alone reads club but the pip is
     * often a spade when template scores are weak and nearly tied.
     */
    fun recoverLowConfidenceSpade(
        leader: Suit,
        clubScore: Float,
        spadeScore: Float,
        margin: Float,
        crop: Bitmap?,
        rank: Rank? = null,
        topClubScore: Float = 0f,
        topSpadeScore: Float = 0f,
        topMargin: Float = 0f
    ): Pair<Suit, Boolean>? {
        if (leader != Suit.Clubs || crop == null) return null
        if (SuitBadgeHeuristics.blackSuitClubLobeEvidence(crop)) return null
        if (SuitBadgeHeuristics.blackSuitWideClubTop(crop)) return null

        if (spadeShapeBlocksClubVeto(crop)) {
            val clubLeadsFull = clubScore > spadeScore + 0.035f
            val clubLeadsTop = topMargin >= TOP_BLACK_SUIT_MARGIN &&
                topClubScore > topSpadeScore + 0.035f
            val spadeCompetitive = spadeScore + 0.02f >= clubScore ||
                topSpadeScore + 0.02f >= topClubScore
            if (spadeCompetitive && !(clubLeadsFull && clubLeadsTop && margin >= 0.05f)) {
                return Suit.Spades to (margin < 0.05f || topMargin < TOP_BLACK_SUIT_MARGIN * 1.5f)
            }
        }

        if (margin >= 0.025f || max(clubScore, spadeScore) >= 0.80f) return null
        if (clubScore > spadeScore + 0.020f && rank != Rank.Four) return null
        if (!spadeShapeBlocksClubVeto(crop)) return null
        val noWidePeak = SuitBadgeHeuristics.guessBlackSuit(
            crop,
            minMargin = 0.22f,
            allowWidePeakClubRule = false
        ) ?: return null
        if (noWidePeak.suit != Suit.Spades || noWidePeak.margin < 0.22f) return null
        return Suit.Spades to true
    }

    private fun redBitmapScores(crop: Bitmap): Pair<Float, Float> {
        if (bitmapSuitTemplates.isEmpty()) return 0f to 0f
        val sourceMasks = suitBadgeMasks(crop, preferLocatedBadge = true)
        var heartScore = 0f
        var diamondScore = 0f
        bitmapSuitTemplates[Suit.Hearts]?.let { templates ->
            heartScore = templates.maxOf { template ->
                sourceMasks.maxOf { source -> maskScore(source, template) }
            }
        }
        bitmapSuitTemplates[Suit.Diamonds]?.let { templates ->
            diamondScore = templates.maxOf { template ->
                sourceMasks.maxOf { source -> maskScore(source, template) }
            }
        }
        return heartScore to diamondScore
    }

    /** Like bestBitmapSuit but returns the top suit even when the margin is thin. */
    private fun bestBitmapSuitLoose(crop: Bitmap, red: Boolean): Suit? {
        if (bitmapSuitTemplates.isEmpty()) return null
        val sourceMasks = suitBadgeMasks(crop, preferLocatedBadge = true)
        var best: Pair<Suit, Float>? = null
        bitmapSuitTemplates
            .filterKeys { it.isRed == red }
            .forEach { (suit, templateMasks) ->
                val score = templateMasks.maxOf { templateMask ->
                    sourceMasks.maxOf { source -> maskScore(source, templateMask) }
                }
                if (best == null || score > best!!.second) {
                    best = suit to score
                }
            }
        return best?.takeIf { it.second >= 0.40f }?.first
    }

    private fun pickRank(
        bitmapHit: Pair<Rank, Float>?,
        rankHit: Pair<Rank, Float>?,
        glyph: RankInkHeuristics.Guess?,
        ocrGuess: RankCornerOcr.Guess? = null
    ): Pair<Rank, Float>? {
        val base = pickRankBase(bitmapHit, rankHit, glyph)
        if (ocrGuess == null) return base
        if (bitmapHit != null && bitmapHit.second >= 0.68f) return base

        val candidates = rankCandidates(bitmapHit, rankHit, glyph)
        val matching = candidates.filter { it.first == ocrGuess.rank }
        if (matching.isNotEmpty()) {
            val confidence = max(matching.maxOf { it.second }, ocrGuess.confidence)
                .coerceAtLeast(0.52f)
            return ocrGuess.rank to confidence
        }

        val ranks = candidates.map { it.first }.toSet()
        if (ocrGuess.rank == Rank.Ten &&
            ranks.contains(Rank.King) &&
            ranks.contains(Rank.Ten)
        ) {
            return Rank.Ten to ocrGuess.confidence.coerceAtLeast(0.52f)
        }
        if (ocrGuess.rank == Rank.Queen &&
            ranks.contains(Rank.Ten) &&
            ranks.contains(Rank.Queen)
        ) {
            return Rank.Queen to ocrGuess.confidence.coerceAtLeast(0.52f)
        }
        if (ocrGuess.rank == Rank.Jack &&
            ranks.contains(Rank.Jack) &&
            ranks.contains(Rank.Three)
        ) {
            return Rank.Jack to ocrGuess.confidence.coerceAtLeast(0.52f)
        }
        if (ocrGuess.rank == Rank.Three &&
            ranks.contains(Rank.Jack) &&
            ranks.contains(Rank.Three)
        ) {
            return Rank.Three to ocrGuess.confidence.coerceAtLeast(0.52f)
        }
        // Every branch above only lets OCR confirm a rank some other source
        // already guessed, or step in on a few specific known confusions
        // (Ten/King, Queen/Ten, Jack/Three). Real cascade reads showed OCR
        // correctly reading '5' and '4' while glyph/bitmap matching missed
        // both entirely (neither even had the right rank as a candidate),
        // and the correct OCR read got silently thrown away in favor of a
        // weaker, wrong base guess. Only step in when OCR isn't even weaker
        // than the base it would be overriding — this doesn't assert OCR is
        // generally better, just that it shouldn't lose to something no more
        // confident than itself.
        if (base == null || ocrGuess.confidence >= base.second) {
            if (base == null || base.second < 0.68f) {
                return ocrGuess.rank to ocrGuess.confidence.coerceAtLeast(0.52f)
            }
        }
        return base
    }

    private fun pickRankBase(
        bitmapHit: Pair<Rank, Float>?,
        rankHit: Pair<Rank, Float>?,
        glyph: RankInkHeuristics.Guess?
    ): Pair<Rank, Float>? {
        if (bitmapHit != null &&
            glyph != null &&
            bitmapHit.first == Rank.King &&
            glyph.rank == Rank.Ten
        ) {
            return Rank.Ten to max(bitmapHit.second, glyph.confidence)
        }
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
    // Takes the already-computed per-rank score map (rankTemplateScoreMap)
    // instead of a crop, so the expensive crop-pyramid + mask-scoring pass
    // that produces those scores only happens once per recognize() call —
    // it used to run a second time here, independently, purely to find the
    // max, doubling the cost of every single card's rank recognition.
    private fun bestBitmapRank(rankScoreMap: Map<Rank, Float>): Pair<Rank, Float>? {
        if (rankScoreMap.isEmpty()) return null
        var best: Pair<Rank, Float>? = null
        var second = 0f
        var tenScore = 0f
        var queenScore = 0f
        var kingScore = 0f
        var jackScore = 0f
        var threeScore = 0f
        var twoScore = 0f
        var sevenScore = 0f
        rankScoreMap.forEach { (rank, score) ->
            when (rank) {
                Rank.Ten -> tenScore = score
                Rank.Queen -> queenScore = score
                Rank.King -> kingScore = score
                Rank.Jack -> jackScore = score
                Rank.Three -> threeScore = score
                Rank.Two -> twoScore = score
                Rank.Seven -> sevenScore = score
                else -> Unit
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
        // Applying this margin check regardless of the top score was tried
        // and reverted: a real device Evaluate run (v1.3.68/69) showed it
        // decline the bitmap-rank match on dozens of genuinely-correct,
        // high-confidence reads (0.84 vs 0.82, 0.85 vs 0.83, etc. - two
        // visually similar ranks landing close together is common even when
        // the top one is right), each falling through to weaker OCR/glyph
        // fallbacks that mostly missed - accuracy dropped from 97%
        // (1031/1068) to 91% (975/1068), with a new "FaceUp -> Unknown (37)"
        // bucket that hadn't existed before. The single case this was meant
        // to fix (a geometrically-reconstructed cascade card scoring Six
        // 0.83 vs Five 0.82) is a real but narrow case; the unconditional
        // margin check costs far more than it fixes. Reverted to only
        // applying below 0.68, where a close margin is a genuine sign of
        // ambiguity rather than two merely-similar high scores.
        if (top.second - second < 0.035f && top.second < 0.68f) return null
        // Clipped Smash Q matches the "0" in Ten. Prefer Queen unless Ten
        // wins by a clear two-glyph margin.
        if (top.first == Rank.Ten &&
            queenScore >= 0.50f &&
            tenScore - queenScore < 0.08f
        ) {
            return Rank.Queen to queenScore
        }
        // A Smash "10" has a tall 1 that matches K unless Ten wins clearly.
        if (top.first == Rank.King &&
            tenScore >= 0.50f &&
            kingScore - tenScore < 0.08f
        ) {
            return Rank.Ten to tenScore
        }
        if (top.first == Rank.Jack &&
            threeScore > jackScore &&
            threeScore >= 0.50f
        ) {
            return Rank.Three to threeScore
        }
        // Two and Seven ink masks can score identically at the coarse 48x48
        // grid, and unlike the confusions above there's no reliable glyph
        // bias to prefer one — silently falling back to enum order (Two)
        // makes this a coin flip dressed up as a decision. Decline instead so
        // the caller's ink-shape/OCR tiebreak gets a real say.
        if ((top.first == Rank.Two || top.first == Rank.Seven) &&
            kotlin.math.abs(twoScore - sevenScore) < 0.02f
        ) {
            return null
        }
        return top
    }

    private class InkComponent(var size: Int, var left: Int, var top: Int, var right: Int, var bottom: Int)

    /**
     * Crop to the union of "significant" connected ink blobs (with a small
     * margin) - components at least sizeThreshold of the largest one's pixel
     * count. inkMask always stretches whatever bitmap it's given to fill a
     * 48x48 grid, so two crops with different amounts of surrounding
     * whitespace end up warping the same glyph to different relative sizes.
     * Rank template PNGs are already snug around the digit (ink fills ~90%
     * of the frame), but rankSourceMasks' trimmedToVisibleStrip ROI is
     * deliberately wider than the digit (50% of card width, to keep the
     * suit pip out) - the digit there only fills ~35-40% of the frame
     * width. A real golden crop of "8" scored 0.50 against its own rank_eight
     * template that way (tied for last among all ranks); content-cropping
     * both sides to the same framing before the resample brought it to
     * 0.89, a clear win over every other rank.
     *
     * Two earlier attempts here were both wrong in opposite directions.
     * Bounding-box-of-all-ink regressed a real card: a single stray red
     * pixel bled in from the card above at the very top of the ROI, and the
     * box stretched to include it, reintroducing the "digit doesn't fill
     * the frame" problem this function exists to fix - just from
     * contamination instead of margin. Switching to the single largest
     * connected component fixed that, but broke "10" - Solitaire's only
     * two-character rank - because "1" and "0" are two separate components
     * with no touching pixels, and "1" alone has more ink than the (thinner,
     * ring-shaped) "0", so the single-largest rule silently dropped "0"
     * entirely, leaving just a bare "1" to match against. A size-relative
     * threshold keeps both real digit strokes (comparable sizes) while still
     * discarding a stray speck (a handful of pixels next to a whole digit).
     */
    private fun tightContentCrop(bitmap: Bitmap, margin: Int = 2, sizeThreshold: Float = 0.20f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ink = BooleanArray(width * height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            ink[i] = SmashColorAnalyzer.isRedInk(r, g, b) ||
                SmashColorAnalyzer.isBlackInk(r, g, b) ||
                SmashColorAnalyzer.isGenericDarkInk(r, g, b)
        }
        val visited = BooleanArray(width * height)
        val components = mutableListOf<InkComponent>()
        val queueX = IntArray(width * height)
        val queueY = IntArray(width * height)
        for (startY in 0 until height) {
            for (startX in 0 until width) {
                val startIndex = startY * width + startX
                if (!ink[startIndex] || visited[startIndex]) continue
                var head = 0
                var tail = 0
                queueX[tail] = startX
                queueY[tail] = startY
                tail++
                visited[startIndex] = true
                var size = 0
                var minX = startX
                var minY = startY
                var maxX = startX
                var maxY = startY
                while (head < tail) {
                    val cx = queueX[head]
                    val cy = queueY[head]
                    head++
                    size++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                            val nIndex = ny * width + nx
                            if (ink[nIndex] && !visited[nIndex]) {
                                visited[nIndex] = true
                                queueX[tail] = nx
                                queueY[tail] = ny
                                tail++
                            }
                        }
                    }
                }
                components += InkComponent(size, minX, minY, maxX, maxY)
            }
        }
        if (components.isEmpty()) return bitmap
        val maxSize = components.maxOf { it.size }
        val minKeptSize = maxSize * sizeThreshold
        var bestLeft = width
        var bestTop = height
        var bestRight = -1
        var bestBottom = -1
        components.forEach { component ->
            if (component.size >= minKeptSize) {
                if (component.left < bestLeft) bestLeft = component.left
                if (component.top < bestTop) bestTop = component.top
                if (component.right > bestRight) bestRight = component.right
                if (component.bottom > bestBottom) bestBottom = component.bottom
            }
        }
        val left = (bestLeft - margin).coerceIn(0, width - 1)
        val top = (bestTop - margin).coerceIn(0, height - 1)
        val right = (bestRight + margin + 1).coerceIn(left + 1, width)
        val bottom = (bestBottom + margin + 1).coerceIn(top + 1, height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
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
                    val isInk =
                        SmashColorAnalyzer.isRedInk(r, g, b) ||
                            SmashColorAnalyzer.isBlackInk(r, g, b) ||
                            SmashColorAnalyzer.isGenericDarkInk(r, g, b)
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
        return matchTemplate(crop, tmpl, badgeOnly = false) >= 0.72f
    }

    private fun bestRank(crop: Bitmap, trimmedToVisibleStrip: Boolean = false): Pair<Rank, Float>? {
        if (rankTemplates.isEmpty()) return null
        var best: Pair<Rank, Float>? = null
        var second = 0f
        for ((rank, template) in rankTemplates) {
            // matchTemplate's "center" ROI targets the large center rank
            // glyph on a full card. A tableau cascade card's crop is already
            // trimmed to just its own visible header strip, which has no
            // center glyph at all - only the small corner badge, which the
            // badgeOnly ROI below already covers. Scoring the (nonexistent)
            // center content there produced spurious matches; see the
            // resolveRankFromCrop comment on RankInkHeuristics for the
            // identical failure mode in a sibling heuristic.
            val center = if (trimmedToVisibleStrip) 0f else matchTemplate(crop, template, badgeOnly = false)
            val badge = if (trimmedToVisibleStrip) {
                listOf(0.70f, 0.90f).maxOf { fraction ->
                    matchTemplate(crop, template, badgeOnly = true, badgeHeightFraction = fraction)
                }
            } else {
                matchTemplate(crop, template, badgeOnly = true)
            }
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
        for ((suit, templates) in suitTemplates) {
            val score = templates.maxOf { template ->
                matchTemplate(crop, template, badgeOnly = true, suitPip = true)
            }
            if (best == null || score > best.second) best = suit to score
        }
        return best
    }

    private fun matchTemplate(
        crop: Bitmap,
        template: Mat,
        badgeOnly: Boolean,
        suitPip: Boolean = false,
        // matchTemplate's badgeOnly ROI (h=42% of crop) is calibrated for a
        // full ~193px card's corner badge. On an already-trimmed ~44-54px
        // cascade-card crop (bestRank's only trimmedToVisibleStrip caller),
        // 42% shrinks to ~18-23px - often too short for the rank digit. This
        // silently starved bestRank of any usable score for cascade cards
        // (confirmed via a fresh device log: rankScoreMap - a separate,
        // already-fixed ink-mask path - had real if noisy candidates, but
        // bestRank's own score stayed empty, so needsOcrTiebreak saw no
        // candidates at all and never even tried OCR). Same fractions
        // rankSourceMasks already validated against real golden pixels: a
        // tall glyph like "8" needs close to the full trimmed strip, "6"
        // needs noticeably less - callers try both and take the max.
        badgeHeightFraction: Float = 0.42f
    ): Float {
        val src = Mat()
        val gray = Mat()
        val resized = Mat()
        val result = Mat()
        var roi: Mat? = null
        var tmpl: Mat? = null
        return try {
            Utils.bitmapToMat(crop, src)
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            roi = if (suitPip) {
                val x = (gray.cols() * 0.56).toInt().coerceAtMost(gray.cols() - 8)
                val w = (gray.cols() * 0.36).toInt().coerceAtLeast(8)
                val h = (gray.rows() * 0.24).toInt().coerceAtLeast(8)
                Mat(
                    gray,
                    Rect(
                        x,
                        0,
                        w.coerceAtMost(gray.cols() - x),
                        h.coerceAtMost(gray.rows())
                    )
                )
            } else if (badgeOnly) {
                val w = (gray.cols() * 0.50).toInt().coerceAtLeast(8)
                val h = (gray.rows() * badgeHeightFraction).toInt().coerceAtLeast(8)
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
        private const val MAX_BITMAP_TEMPLATES_PER_RANK = 10
        private const val MAX_BITMAP_TEMPLATES_PER_SUIT = 8
        const val BLACK_SUIT_MARGIN = 0.045f
        /** Full-badge margin at or below this → defer to top-half tiebreaker. */
        const val BLACK_SUIT_TOP_TIEBREAK_MAX = 0.055f
        const val BLACK_SHAPE_MIN_MARGIN = 0.40f
        const val TOP_BLACK_FRACTION = 0.45f
        const val TOP_BLACK_SUIT_MARGIN = 0.04f
        /** Shape margin to override a thin spade template win on club badges. */
        const val TOP_BLACK_SHAPE_VETO_MARGIN = 0.28f
        /**
         * Shape margin required to flip a wide-margin topLeader==Clubs read to
         * Spades. Real device data splits cleanly: confirmed-Spades cases sit
         * at margin 1.08, confirmed-Clubs cases at 0.50 - this sits with
         * roughly equal buffer from both.
         */
        const val BLACK_TOP_CLUB_STRONG_SPADE_SHAPE_MARGIN = 0.80f
        /**
         * fullClub ceiling paired with the margin check above. Same real-data
         * split: confirmed-Spades cases have fullClub~0.81-0.83, confirmed-
         * Clubs cases ~0.92-0.93.
         */
        const val BLACK_TOP_CLUB_STRONG_SPADE_FULL_CLUB_MAX = 0.88f
        const val RED_SUIT_MARGIN = 0.040f
        const val RED_SHAPE_MIN_MARGIN = 0.12f
        /**
         * estimateConfidence's "clean short parse" tier (a real rank corner
         * like "K", "10", "A", or a single digit). Waste OCR stops trying
         * further regions once it hits this, instead of exhausting all of
         * them on every attempt.
         */
        const val WASTE_OCR_EARLY_EXIT_CONFIDENCE = 0.62f
    }
}
