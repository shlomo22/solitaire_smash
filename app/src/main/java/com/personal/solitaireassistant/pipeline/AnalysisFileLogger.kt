package com.personal.solitaireassistant.pipeline

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Appends analysis traces to app-private storage so we can tell recognition
 * failures apart from arrow/move logic without adb.
 *
 * Path: files/logs/analysis.log
 * Pull with: adb exec-out run-as com.personal.solitaireassistant cat files/logs/analysis.log
 * Or share via device file manager / Android Studio Device Explorer.
 *
 * On ARROW / NO_MOVE outcomes the log includes a `recognition:` block per slot:
 * rank source (rank-png, rank-glyph, …), suit source (suit-png, suit-shape-red, …),
 * top PNG template scores, and post-passes (resolve-red-suit, deck-constraint, …).
 */
class AnalysisFileLogger(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val logFile: File by lazy {
        File(appContext.filesDir, "logs").also { it.mkdirs() }.let { File(it, LOG_NAME) }
    }

    fun pathForDisplay(): String = logFile.absolutePath

    fun clear() {
        executor.execute {
            runCatching {
                if (logFile.exists()) logFile.writeText("")
                appendSync("=== log cleared ${Date()} ===")
            }
        }
    }

    fun sessionStart(frameSize: String? = null) {
        append(
            "=== session start ${Date()}" +
                (frameSize?.let { " frame=$it" } ?: "") +
                " path=${logFile.absolutePath} ==="
        )
    }

    fun append(message: String) {
        executor.execute {
            runCatching { appendSync(message) }
                .onFailure { Log.w(TAG, "Failed writing analysis log", it) }
        }
    }

    private fun appendSync(message: String) {
        rotateIfNeeded()
        val line = "${timeFmt.format(Date())} $message\n"
        logFile.appendText(line)
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_BYTES) return
        val bak = File(logFile.parentFile, "$LOG_NAME.bak")
        if (bak.exists()) bak.delete()
        logFile.renameTo(bak)
    }

    companion object {
        private const val TAG = "AnalysisFileLogger"
        private const val LOG_NAME = "analysis.log"
        private const val MAX_BYTES = 2L * 1024L * 1024L
    }
}
