package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Suit
import kotlin.math.abs

enum class BadgeRoiSource {
    Located,
    FallbackFixed
}

data class RedSuitSlotReport(
    val sampleId: String,
    val pile: String,
    val truthSuit: Suit?,
    val detectedSuit: Suit?,
    val heartScore: Float,
    val diamondScore: Float,
    val margin: Float,
    val badgeRoiSource: BadgeRoiSource,
    val shapeSuit: Suit?,
    val shapeMargin: Float,
    val suitSource: String?,
    val postSteps: List<String>,
    val suitAmbiguous: Boolean
) {
    val thinMargin: Boolean get() = margin < CardRecognizer.RED_SUIT_MARGIN
    val mismatch: Boolean get() = truthSuit != null && detectedSuit != null && truthSuit != detectedSuit

    fun formatLine(): String = buildString {
        append(sampleId)
        append(' ')
        append(pile)
        append(" truth=")
        append(truthSuit?.name ?: "?")
        append(" detected=")
        append(detectedSuit?.name ?: "?")
        append(" H=")
        append("%.3f".format(heartScore))
        append(" D=")
        append("%.3f".format(diamondScore))
        append(" margin=")
        append("%.3f".format(margin))
        append(" roi=")
        append(badgeRoiSource.name)
        if (shapeSuit != null) {
            append(" shape=")
            append(shapeSuit.name)
            append('@')
            append("%.2f".format(shapeMargin))
        }
        append(" src=")
        append(suitSource ?: "?")
        if (suitAmbiguous) append(" ambiguous")
        if (mismatch) append(" MISMATCH")
    }
}

object RedSuitDiagnostic {
    fun badgeRoiSource(bitmap: Bitmap, region: BoardRegion): BadgeRoiSource {
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        if (right - left < 8 || bottom - top < 8) return BadgeRoiSource.FallbackFixed
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return try {
            if (SuitBadgeHeuristics.locateBadge(crop) != null) {
                BadgeRoiSource.Located
            } else {
                BadgeRoiSource.FallbackFixed
            }
        } finally {
            crop.recycle()
        }
    }

    fun analyzeSlot(
        sampleId: String,
        pile: String,
        bitmap: Bitmap,
        region: BoardRegion,
        recognizer: CardRecognizer,
        truthSuit: Suit?,
        detected: RecognizedSlot?
    ): RedSuitSlotReport {
        val scores = recognizer.suitTemplateScores(
            bitmap,
            region,
            setOf(Suit.Hearts, Suit.Diamonds)
        )
        val heartScore = scores[Suit.Hearts] ?: 0f
        val diamondScore = scores[Suit.Diamonds] ?: 0f
        val margin = abs(heartScore - diamondScore)
        val roiSource = badgeRoiSource(bitmap, region)
        val shape = run {
            val crop = createCardCrop(bitmap, region) ?: return@run null
            try {
                SuitBadgeHeuristics.guessRedSuit(crop)
            } finally {
                crop.recycle()
            }
        }
        val trace = detected?.trace
        return RedSuitSlotReport(
            sampleId = sampleId,
            pile = pile,
            truthSuit = truthSuit,
            detectedSuit = detected?.engine?.suit,
            heartScore = heartScore,
            diamondScore = diamondScore,
            margin = margin,
            badgeRoiSource = roiSource,
            shapeSuit = shape?.suit,
            shapeMargin = shape?.margin ?: 0f,
            suitSource = trace?.suitSource,
            postSteps = trace?.postSteps.orEmpty(),
            suitAmbiguous = detected?.engine?.suitAmbiguous == true
        )
    }

    fun summarize(reports: List<RedSuitSlotReport>): String {
        if (reports.isEmpty()) return "No red-suit slots analyzed."
        val mismatches = reports.filter { it.mismatch }
        val thin = reports.filter { it.thinMargin }
        val fallback = reports.filter { it.badgeRoiSource == BadgeRoiSource.FallbackFixed }
        val lines = mutableListOf(
            "Red-suit diagnostic: ${reports.size} slots",
            "  mismatches: ${mismatches.size}",
            "  thin margin (<${CardRecognizer.RED_SUIT_MARGIN}): ${thin.size}",
            "  fallback ROI: ${fallback.size}"
        )
        mismatches.take(12).forEach { lines += "  ${it.formatLine()}" }
        if (mismatches.size > 12) lines += "  ... +${mismatches.size - 12} more"
        return lines.joinToString("\n")
    }

    private fun createCardCrop(bitmap: Bitmap, region: BoardRegion): Bitmap? {
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        if (right - left < 8 || bottom - top < 8) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }
}

