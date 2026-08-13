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
    val captureIntervalMs: Long = 300L,
    val minMatchConfidence: Float = 0.72f,
    val debugSaveFrames: Boolean = false,
    /** When true, recognition ignores user-captured Lab templates in files/templates/. */
    val ignoreUserTemplates: Boolean = false
) {
    val overlayColor: Color get() = Color(overlayColorArgb)
}

class AssistantPreferences(private val context: Context) {
    private object Keys {
        val overlayColor = intPreferencesKey("overlay_color")
        val captureIntervalMs = longPreferencesKey("capture_interval_ms")
        val minMatchConfidence = floatPreferencesKey("min_match_confidence")
        val debugSaveFrames = intPreferencesKey("debug_save_frames")
        val ignoreUserTemplates = intPreferencesKey("ignore_user_templates")
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
                // Migrate the original default to the faster interval.
                750L, null -> AssistantSettings().captureIntervalMs
                else -> saved
            },
            minMatchConfidence = prefs[Keys.minMatchConfidence]
                ?: AssistantSettings().minMatchConfidence,
            debugSaveFrames = (prefs[Keys.debugSaveFrames] ?: 0) == 1,
            ignoreUserTemplates = (prefs[Keys.ignoreUserTemplates] ?: 0) == 1
        )
    }

    suspend fun updateOverlayColor(color: Color) {
        context.dataStore.edit { it[Keys.overlayColor] = color.toArgb() }
    }

    suspend fun updateCaptureIntervalMs(ms: Long) {
        context.dataStore.edit {
            it[Keys.captureIntervalMs] = ms.coerceIn(250L, 3000L)
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

    suspend fun updateIgnoreUserTemplates(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.ignoreUserTemplates] = if (enabled) 1 else 0
        }
    }
}
