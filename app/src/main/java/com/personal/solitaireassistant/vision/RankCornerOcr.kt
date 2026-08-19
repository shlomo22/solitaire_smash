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
        DIRECT
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
                CornerRoiProfile.WASTE -> 0.45f to 0.32f
                CornerRoiProfile.DIRECT -> return null
            }
            val roiW = (w * widthFraction).toInt().coerceIn(8, w)
            val roiH = (h * heightFraction).toInt().coerceIn(8, h)
            return Bitmap.createBitmap(cardCrop, 0, 0, roiW, roiH)
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
