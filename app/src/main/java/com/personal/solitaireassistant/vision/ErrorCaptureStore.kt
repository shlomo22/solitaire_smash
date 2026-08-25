package com.personal.solitaireassistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto-saved recognition-error debug bundles for misread investigation.
 *
 * Path: files/recognition_errors/
 * Pull with:
 * adb exec-out run-as com.personal.solitaireassistant sh -c 'cd files/recognition_errors && tar cf - .' > errors.tar
 */
class ErrorCaptureStore(context: Context) {
    private val filesDir = context.applicationContext.filesDir

    fun dir(): File = File(filesDir, "recognition_errors").also { it.mkdirs() }

    fun pathForDisplay(): String = dir().absolutePath

    fun count(): Int = listIds().size

    fun listIds(): List<String> =
        dir().listFiles()
            .orEmpty()
            .filter { it.extension.equals("json", ignoreCase = true) }
            .map { it.nameWithoutExtension }
            .sorted()

    fun listIdsNewestFirst(): List<String> = listIds().asReversed()

    /** Oldest-first, suitable for comparing consecutive captures from the same session. */
    fun listIdsOldestFirst(): List<String> = listIds()

    data class CompactResult(
        val removed: Int,
        val remaining: Int
    )

    /**
     * Walks [listIdsOldestFirst] and deletes the newer capture whenever it has
     * the same violation set as the capture immediately before it.
     */
    fun compactConsecutiveDuplicates(): CompactResult {
        val ids = listIdsOldestFirst().toMutableList()
        var removed = 0
        var index = 0
        while (index < ids.size - 1) {
            val olderKey = violationKey(ids[index])
            val newerKey = violationKey(ids[index + 1])
            if (olderKey != null && olderKey == newerKey) {
                delete(ids[index + 1])
                ids.removeAt(index + 1)
                removed++
            } else {
                index++
            }
        }
        return CompactResult(removed = removed, remaining = ids.size)
    }

    private fun violationKey(id: String): String? {
        val text = loadJsonText(id) ?: return null
        val meta = GoldenTruthJson.parseErrorCaptureMeta(text) ?: return null
        return meta.violations
            .map { it.summary() }
            .sorted()
            .joinToString("|")
    }

    fun loadJsonText(id: String): String? {
        val file = File(dir(), "$id.json")
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun loadSample(id: String): GoldenSample? {
        val text = loadJsonText(id) ?: return null
        return runCatching { GoldenTruthJson.fromJson(text) }.getOrNull()
    }

    fun loadBitmap(id: String): Bitmap? {
        val file = File(dir(), "$id.png")
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun nextId(): String =
        "error_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"

    fun save(bitmap: Bitmap, sample: GoldenSample, meta: ErrorCaptureMeta) {
        val directory = dir()
        val png = File(directory, "${sample.id}.png")
        FileOutputStream(png).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
        File(directory, "${sample.id}.json").writeText(
            GoldenTruthJson.toJson(sample, errorCapture = meta)
        )
    }

    /** Deletes one saved PNG+JSON pair. Returns true when both files were removed. */
    fun delete(id: String): Boolean {
        val directory = dir()
        val pngDeleted = File(directory, "$id.png").delete()
        val jsonDeleted = File(directory, "$id.json").delete()
        return pngDeleted || jsonDeleted
    }

    /** Deletes every saved PNG+JSON pair in [dir]. Returns how many ids were removed. */
    fun clearAll(): Int {
        val ids = listIds()
        val directory = dir()
        ids.forEach { id ->
            File(directory, "$id.png").delete()
            File(directory, "$id.json").delete()
        }
        return ids.size
    }
}
