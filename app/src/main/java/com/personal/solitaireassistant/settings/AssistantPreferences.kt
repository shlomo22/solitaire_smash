package com.personal.solitaireassistant.settings

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "assistant_settings"
)

data class AssistantSettings(
    val overlayColorArgb: Int = Color(0xE6000000.toInt()).toArgb(),
    val captureIntervalMs: Long = 200L,
    val minMatchConfidence: Float = 0.72f,
    val debugSaveFrames: Boolean = false,
    val autoCaptureRecognitionErrors: Boolean = false,
    val captureRawReadErrors: Boolean = false,
    val saveMoveHistory: Boolean = false
) {
    val overlayColor: Color get() = Color(overlayColorArgb)
}

class AssistantPreferences(private val context: Context) {
    private object Keys {
        val overlayColor = intPreferencesKey("overlay_color")
        val captureIntervalMs = longPreferencesKey("capture_interval_ms")
        val minMatchConfidence = floatPreferencesKey("min_match_confidence")
        val debugSaveFrames = intPreferencesKey("debug_save_frames")
        val autoCaptureRecognitionErrors = intPreferencesKey("auto_capture_recognition_errors")
        val captureRawReadErrors = intPreferencesKey("capture_raw_read_errors")
        val saveMoveHistory = intPreferencesKey("save_move_history")
    }

    val settings: Flow<AssistantSettings> = context.dataStore.data.map { prefs ->
        val savedColor = prefs[Keys.overlayColor]
        val effectiveColor = when (savedColor) {
            // Migrate the original yellow default to the new black default.
            0xE6FFEB3B.toInt(), null -> AssistantSettings().overlayColorArgb
            else -> savedColor
        }
        AssistantSettings(
            overlayColorArgb = effectiveColor,
            captureIntervalMs = when (val saved = prefs[Keys.captureIntervalMs]) {
                // Migrate older defaults to the current faster interval.
                750L, 300L, null -> AssistantSettings().captureIntervalMs
                else -> saved
            },
            minMatchConfidence = prefs[Keys.minMatchConfidence]
                ?: AssistantSettings().minMatchConfidence,
            debugSaveFrames = (prefs[Keys.debugSaveFrames] ?: 0) == 1,
            autoCaptureRecognitionErrors =
                (prefs[Keys.autoCaptureRecognitionErrors] ?: 0) == 1,
            captureRawReadErrors = (prefs[Keys.captureRawReadErrors] ?: 0) == 1,
            saveMoveHistory = (prefs[Keys.saveMoveHistory] ?: 0) == 1
        )
    }

    suspend fun updateOverlayColor(color: Color) {
        context.dataStore.edit { it[Keys.overlayColor] = color.toArgb() }
    }

    suspend fun updateCaptureIntervalMs(ms: Long) {
        context.dataStore.edit {
            it[Keys.captureIntervalMs] = ms.coerceIn(150L, 3000L)
        }
    }

    suspend fun updateMinMatchConfidence(value: Float) {
        context.dataStore.edit {
            it[Keys.minMatchConfidence] = value.coerceIn(0.4f, 0.95f)
        }
    }

    suspend fun updateDebugSaveFrames(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.debugSaveFrames] = if (enabled) 1 else 0
        }
    }

    suspend fun updateAutoCaptureRecognitionErrors(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.autoCaptureRecognitionErrors] = if (enabled) 1 else 0
        }
    }

    suspend fun updateCaptureRawReadErrors(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.captureRawReadErrors] = if (enabled) 1 else 0
        }
    }

    suspend fun updateSaveMoveHistory(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.saveMoveHistory] = if (enabled) 1 else 0
        }
    }
}
