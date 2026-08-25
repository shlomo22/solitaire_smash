package com.personal.solitaireassistant.ui

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.personal.solitaireassistant.capture.CaptureService
import com.personal.solitaireassistant.capture.PendingSnapshot
import com.personal.solitaireassistant.capture.PendingSnapshotHolder
import com.personal.solitaireassistant.settings.AssistantPreferences
import com.personal.solitaireassistant.settings.AssistantSettings
import com.personal.solitaireassistant.vision.ErrorCaptureEvaluator
import com.personal.solitaireassistant.vision.ErrorCaptureStore
import com.personal.solitaireassistant.vision.ErrorCaptureReviewHints
import com.personal.solitaireassistant.vision.GoldenSample
import com.personal.solitaireassistant.vision.GoldenTruthJson
import com.personal.solitaireassistant.pipeline.AnalysisFileLogger
import com.personal.solitaireassistant.vision.GoldenTruthEvaluator
import com.personal.solitaireassistant.vision.GoldenTruthStore
import com.personal.solitaireassistant.vision.SlotGuess
import com.personal.solitaireassistant.vision.toGoldenSlot
import com.personal.solitaireassistant.vision.toRecognizedSlot
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
    val errorCaptureCount: Int = 0,
    val errorCapturePath: String = "",
    val evalReport: String = "",
    val errorEvalReport: String = "",
    val evaluating: Boolean = false,
    val evaluatingErrors: Boolean = false,
    val showGoldenReview: Boolean = false,
    val showErrorCaptureImport: Boolean = false,
    val errorCaptureImportIds: List<String> = emptyList()
)

