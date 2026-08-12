package com.personal.solitaireassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.personal.solitaireassistant.capture.CaptureService

private val presetColors = listOf(
    Color(0xE6000000.toInt()),
    Color(0xE6FF5722.toInt()),
    Color(0xE62196F3.toInt()),
    Color(0xE64CAF50.toInt()),
    Color(0xE6FFFFFF.toInt())
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    serviceStatus: CaptureService.Status,
    statusMessage: String,
    openCvReady: Boolean,
    canDrawOverlays: Boolean,
    analysisLogPath: String?,
    onOverlayColor: (Color) -> Unit,
    onInterval: (Long) -> Unit,
    onConfidence: (Float) -> Unit,
    onDebugFrames: (Boolean) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenTemplateLab: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val settings = state.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Solitaire Assistant", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Personal draw-3 Klondike overlay helper for Solitaire Smash.",
            style = MaterialTheme.typography.bodyMedium
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text("Service: ${serviceStatus::class.simpleName}")
                Text(statusMessage)
                Text("OpenCV: ${if (openCvReady) "ready" else "FAILED"}")
                Text("Overlay permission: ${if (canDrawOverlays) "granted" else "missing"}")
                Text(
                    "Analysis log: ${analysisLogPath ?: "(start capture to create)"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Pull: adb exec-out run-as com.personal.solitaireassistant cat files/logs/analysis.log",
                    style = MaterialTheme.typography.bodySmall
                )
                if (state.transientMessage.isNotBlank()) {
                    Text(state.transientMessage, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Overlay color", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    presetColors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color.copy(alpha = 1f))
                                .border(
                                    width = if (settings.overlayColorArgb == color.toArgb()) {
                                        3.dp
                                    } else {
                                        1.dp
                                    },
                                    color = Color.DarkGray,
                                    shape = CircleShape
                                )
                                .clickable { onOverlayColor(color) }
                        )
                    }
                }

                Text("Capture interval: ${settings.captureIntervalMs} ms")
                Slider(
                    value = settings.captureIntervalMs.toFloat(),
                    onValueChange = { onInterval(it.toLong()) },
                    valueRange = 250f..3000f,
                    steps = 10
                )

                Text("Match confidence: ${"%.2f".format(settings.minMatchConfidence)}")
                Slider(
                    value = settings.minMatchConfidence,
                    onValueChange = onConfidence,
                    valueRange = 0.4f..0.95f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Save debug frames")
                    Switch(
                        checked = settings.debugSaveFrames,
                        onCheckedChange = onDebugFrames
                    )
                }
            }
        }

        if (!canDrawOverlays) {
            OutlinedButton(
                onClick = onOpenOverlaySettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant overlay permission")
            }
        }

        OutlinedButton(
            onClick = onOpenTemplateLab,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Template Lab")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                enabled = serviceStatus !is CaptureService.Status.Running &&
                    serviceStatus !is CaptureService.Status.Starting
            ) {
                Text("Start")
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Test order: 1) Start capture over Solitaire Smash. " +
                "2) Open Template Lab to save rank/suit crops (hints pause). " +
                "3) Run live overlay and tap Cancel on wrong hints. " +
                "4) Pull analysis log via adb if needed.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
