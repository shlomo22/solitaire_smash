package com.personal.solitaireassistant.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class ReviewSampleStore(context: Context) {
    private val rootDir = File(context.filesDir, "review_samples").also { it.mkdirs() }
    private val counter = AtomicInteger(nextCounterStart())

    fun pathForDisplay(): String = rootDir.absolutePath

    /**
     * Saves [frame] + [recognizedText] under a timestamped folder.
     * @return sample index (1-based display) or null on failure
     */
    fun save(frame: Bitmap, recognizedText: String): Int? {
        return try {
            val index = counter.incrementAndGet()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dirName = String.format(Locale.US, "%s_%03d", stamp, index)
            val dir = File(rootDir, dirName).also { it.mkdirs() }
            val png = File(dir, "frame.png")
            FileOutputStream(png).use { out ->
                frame.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            File(dir, "recognized.txt").writeText(recognizedText)
            File(rootDir, "index.txt").appendText("$dirName\n")
            index
        } catch (t: Throwable) {
            Log.w(TAG, "Failed saving review sample", t)
            null
        }
    }

    private fun nextCounterStart(): Int {
        val existing = rootDir.listFiles()?.count { it.isDirectory } ?: 0
        return existing
    }

    companion object {
        private const val TAG = "ReviewSampleStore"
    }
}
