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
