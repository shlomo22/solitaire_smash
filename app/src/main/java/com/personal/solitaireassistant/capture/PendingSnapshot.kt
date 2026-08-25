package com.personal.solitaireassistant.capture

import android.graphics.Bitmap
import com.personal.solitaireassistant.vision.RecognizedSlot
import com.personal.solitaireassistant.vision.SlotGuess
import com.personal.solitaireassistant.vision.SuspiciousSlotHint

data class PendingSnapshot(
    val bitmap: Bitmap,
    val slots: List<RecognizedSlot>,
    val diagnostics: List<String>,
    val initialTruths: List<SlotGuess>? = null,
    val allowRecapture: Boolean = true,
    val sourceErrorCaptureId: String? = null,
    val suspiciousHints: List<SuspiciousSlotHint> = emptyList()
)

object PendingSnapshotHolder {
    private val lock = Any()
    private var current: PendingSnapshot? = null

    fun set(snapshot: PendingSnapshot) {
        synchronized(lock) {
            current = snapshot
        }
    }

    fun peek(): PendingSnapshot? = synchronized(lock) { current }

    fun clear() {
        synchronized(lock) {
            current = null
        }
    }
}
