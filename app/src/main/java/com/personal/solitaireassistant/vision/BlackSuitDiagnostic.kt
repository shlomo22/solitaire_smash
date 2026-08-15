package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Suit

data class BlackSuitSlotReport(
    val sampleId: String,
    val pile: String,
    val truthSuit: Suit?,
    val detectedSuit: Suit?,
    val clubScore: Float,
    val spadeScore: Float,
    val margin: Float,
    val topClubScore: Float,
    val topSpadeScore: Float,
    val topMargin: Float,
    val badgeRoiSource: BadgeRoiSource,
    val shapeSuit: Suit?,
    val shapeMargin: Float,
    val suitSource: String?,
    val postSteps: List<String>,
    val suitAmbiguous: Boolean
) {
    val thinMargin: Boolean get() = margin < CardRecognizer.BLACK_SUIT_MARGIN
    val mismatch: Boolean get() = truthSuit != null && detectedSuit != null && truthSuit != detectedSuit

    fun formatLine(): String = buildString {
        append(sampleId)
        append(' ')
        append(pile)
        append(" truth=")
        append(truthSuit?.name ?: "?")
        append(" detected=")
        append(detectedSuit?.name ?: "?")
        append(" C=")
        append("%.3f".format(clubScore))
        append(" S=")
        append("%.3f".format(spadeScore))
        append(" margin=")
        append("%.3f".format(margin))
        append(" topC=")
        append("%.3f".format(topClubScore))
        append(" topS=")
        append("%.3f".format(topSpadeScore))
        append(" topMargin=")
        append("%.3f".format(topMargin))
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

object BlackSuitDiagnostic {
    fun analyzeSlot(
        sampleId: String,
        pile: String,
        bitmap: Bitmap,
        region: BoardRegion,
        recognizer: CardRecognizer,
        truthSuit: Suit?,
        detected: RecognizedSlot?
    ): BlackSuitSlotReport {
        val scores = recognizer.blackSuitTemplateScores(bitmap, region)
        val clubScore = scores.fullClub
        val spadeScore = scores.fullSpade
        val margin = scores.fullMargin
        val topClubScore = scores.topClub
        val topSpadeScore = scores.topSpade
        val topMargin = scores.topMargin
        val roiSource = RedSuitDiagnostic.badgeRoiSource(bitmap, region)
        val shape = run {
            val crop = createCardCrop(bitmap, region) ?: return@run null
            try {
                SuitBadgeHeuristics.guessBlackSuit(crop)
            } finally {
                crop.recycle()
            }
        }
        val trace = detected?.trace
        return BlackSuitSlotReport(
            sampleId = sampleId,
            pile = pile,
            truthSuit = truthSuit,
            detectedSuit = detected?.engine?.suit,
            clubScore = clubScore,
            spadeScore = spadeScore,
            margin = margin,
            topClubScore = topClubScore,
            topSpadeScore = topSpadeScore,
            topMargin = topMargin,
            badgeRoiSource = roiSource,
            shapeSuit = shape?.suit,
            shapeMargin = shape?.margin ?: 0f,
            suitSource = trace?.suitSource,
            postSteps = trace?.postSteps.orEmpty(),
            suitAmbiguous = detected?.engine?.suitAmbiguous == true
        )
    }

    fun summarize(reports: List<BlackSuitSlotReport>): String {
        if (reports.isEmpty()) return "No black-suit slots analyzed."
        val mismatches = reports.filter { it.mismatch }
        val thin = reports.filter { it.thinMargin }
        val fallback = reports.filter { it.badgeRoiSource == BadgeRoiSource.FallbackFixed }
        val lines = mutableListOf(
            "Black-suit diagnostic: ${reports.size} slots",
            "  mismatches: ${mismatches.size}",
            "  thin margin (<${CardRecognizer.BLACK_SUIT_MARGIN}): ${thin.size}",
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
