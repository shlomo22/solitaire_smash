package com.personal.solitaireassistant.vision

import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Elevates the OS scheduling priority of executor worker threads on the
 * frame-analysis hot path (the single-threaded
 * `AnalysisPipeline.analysisExecutor` that gates arrow latency, and
 * [GameStateDetector]'s per-column/waste-OCR pools it awaits synchronously
 * within one detect() call).
 *
 * `android.os.Process.setThreadPriority` sets the real Linux nice value the
 * kernel scheduler uses, unlike `java.lang.Thread.setPriority` which Android
 * mostly ignores. It has to be called from inside each worker thread itself
 * (there is no "set priority of a not-yet-created thread" API), so this
 * wraps the pool's Runnable in a Thread that sets its own priority once
 * before entering the runnable's loop - `Executors`' pool threads are
 * long-lived and reused for every future task, so priority set here persists
 * for the thread's whole life, not just the first task.
 *
 * [Process.THREAD_PRIORITY_FOREGROUND] (-2) is used rather than the more
 * aggressive [Process.THREAD_PRIORITY_URGENT_DISPLAY] (-8, the level Android
 * itself reserves for actual frame-rendering/compositor threads): elevating
 * above default background priority is the goal (winning scheduling races
 * against other apps/system threads under contention), not starving
 * Solitaire Smash's own rendering thread, which would make the game itself
 * feel worse even if our arrow got faster. Not device-verified yet - the
 * existing `timing: ... detect=` log lines are how to confirm this actually
 * moves per-frame latency on a real pull.
 */
object PriorityThreadFactory {
    fun newFixedThreadPool(nThreads: Int, name: String): ExecutorService =
        Executors.newFixedThreadPool(nThreads, factory(name))

    fun newSingleThreadExecutor(name: String): ExecutorService =
        Executors.newSingleThreadExecutor(factory(name))

    private fun factory(name: String): ThreadFactory {
        val counter = AtomicInteger(0)
        return ThreadFactory { runnable ->
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                runnable.run()
            }.also { it.name = "$name-${counter.incrementAndGet()}" }
        }
    }
}
