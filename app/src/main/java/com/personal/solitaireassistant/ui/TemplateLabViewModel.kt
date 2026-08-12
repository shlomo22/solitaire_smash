package com.personal.solitaireassistant.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.solitaireassistant.capture.CaptureService
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import com.personal.solitaireassistant.pipeline.LabCardRegion
import com.personal.solitaireassistant.pipeline.LabSnapshot
import com.personal.solitaireassistant.settings.UserTemplateStore
import com.personal.solitaireassistant.vision.CardRecognizer
import com.personal.solitaireassistant.vision.GlyphCrops
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class TemplatePreviewScores(
    val topRanks: List<Pair<Rank, Float>>,
    val topSuits: List<Pair<Suit, Float>>
)

data class TemplateLabUiState(
    val snapshot: LabSnapshot? = null,
    val regions: List<LabCardRegion> = emptyList(),
    val selectedRegionId: String? = null,
    val selectedRank: Rank = Rank.Ace,
    val selectedSuit: Suit = Suit.Spades,
    val rankCornerPreview: Bitmap? = null,
    val suitBadgePreview: Bitmap? = null,
    val previewScores: TemplatePreviewScores? = null,
    val coverage: UserTemplateStore.TemplateCoverage? = null,
    val message: String = "",
    val templateRootPath: String = ""
)

class TemplateLabViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val templateStore = UserTemplateStore(application)
    private val recognizer = CardRecognizer(application)

    private val _uiState = MutableStateFlow(
        TemplateLabUiState(
            coverage = templateStore.coverageCounts(),
            templateRootPath = templateStore.rootPath
        )
    )
    val uiState: StateFlow<TemplateLabUiState> = _uiState.asStateFlow()

    val serviceStatus = CaptureService.status.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CaptureService.Status.Idle
    )

    init {
        viewModelScope.launch {
            CaptureService.labSnapshot().collect { snapshot ->
                val current = _uiState.value
                current.rankCornerPreview?.recycle()
                current.suitBadgePreview?.recycle()
                // The pipeline transfers frame ownership to us; recycle the frame
                // we are replacing (main thread only) to avoid leaking ~10MB/frame.
                val previousFrame = current.snapshot?.bitmap
                if (previousFrame != null &&
                    previousFrame !== snapshot?.bitmap &&
                    !previousFrame.isRecycled
                ) {
                    previousFrame.recycle()
                }
                _uiState.value = current.copy(
                    snapshot = snapshot,
                    regions = snapshot?.regions.orEmpty(),
                    message = if (snapshot == null) {
                        "Waiting for capture frame — start capture over Solitaire Smash"
                    } else {
                        "Frame ${snapshot.bitmap.width}x${snapshot.bitmap.height} " +
                            "(${snapshot.regions.size} card regions)"
                    }
                )
                snapshot?.let { refreshSelection(it, current.selectedRegionId) }
            }
        }
    }

    fun selectRegion(regionId: String) {
        val snapshot = _uiState.value.snapshot ?: return
        refreshSelection(snapshot, regionId)
    }

    fun setRank(rank: Rank) {
        _uiState.value = _uiState.value.copy(selectedRank = rank)
    }

    fun setSuit(suit: Suit) {
        _uiState.value = _uiState.value.copy(selectedSuit = suit)
    }

    fun saveTemplate() {
        val state = _uiState.value
        val snapshot = state.snapshot ?: run {
            publishMessage("No frame available")
            return
        }
        val region = state.regions.firstOrNull { it.id == state.selectedRegionId }
            ?: run {
                publishMessage("Select a card region first")
                return
            }
        val crops = recognizer.extractGlyphCrops(snapshot.bitmap, region.region)
            ?: run {
                publishMessage("Could not crop rank/suit glyphs")
                return
            }
        viewModelScope.launch {
            try {
                templateStore.saveRankCorner(state.selectedRank, crops.rankCorner)
                templateStore.saveSuitBadge(state.selectedSuit, crops.suitBadge)
                state.selectedRank.takeIf { it in UserTemplateStore.GLYPH_RANKS }?.let { rank ->
                    crops.rankGlyph?.let { glyph ->
                        templateStore.saveRankGlyph(rank, glyph)
                    }
                }
                CaptureService.reloadTemplates()
                recognizer.reloadTemplates()
                _uiState.value = _uiState.value.copy(
                    coverage = templateStore.coverageCounts(),
                    message = "Saved ${state.selectedRank.name} / ${state.selectedSuit.name} templates"
                )
            } finally {
                crops.cardCrop.recycle()
                crops.rankCorner.recycle()
                crops.suitBadge.recycle()
                crops.rankGlyph?.recycle()
            }
        }
    }

    fun testRecognition() {
        val state = _uiState.value
        val snapshot = state.snapshot ?: return
        val region = state.regions.firstOrNull { it.id == state.selectedRegionId } ?: return
        val crops = recognizer.extractGlyphCrops(snapshot.bitmap, region.region) ?: return
        try {
            val rankScores = recognizer.rankCornerScores(crops.rankCorner)
            val suitScores = recognizer.suitBadgeScores(crops.suitBadge)
            _uiState.value = state.copy(
                previewScores = TemplatePreviewScores(
                    topRanks = rankScores.entries
                        .sortedByDescending { it.value }
                        .take(3)
                        .map { it.key to it.value },
                    topSuits = suitScores.entries
                        .sortedByDescending { it.value }
                        .take(3)
                        .map { it.key to it.value }
                ),
                message = "Recognition test complete"
            )
        } finally {
            crops.cardCrop.recycle()
            crops.rankCorner.recycle()
            crops.suitBadge.recycle()
            crops.rankGlyph?.recycle()
        }
    }

    fun exportTemplates(): String? {
        val downloads = File(getApplication<Application>().getExternalFilesDir(null), "exports")
            .also { it.mkdirs() }
        val zip = File(downloads, "smash_templates_${System.currentTimeMillis()}.zip")
        return if (templateStore.exportZip(zip)) {
            publishMessage("Exported to ${zip.absolutePath}")
            zip.absolutePath
        } else {
            publishMessage("Export failed")
            null
        }
    }

    private fun refreshSelection(snapshot: LabSnapshot, regionId: String?) {
        val selectedId = regionId ?: snapshot.regions.firstOrNull()?.id
        val region = snapshot.regions.firstOrNull { it.id == selectedId } ?: run {
            _uiState.value = _uiState.value.copy(
                selectedRegionId = null,
                rankCornerPreview = null,
                suitBadgePreview = null,
                previewScores = null
            )
            return
        }
        val old = _uiState.value
        old.rankCornerPreview?.recycle()
        old.suitBadgePreview?.recycle()
        val crops = recognizer.extractGlyphCrops(snapshot.bitmap, region.region)
        val detected = region.detectedCard
        _uiState.value = old.copy(
            selectedRegionId = region.id,
            selectedRank = detected?.rank ?: old.selectedRank,
            selectedSuit = detected?.suit ?: old.selectedSuit,
            rankCornerPreview = crops?.rankCorner?.copy(Bitmap.Config.ARGB_8888, false),
            suitBadgePreview = crops?.suitBadge?.copy(Bitmap.Config.ARGB_8888, false),
            previewScores = null,
            message = "Selected ${region.label}" +
                (detected?.let { " — detected ${it.id}" } ?: "")
        )
        crops?.let {
            it.cardCrop.recycle()
            it.rankCorner.recycle()
            it.suitBadge.recycle()
            it.rankGlyph?.recycle()
        }
    }

    private fun publishMessage(message: String) {
        _uiState.value = _uiState.value.copy(message = message)
    }

    override fun onCleared() {
        val state = _uiState.value
        state.rankCornerPreview?.recycle()
        state.suitBadgePreview?.recycle()
        state.snapshot?.bitmap?.takeIf { !it.isRecycled }?.recycle()
        recognizer.release()
        super.onCleared()
    }
}

class TemplateLabViewModelFactory(
    private val application: Application
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        return TemplateLabViewModel(application) as T
    }
}
