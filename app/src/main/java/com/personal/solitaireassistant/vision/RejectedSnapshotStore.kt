package com.personal.solitaireassistant.vision

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto-saved cancel/rejection debug bundles for bad-arrow investigation.
 *
 * Path: files/rejected/
 * Pull with:
 * adb exec-out run-as com.personal.solitaireassistant sh -c 'cd files/rejected && tar cf - .' > rejected.tar
 */
class RejectedSnapshotStore(context: Context) {
    private val filesDir = context.applicationContext.filesDir

    fun dir(): File = File(filesDir, "rejected").also { it.mkdirs() }

    fun nextId(): String =
        "reject_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"

    fun save(bitmap: Bitmap, sample: GoldenSample, meta: RejectionMeta) {
        val directory = dir()
        val png = File(directory, "${sample.id}.png")
        FileOutputStream(png).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
        }
        File(directory, "${sample.id}.json").writeText(GoldenTruthJson.toJson(sample, meta))
    }
}
