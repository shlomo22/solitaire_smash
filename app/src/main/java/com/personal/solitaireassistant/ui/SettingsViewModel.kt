package com.personal.solitaireassistant.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.personal.solitaireassistant.capture.CaptureService
import com.personal.solitaireassistant.capture.PendingSnapshotHolder
import com.personal.solitaireassistant.settings.AssistantPreferences
import com.personal.solitaireassistant.settings.AssistantSettings
import com.personal.solitaireassistant.vision.GoldenSample
import com.personal.solitaireassistant.pipeline.AnalysisFileLogger
import com.personal.solitaireassistant.vision.GoldenTruthEvaluator
import com.personal.solitaireassistant.vision.GoldenTruthStore
import com.personal.solitaireassistant.vision.SlotGuess
import com.personal.solitaireassistant.vision.toGoldenSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: AssistantSettings = AssistantSettings(),
    val transientMessage: String = "",
    val goldenCount: Int = 0,
    val goldenPath: String = "",
    val evalReport: String = "",
    val evaluating: Boolean = false,
    val showGoldenReview: Boolean = false
)

class SettingsViewModel(
    application: Application,
    private val preferences: AssistantPreferences
) : AndroidViewModel(application) {
    private val store = GoldenTruthStore(application)
    private val transient = MutableStateFlow("")
    private val goldenCount = MutableStateFlow(store.count())
    private val evalReport = MutableStateFlow("")
    private val evaluating = MutableStateFlow(false)
    private val showGoldenReview = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(preferences.settings, transient, goldenCount) { settings, message, count ->
            Triple(settings, message, count)
        },
        combine(evalReport, evaluating, showGoldenReview) { report, running, review ->
            Triple(report, running, review)
        }
    ) { left, right ->
        SettingsUiState(
            settings = left.first,
            transientMessage = left.second,
            goldenCount = left.third,
            goldenPath = store.pathForDisplay(),
            evalReport = right.first,
            evaluating = right.second,
            showGoldenReview = right.third
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setOverlayColor(color: Color) {
        viewModelScope.launch { preferences.updateOverlayColor(color) }
    }

    fun setCaptureInterval(ms: Long) {
        viewModelScope.launch { preferences.updateCaptureIntervalMs(ms) }
    }

    fun setConfidence(value: Float) {
        viewModelScope.launch { preferences.updateMinMatchConfidence(value) }
    }

    fun setDebugFrames(enabled: Boolean) {
        viewModelScope.launch { preferences.updateDebugSaveFrames(enabled) }
    }

    fun setTransientMessage(message: String) {
        transient.value = message
    }

    fun openGoldenReview() {
        if (PendingSnapshotHolder.peek() == null) {
            transient.value = "No snapshot available — capture a board first"
            return
        }
        showGoldenReview.value = true
        CaptureService.setGoldenReviewActive(true)
    }

    fun closeGoldenReview() {
        showGoldenReview.value = false
    }

    fun discardGoldenReview() {
        PendingSnapshotHolder.clear()
        showGoldenReview.value = false
    }

    fun saveGoldenReview(truths: List<SlotGuess>) {
        val snapshot = PendingSnapshotHolder.peek() ?: run {
            transient.value = "Snapshot was lost"
            showGoldenReview.value = false
            return
        }
        viewModelScope.launch {
            val id = store.nextId()
            val sample = GoldenSample(
                id = id,
                frameWidth = snapshot.bitmap.width,
                frameHeight = snapshot.bitmap.height,
                slots = snapshot.slots.mapIndexed { index, slot ->
                    slot.toGoldenSlot(truths.getOrElse(index) { slot.engine })
                }
            )
            showGoldenReview.value = false
            withContext(Dispatchers.IO) {
                store.save(snapshot.bitmap, sample)
            }
            PendingSnapshotHolder.clear()
            goldenCount.value = store.count()
            transient.value = "Saved golden sample $id"
        }
    }

    fun evaluateGoldenSet() {
        if (evaluating.value) return
        viewModelScope.launch {
            evaluating.value = true
            transient.value = "Evaluating golden set…"
            val report = withContext(Dispatchers.Default) {
                val result = GoldenTruthEvaluator.evaluate(getApplication(), store)
                val logger = AnalysisFileLogger(getApplication())
                logger.append("=== golden evaluate ===")
                result.summary().lines().forEach { line -> logger.append(line) }
                val traceBlock = result.mismatchTraceBlock()
                if (traceBlock.isNotBlank()) {
                    traceBlock.lines().forEach { line -> logger.append(line) }
                }
                result
            }
            evalReport.value = report.summary()
            evaluating.value = false
            transient.value = "Golden evaluation finished"
        }
    }

    fun refreshGoldenCount() {
        goldenCount.value = store.count()
    }
}

class SettingsViewModelFactory(
    private val application: Application,
    private val preferences: AssistantPreferences
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(application, preferences) as T
    }
}