class SettingsViewModel(
    application: Application,
    private val preferences: AssistantPreferences
) : AndroidViewModel(application) {
    private val store = GoldenTruthStore(application)
    private val errorCaptureStore = ErrorCaptureStore(application)
    private val transient = MutableStateFlow("")
    private val goldenCount = MutableStateFlow(store.count())
    private val errorCaptureCount = MutableStateFlow(errorCaptureStore.count())
    private val evalReport = MutableStateFlow("")
    private val errorEvalReport = MutableStateFlow("")
    private val evaluating = MutableStateFlow(false)
    private val evaluatingErrors = MutableStateFlow(false)
    private val showGoldenReview = MutableStateFlow(false)
    private val showErrorCaptureImport = MutableStateFlow(false)
    private val errorCaptureImportIds = MutableStateFlow<List<String>>(emptyList())

    private val coreState = combine(
        preferences.settings,
        transient,
        goldenCount,
        errorCaptureCount
    ) { settings, message, count, errorCount ->
        CoreSettingsState(settings, message, count, errorCount)
    }
    private val panelState = combine(
        evalReport,
        errorEvalReport,
        evaluating,
        evaluatingErrors,
        showGoldenReview
    ) { report, errorReport, running, runningErrors, review ->
        EvalPanelStatePart1(report, errorReport, running, runningErrors, review)
    }
    private val importState = combine(showErrorCaptureImport, errorCaptureImportIds) { import, ids ->
        import to ids
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        coreState,
        panelState,
        importState
    ) { core, part1, importPair ->
        SettingsUiState(
            settings = core.settings,
            transientMessage = core.message,
            goldenCount = core.goldenCount,
            goldenPath = store.pathForDisplay(),
            errorCaptureCount = core.errorCaptureCount,
            errorCapturePath = errorCaptureStore.pathForDisplay(),
            evalReport = part1.evalReport,
            errorEvalReport = part1.errorEvalReport,
            evaluating = part1.evaluating,
            evaluatingErrors = part1.evaluatingErrors,
            showGoldenReview = part1.showGoldenReview,
            showErrorCaptureImport = importPair.first,
            errorCaptureImportIds = importPair.second
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

    fun setAutoCaptureRecognitionErrors(enabled: Boolean) {
        viewModelScope.launch { preferences.updateAutoCaptureRecognitionErrors(enabled) }
    }

    fun setCaptureRawReadErrors(enabled: Boolean) {
        viewModelScope.launch { preferences.updateCaptureRawReadErrors(enabled) }
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
        val snapshot = PendingSnapshotHolder.peek()
        val importedId = snapshot?.sourceErrorCaptureId
        PendingSnapshotHolder.clear()
        showGoldenReview.value = false
        if (importedId != null) {
            viewModelScope.launch {
                removeImportedErrorCapture(
                    importedId = importedId,
                    emptyMessage = "Discarded $importedId — no captures left",
                    remainingMessage = "Discarded $importedId"
                )
            }
        }
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
            val sourceId = snapshot.sourceErrorCaptureId
            withContext(Dispatchers.IO) {
                store.save(snapshot.bitmap, sample)
            }
            PendingSnapshotHolder.clear()
            goldenCount.value = store.count()
            if (sourceId != null) {
                removeImportedErrorCapture(
                    importedId = sourceId,
                    emptyMessage = "Saved golden sample $id — no error captures left",
                    remainingMessage = "Saved golden sample $id (from $sourceId)"
                )
            } else {
                transient.value = "Saved golden sample $id"
            }
        }
    }

    private suspend fun removeImportedErrorCapture(
        importedId: String,
        emptyMessage: String,
        remainingMessage: String
    ) {
        withContext(Dispatchers.IO) {
            errorCaptureStore.delete(importedId)
        }
        errorCaptureCount.value = errorCaptureStore.count()
        val remaining = errorCaptureStore.listIdsNewestFirst()
        if (remaining.isEmpty()) {
            showErrorCaptureImport.value = false
            errorCaptureImportIds.value = emptyList()
            transient.value = emptyMessage
        } else {
            errorCaptureImportIds.value = remaining
            showErrorCaptureImport.value = true
            transient.value = remainingMessage
        }
    }

    fun evaluateGoldenSet() {
        if (evaluating.value || evaluatingErrors.value) return
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

    fun refreshErrorCaptureCount() {
        errorCaptureCount.value = errorCaptureStore.count()
    }

    fun deleteErrorCaptures() {
        if (errorCaptureCount.value <= 0) return
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { errorCaptureStore.clearAll() }
            errorCaptureCount.value = errorCaptureStore.count()
            transient.value = if (removed > 0) {
                "Deleted $removed saved capture${if (removed == 1) "" else "s"}"
            } else {
                "No saved captures to delete"
            }
        }
    }

    fun compactErrorCaptures() {
        if (errorCaptureCount.value <= 1) {
            transient.value = "Need at least 2 captures to compact"
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { errorCaptureStore.compactConsecutiveDuplicates() }
            errorCaptureCount.value = errorCaptureStore.count()
            if (showErrorCaptureImport.value) {
                val remaining = errorCaptureStore.listIdsNewestFirst()
                errorCaptureImportIds.value = remaining
                if (remaining.isEmpty()) {
                    showErrorCaptureImport.value = false
                }
            }
            transient.value = when {
                result.removed <= 0 -> "No consecutive duplicate captures found"
                result.remaining <= 0 -> "Compacted ${result.removed} duplicate captures — none left"
                else -> "Compacted ${result.removed} duplicate capture${if (result.removed == 1) "" else "s"} " +
                    "(${result.remaining} remaining)"
            }
        }
    }

    fun evaluateErrorCaptures() {
        if (evaluatingErrors.value || evaluating.value) return
        if (errorCaptureCount.value <= 0) {
            transient.value = "No error captures saved"
            return
        }
        viewModelScope.launch {
            evaluatingErrors.value = true
            transient.value = "Evaluating error captures…"
            val report = withContext(Dispatchers.Default) {
                val result = ErrorCaptureEvaluator.evaluate(getApplication(), errorCaptureStore)
                val logger = AnalysisFileLogger(getApplication())
                logger.append("=== error capture evaluate ===")
                result.summary().lines().forEach { line -> logger.append(line) }
                val detail = result.detailBlock()
                if (detail.isNotBlank()) {
                    detail.lines().forEach { line -> logger.append(line) }
                }
                result
            }
            errorEvalReport.value = report.summary()
            evaluatingErrors.value = false
            transient.value = "Error capture evaluation finished"
        }
    }

    fun openErrorCaptureImport() {
        val ids = errorCaptureStore.listIdsNewestFirst()
        if (ids.isEmpty()) {
            transient.value = "No error captures saved"
            return
        }
        errorCaptureImportIds.value = ids
        showErrorCaptureImport.value = true
    }

    fun dismissErrorCaptureImport() {
        showErrorCaptureImport.value = false
    }

    fun importErrorCapture(id: String) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val bitmap = errorCaptureStore.loadBitmap(id) ?: return@withContext null
                val sample = errorCaptureStore.loadSample(id) ?: run {
                    bitmap.recycle()
                    return@withContext null
                }
                Triple(bitmap, sample, id)
            } ?: run {
                transient.value = "Could not load $id"
                return@launch
            }
            val (bitmap, sample, captureId) = loaded
            val suspiciousHints = withContext(Dispatchers.IO) {
                errorCaptureStore.loadJsonText(captureId)
                    ?.let { GoldenTruthJson.parseErrorCaptureMeta(it) }
                    ?.violations
                    ?.let { ErrorCaptureReviewHints.fromViolations(it) }
                    .orEmpty()
            }
            PendingSnapshotHolder.clear()
            PendingSnapshotHolder.set(
                PendingSnapshot(
                    bitmap = bitmap,
                    slots = sample.slots.map { it.toRecognizedSlot() },
                    diagnostics = emptyList(),
                    initialTruths = sample.slots.map { it.truth },
                    allowRecapture = false,
                    sourceErrorCaptureId = captureId,
                    suspiciousHints = suspiciousHints
                )
            )
            showErrorCaptureImport.value = false
            showGoldenReview.value = true
            CaptureService.setGoldenReviewActive(true)
            transient.value = "Label cards, then Save to add $captureId to golden set"
        }
    }
}

private data class CoreSettingsState(
    val settings: AssistantSettings,
    val message: String,
    val goldenCount: Int,
    val errorCaptureCount: Int
)

private data class EvalPanelStatePart1(
    val evalReport: String,
    val errorEvalReport: String,
    val evaluating: Boolean,
    val evaluatingErrors: Boolean,
    val showGoldenReview: Boolean
)

class SettingsViewModelFactory(
    private val application: Application,
    private val preferences: AssistantPreferences
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(application, preferences) as T
    }
}
