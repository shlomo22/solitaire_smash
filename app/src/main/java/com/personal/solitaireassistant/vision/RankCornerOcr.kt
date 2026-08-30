package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.personal.solitaireassistant.game.Rank
import java.util.concurrent.TimeUnit

/**
 * Reads the small top-left corner rank on Solitaire Smash cards via ML Kit OCR.
 * Used only as a tiebreaker when template/glyph fusion is ambiguous.
 */
class RankCornerOcr {
    data class Guess(
        val rank: Rank,
        val confidence: Float,
        val rawText: String
    )

    data class AttemptResult(
        val guess: Guess?,
        val trace: String
    )

    enum class CornerRoiProfile {
        /** Standard card crops (tableau, foundation). */
        DEFAULT,
        /** Fanned waste cards: wider corner window for clipped rank glyphs. */
        WASTE,
        /** Region is already a rank-corner patch; skip inner ROI crop. */
        DIRECT,
        /**
         * Caller already trimmed the crop down to a tableau cascade card's
         * own visible header strip (well under half a full card's height).
         * DEFAULT's 25% height fraction assumes a full ~193px card, which on
         * an already-trimmed ~44-54px crop shrinks to ~11px - too short for
         * ML Kit to read anything, which is why this profile's absence made
         * the real recognition path silently OCR-miss on crops the
         * diagnostic probe (run on full, untrimmed truth bounds) read fine.
         */
        TRIMMED
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Tableau columns now recognize concurrently (GameStateDetector.detect's
    // computeColumn), and this single ML Kit client is shared across all of
    // them as a rare rank-tiebreak fallback. ML Kit's client isn't documented
    // as safe for concurrent process() calls from multiple threads, so
    // serialize access here rather than risk it - OCR is already the rare,
    // non-hot path (only reached when template/glyph matching disagrees), so
    // this costs nothing in the common case where no column needs it.
    @Synchronized
    fun attempt(
        crop: Bitmap,
        profile: CornerRoiProfile = CornerRoiProfile.DEFAULT
    ): AttemptResult {
        val preprocessed = when (profile) {
            CornerRoiProfile.DIRECT -> preprocess(crop)
            else -> {
                val roi = cornerRankRoi(crop, profile)
                    ?: return AttemptResult(null, "ocr=miss:no-roi")
                val processed = preprocess(roi)
                roi.recycle()
                processed
            }
        }
        return try {
            val image = InputImage.fromBitmap(preprocessed, 0)
            val result = Tasks.await(
                recognizer.process(image),
                OCR_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
            val rawText = result.text.trim()
            if (rawText.isEmpty()) {
                return AttemptResult(null, "ocr=miss:empty")
            }
            val rank = parseRank(rawText)
                ?: return AttemptResult(null, "ocr=miss:unparsed='${sanitizeTrace(rawText)}'")
            val guess = Guess(rank, estimateConfidence(rawText), rawText)
            AttemptResult(guess, formatHitTrace(guess))
        } catch (t: Throwable) {
            Log.w(TAG, "OCR failed", t)
            AttemptResult(null, "ocr=miss:${t.javaClass.simpleName}")
        } finally {
            preprocessed.recycle()
        }
    }

    fun guess(crop: Bitmap): Guess? = attempt(crop).guess

    fun close() {
        recognizer.close()
    }

    companion object {
        private const val TAG = "RankCornerOcr"
        private const val OCR_TIMEOUT_MS = 500L
        private const val DEFAULT_CONFIDENCE = 0.55f

        fun cornerRankRoi(
            cardCrop: Bitmap,
            profile: CornerRoiProfile = CornerRoiProfile.DEFAULT
        ): Bitmap? {
            val w = cardCrop.width
            val h = cardCrop.height
            if (w < 12 || h < 12) return null
            val (widthFraction, heightFraction) = when (profile) {
                CornerRoiProfile.DEFAULT -> 0.35f to 0.25f
                // Taller than DEFAULT: Smash "8" is two stacked loops and the
                // old 0.32 height clipped the lower loop on waste (Evaluate
                // 132126/132140/155538: every OCR region miss, tight Four).
                // OCR Eight already overrides Four in ocrRankOverride once it
                // reads — safer than inventing Eight from ink (v1.4.90 −30).
                CornerRoiProfile.WASTE -> 0.48f to 0.42f
                // Width fraction validated for rankSourceMasks' trimmedToVisibleStrip
                // branch against real golden pixels: digit ink always ends by ~30%
                // of card width with the pip not starting before ~71%. Height uses
                // the full available crop (>=1.0 always clamps to h via coerceIn
                // below) rather than rankSourceMasks' own 0.90: a side-by-side pixel
                // crop of a real "10" showed 0.90 clipping the bottom of the digit,
                // which template matching tolerates but ML Kit's text detector does
                // not - the caller's effectiveRankCrop already keeps a safety margin
                // before the covering card (inkRegion = faceUpStep*0.9), so there is
                // no need for OCR's own ROI to shrink further inside that.
                CornerRoiProfile.TRIMMED -> 0.50f to 1.0f
                CornerRoiProfile.DIRECT -> return null
            }
            // Waste crops start flush at the card's own top edge, which puts a
            // ~2-3%-of-height drop-shadow/border-transition band (a desaturated
            // dark purple, e.g. RGB(50,36,98)) right inside row 0 - it passes
            // SmashColorAnalyzer.isBlackInk and preprocess() paints it as a solid
            // black bar across the top of the binarized crop, sitting directly
            // above the rank glyph (which never starts before ~6.7% of card
            // height in pixel-checked golden samples: 20260822_230705,
            // 20260825_132126/132140, 20260824_080754). Skip a small top margin
            // for WASTE so that spurious bar isn't fed to ML Kit.
            val topInsetFraction = if (profile == CornerRoiProfile.WASTE) 0.04f else 0f
            val roiTop = (h * topInsetFraction).toInt().coerceIn(0, h - 1)
            val roiW = (w * widthFraction).toInt().coerceIn(8, w)
            val roiH = (h * heightFraction).toInt().coerceIn(8, h - roiTop)
            return Bitmap.createBitmap(cardCrop, 0, roiTop, roiW, roiH)
        }

        fun upscale(source: Bitmap, scale: Int = 3): Bitmap {
            val w = source.width * scale
            val h = source.height * scale
            if (w <= 0 || h <= 0) return source
            return Bitmap.createScaledBitmap(source, w, h, true)
        }

        fun preprocess(source: Bitmap): Bitmap {
            val scaled = upscale(source)
            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val color = pixels[i]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val isInk = SmashColorAnalyzer.isRedInk(r, g, b) ||
                    SmashColorAnalyzer.isBlackInk(r, g, b)
                pixels[i] = if (isInk) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
            scaled.setPixels(pixels, 0, w, 0, 0, w, h)
            return scaled
        }

        fun formatHitTrace(guess: Guess): String =
            "ocr='${sanitizeTrace(guess.rawText)}'@${"%.2f".format(guess.confidence)}"

        fun sanitizeTrace(rawText: String): String =
            rawText.replace("'", "").take(24)

        /** Bundled ML Kit 16.x does not expose OCR confidence scores. */
        fun estimateConfidence(rawText: String): Float {
            val trimmed = rawText.trim()
            val compact = trimmed.uppercase().replace(Regex("\\s+"), "")
            return when {
                compact == trimmed.uppercase() && compact.length <= 2 -> 0.62f
                compact.length <= 3 -> 0.58f
                else -> DEFAULT_CONFIDENCE
            }.coerceIn(DEFAULT_CONFIDENCE, 0.72f)
        }

        fun parseRank(text: String): Rank? {
            val compact = text
                .trim()
                .uppercase()
                .replace(Regex("\\s+"), "")
            if (compact.isEmpty()) return null

            val normalized = compact
                .replace('O', '0')
                .replace('I', '1')
                .replace('L', '1')

            when {
                normalized.contains("10") -> return Rank.Ten
                normalized == "1O" || normalized == "IO" -> return Rank.Ten
                normalized == "A" -> return Rank.Ace
                normalized == "J" -> return Rank.Jack
                normalized == "Q" -> return Rank.Queen
                normalized == "K" -> return Rank.King
            }

            if (normalized.length == 1) {
                val digit = normalized[0]
                if (digit in '2'..'9') {
                    return Rank.entries.firstOrNull { it.value == digit.digitToInt() }
                }
            }

            // Reject multi-token garbage like "8H" or long strings.
            if (compact.length > 3) return null
            return null
        }
    }
}
