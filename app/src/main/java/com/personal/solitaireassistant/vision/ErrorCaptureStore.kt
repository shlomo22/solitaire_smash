package com.personal.solitaireassistant.vision

import android.content.Context
import android.graphics.Bitmap
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
