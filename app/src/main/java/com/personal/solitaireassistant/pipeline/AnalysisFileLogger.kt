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

    /**
     * Rotated generations, oldest last. Read these alongside [logFile] to
     * recover a whole game: at the observed ~1.1MB/min of active play, one
     * generation only covers a slice of it.
     */
    fun rotatedFiles(): List<File> =
        (1..ROTATION_KEEP).map { File(logFile.parentFile, "$LOG_NAME.$it") }
            .filter { it.exists() }

    fun clear() {
        executor.execute {
            runCatching {
                if (logFile.exists()) logFile.writeText("")
                // Rotated generations go too. Leaving them behind would let a
                // "cleared" log still serve a previous game's frames to
                // anything that stitches the generations back together.
                rotatedFiles().forEach { it.delete() }
                File(logFile.parentFile, "$LOG_NAME.bak").delete()
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

    private fun rotateIfNeeded() =
        LogRotation.rotateIfNeeded(logFile, MAX_BYTES, ROTATION_KEEP)

    companion object {
        private const val TAG = "AnalysisFileLogger"
        internal const val LOG_NAME = "analysis.log"

        /**
         * 16MB per generation, 3 kept, so 64MB worst case in app-private
         * storage and roughly an hour of active play recoverable - a debug-only
         * build tracing its own recognition decisions is exactly what that
         * space is for, and losing a game's evidence has already cost more
         * than the disk does.
         */
        internal const val MAX_BYTES = 16L * 1024L * 1024L
        internal const val ROTATION_KEEP = 3
    }
}

/**
 * Generational log rotation, split out from [AnalysisFileLogger] purely so the
 * shift order is testable: the logger's own writes go through an executor and
 * need a [Context], neither of which this depends on.
 *
 * Shifts `analysis.log` down a numbered chain instead of into a single `.bak`,
 * dropping only the oldest. The single-`.bak` scheme lost real evidence: a 2MB
 * cap held about 68 seconds of active play, because every ARROW/NO_MOVE outcome
 * writes a full per-slot `recognition:` block. A game's frames rotated away
 * before they could be pulled, so five separate false-arrow reports had no log
 * to explain them - those boards only survived because the rejection captures
 * store their own screenshots.
 */
internal object LogRotation {
    fun rotateIfNeeded(logFile: File, maxBytes: Long, keep: Int) {
        if (!logFile.exists() || logFile.length() < maxBytes) return
        val dir = logFile.parentFile ?: return
        val name = logFile.name
        File(dir, "$name.$keep").delete()
        // Highest generation first: walking upward would overwrite each
        // generation with the one below it before it had been moved along,
        // collapsing the whole chain into a single copy of the newest file.
        for (generation in keep - 1 downTo 1) {
            val older = File(dir, "$name.$generation")
            if (older.exists()) older.renameTo(File(dir, "$name.${generation + 1}"))
        }
        logFile.renameTo(File(dir, "$name.1"))
    }
}
