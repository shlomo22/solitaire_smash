package com.personal.solitaireassistant.vision

import android.graphics.Bitmap
import com.personal.solitaireassistant.game.Suit
import java.io.File
import java.io.FileOutputStream

/**
 * Extracts suit badge crops from golden truth slots using the same [SuitBadgeHeuristics.locateBadge]
 * ROI the recognizer uses. Run via [GoldenBadgeTemplateExtractorTest] to refresh assets.
 */
object GoldenBadgeTemplateExtractor {
    data class ExtractedBadge(
        val suit: Suit,
        val badge: Bitmap,
        val sampleId: String,
        val pile: String
    )

    fun extractRedBadges(sample: GoldenSample, frame: Bitmap): List<ExtractedBadge> =
        extractBadges(sample, frame) { it.isRed }

    fun extractBlackBadges(sample: GoldenSample, frame: Bitmap): List<ExtractedBadge> =
        extractBadges(sample, frame) { !it.isRed }

    private fun extractBadges(
        sample: GoldenSample,
        frame: Bitmap,
        suitFilter: (Suit) -> Boolean
    ): List<ExtractedBadge> {
        val out = mutableListOf<ExtractedBadge>()
        sample.slots.forEach { slot ->
            if (slot.inferred) return@forEach
            if (slot.truth.kind != SlotKind.FaceUp) return@forEach
            val suit = slot.truth.suit ?: return@forEach
            if (!suitFilter(suit)) return@forEach
            val badge = extractBadge(frame, slot.bounds) ?: return@forEach
            out += ExtractedBadge(suit, badge, sample.id, slot.pile)
        }
        return out
    }

    fun extractBadge(frame: Bitmap, bounds: com.personal.solitaireassistant.game.BoardRegion): Bitmap? {
        val left = bounds.left.toInt().coerceIn(0, frame.width - 1)
        val top = bounds.top.toInt().coerceIn(0, frame.height - 1)
        val right = bounds.right.toInt().coerceIn(left + 1, frame.width)
        val bottom = bounds.bottom.toInt().coerceIn(top + 1, frame.height)
        if (right - left < 8 || bottom - top < 8) return null
        val crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        val located = try {
            SuitBadgeHeuristics.locateBadge(crop)
        } finally {
            crop.recycle()
        }
        return located?.let { found ->
            try {
                found.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                if (!found.bitmap.isRecycled) found.bitmap.recycle()
            }
        }
    }

    fun writeRedSuitTemplates(templatesDir: File, badges: List<ExtractedBadge>): List<File> =
        writeSuitTemplates(templatesDir, badges)

    fun writeBlackSuitTemplates(templatesDir: File, badges: List<ExtractedBadge>): List<File> =
        writeSuitTemplates(templatesDir, badges.filter { it.suit == Suit.Clubs })

    fun writeSpadeSuitTemplates(templatesDir: File, badges: List<ExtractedBadge>): List<File> =
        writeSuitTemplates(templatesDir, badges.filter { it.suit == Suit.Spades })

    /**
     * Writes up to three badge PNGs per suit from distinct golden samples.
     * Prioritizes foundation badges (common C↔S / H↔D failure mode).
     */
    fun writeSuitTemplates(templatesDir: File, badges: List<ExtractedBadge>): List<File> {
        require(templatesDir.isDirectory) { "Not a directory: $templatesDir" }
        val written = mutableListOf<File>()
        badges.groupBy { it.suit }.forEach { (suit, group) ->
            val prefix = "suit_${suit.name.lowercase()}"
            val existing = templatesDir.listFiles { _, name ->
                name == "$prefix.png" ||
                    (name.startsWith("${prefix}_alt") && name.endsWith(".png"))
            }.orEmpty().map { it.name }.toSet()
            val existingNames = existing.toMutableSet()
            var altNum = (1..99).firstOrNull { alt ->
                "${prefix}_alt$alt.png" !in existingNames
            } ?: return@forEach
            val picks = group
                .sortedWith(
                    compareByDescending<ExtractedBadge> {
                        it.suit == Suit.Clubs && it.sampleId == "20260814_205456"
                    }.thenByDescending {
                        it.suit == Suit.Spades && it.sampleId == "20260814_234016"
                    }.thenByDescending {
                        it.pile.startsWith("foundation") && it.sampleId.isNotBlank()
                    }.thenByDescending { it.badge.width * it.badge.height }
                )
                .distinctBy { "${it.sampleId}:${it.pile}" }
                .filter {
                    val area = it.badge.width * it.badge.height
                    area in 900..2_000 &&
                        it.badge.width <= 44 &&
                        it.badge.height <= 46
                }
                .take(3)
            picks.forEach { extracted ->
                while (altNum <= 99 && "${prefix}_alt$altNum.png" in existingNames) altNum++
                if (altNum > 99) return@forEach
                val outFile = File(templatesDir, "${prefix}_alt$altNum.png")
                FileOutputStream(outFile).use { stream ->
                    extracted.badge.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                written += outFile
                existingNames += outFile.name
                altNum++
            }
            group.forEach { it.badge.recycle() }
        }
        return written
    }
}
