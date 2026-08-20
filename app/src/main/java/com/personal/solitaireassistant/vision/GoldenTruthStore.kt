package com.personal.solitaireassistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GoldenTruthStore(context: Context) {
    private val filesDir = context.applicationContext.filesDir

    fun dir(): File = File(filesDir, "golden").also { it.mkdirs() }

    fun pathForDisplay(): String = dir().absolutePath

    fun count(): Int = listIds().size

    fun listIds(): List<String> =
        dir().listFiles()
            .orEmpty()
            .filter { it.extension.equals("json", ignoreCase = true) }
            .map { it.nameWithoutExtension }
            .sorted()

    fun loadSample(id: String): GoldenSample? {
        val file = File(dir(), "$id.json")
        if (!file.isFile) return null
        return runCatching { GoldenTruthJson.fromJson(file.readText()) }.getOrNull()
    }

    /**
     * Ids whose json is present but unparseable, so [listSamples] silently
     * drops them. A hand-edited golden file with a bad enum spelling
     * ("king" for "King") sat in the set unnoticed for a whole tuning
     * session: count() counts json files while listSamples() counts the ones
     * that actually parsed, so the mismatch only ever showed up as
     * "Saved samples: 35" next to "Golden set: 34 samples" - two numbers far
     * enough apart in the report to read as unrelated. Surface it instead.
     */
    fun listUnreadableIds(): List<String> =
        listIds().filter { loadSample(it) == null }

    fun loadBitmap(id: String): Bitmap? {
        val file = File(dir(), "$id.png")
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun listSamples(): List<GoldenSample> =
        listIds().mapNotNull { loadSample(it) }

    fun nextId(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun save(bitmap: Bitmap, sample: GoldenSample) {
        val directory = dir()
        val png = File(directory, "${sample.id}.png")
        FileOutputStream(png).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
        File(directory, "${sample.id}.json").writeText(GoldenTruthJson.toJson(sample))
    }
}
