package com.personal.solitaireassistant.ui

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.personal.solitaireassistant.settings.AssistantPreferences
import com.personal.solitaireassistant.settings.AssistantSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AssistantSettings = AssistantSettings(),
    val transientMessage: String = ""
)

class SettingsViewModel(
    private val preferences: AssistantPreferences
) : ViewModel() {
    private val transient = MutableStateFlow("")

    val uiState: StateFlow<SettingsUiState> = combine(
        preferences.settings,
        transient
    ) { settings, message ->
        SettingsUiState(settings, message)
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
}

class SettingsViewModelFactory(
    private val preferences: AssistantPreferences
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(preferences) as T
    }
}
