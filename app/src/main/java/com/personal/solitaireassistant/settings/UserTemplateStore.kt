package com.personal.solitaireassistant.settings

import android.content.Context
import android.graphics.Bitmap
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UserTemplateStore(context: Context) {
    private val rootDir = File(context.filesDir, "templates").also { it.mkdirs() }

    val rootPath: String get() = rootDir.absolutePath

    fun rankCornerDir(rank: Rank): File =
        File(rootDir, "rank_corner_${rank.name.lowercase()}").also { it.mkdirs() }

    fun suitBadgeDir(suit: Suit): File =
        File(rootDir, "suit_badge_${suit.name.lowercase()}").also { it.mkdirs() }

    fun rankGlyphDir(rank: Rank): File =
        File(rootDir, "rank_glyph_${rank.name.lowercase()}").also { it.mkdirs() }

    fun saveRankCorner(rank: Rank, bitmap: Bitmap): File {
        val dir = rankCornerDir(rank)
        evictIfNeeded(dir, MAX_RANK_CORNER_PER_RANK)
        val file = File(dir, "${System.currentTimeMillis()}.png")
        writePng(bitmap, file)
        return file
    }

    fun saveSuitBadge(suit: Suit, bitmap: Bitmap): File {
        val dir = suitBadgeDir(suit)
        evictIfNeeded(dir, MAX_SUIT_BADGE_PER_SUIT)
        val file = File(dir, "${System.currentTimeMillis()}.png")
        writePng(bitmap, file)
        return file
    }

    fun saveRankGlyph(rank: Rank, bitmap: Bitmap): File? {
        if (rank !in GLYPH_RANKS) return null
        val dir = rankGlyphDir(rank)
        evictIfNeeded(dir, MAX_RANK_GLYPH_PER_RANK)
        val file = File(dir, "${System.currentTimeMillis()}.png")
        writePng(bitmap, file)
        return file
    }

    fun listRankCornerFiles(rank: Rank): List<File> =
        rankCornerDir(rank).listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun listSuitBadgeFiles(suit: Suit): List<File> =
        suitBadgeDir(suit).listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun listRankGlyphFiles(rank: Rank): List<File> =
        rankGlyphDir(rank).listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun coverageCounts(): TemplateCoverage {
        val rankCorner = Rank.entries.associateWith { listRankCornerFiles(it).size }
        val suitBadge = Suit.entries.associateWith { listSuitBadgeFiles(it).size }
        val rankGlyph = GLYPH_RANKS.associateWith { listRankGlyphFiles(it).size }
        return TemplateCoverage(rankCorner, suitBadge, rankGlyph)
    }

    fun exportZip(destination: File): Boolean {
        return try {
            ZipOutputStream(FileOutputStream(destination)).use { zip ->
                rootDir.walkTopDown()
                    .filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
                    .forEach { file ->
                        val entryName = file.relativeTo(rootDir).path.replace('\\', '/')
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun evictIfNeeded(dir: File, maxFiles: Int) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        if (files.size < maxFiles) return
        files.take(files.size - maxFiles + 1).forEach { it.delete() }
    }

    private fun writePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    data class TemplateCoverage(
        val rankCorner: Map<Rank, Int>,
        val suitBadge: Map<Suit, Int>,
        val rankGlyph: Map<Rank, Int>
    )

    companion object {
        const val MAX_RANK_CORNER_PER_RANK = 8
        const val MAX_SUIT_BADGE_PER_SUIT = 5
        const val MAX_RANK_GLYPH_PER_RANK = 5
        val GLYPH_RANKS = setOf(Rank.Jack, Rank.Queen, Rank.King)
    }
}
