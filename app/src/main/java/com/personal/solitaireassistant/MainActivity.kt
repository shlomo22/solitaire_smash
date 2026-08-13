package com.personal.solitaireassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.solitaireassistant.capture.CaptureService
import com.personal.solitaireassistant.ui.SettingsScreen
import com.personal.solitaireassistant.ui.SettingsViewModel
import com.personal.solitaireassistant.ui.SettingsViewModelFactory
import com.personal.solitaireassistant.ui.theme.SolitaireAssistantTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModelFactory((application as SolitaireAssistantApp).preferences)
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                viewModel.setTransientMessage("Screen capture permission denied")
                return@registerForActivityResult
            }
            CaptureService.start(this, result.resultCode, result.data!!)
            viewModel.setTransientMessage("Capture starting…")
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                viewModel.setTransientMessage("Notifications recommended for capture service")
            }
            requestProjectionConsent()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val serviceStatus by CaptureService.status.collectAsStateWithLifecycle()
            val statusMessage by CaptureService.statusMessage.collectAsStateWithLifecycle()
            SolitaireAssistantTheme {
                SettingsScreen(
                    state = uiState,
                    serviceStatus = serviceStatus,
                    statusMessage = statusMessage,
                    openCvReady = (application as SolitaireAssistantApp).openCvReady,
                    canDrawOverlays = Settings.canDrawOverlays(this),
                    analysisLogPath = CaptureService.analysisLogPath(),
                    onOverlayColor = viewModel::setOverlayColor,
                    onInterval = viewModel::setCaptureInterval,
                    onConfidence = viewModel::setConfidence,
                    onDebugFrames = viewModel::setDebugFrames,
                    onIgnoreUserTemplates = viewModel::setIgnoreUserTemplates,
                    onOpenOverlaySettings = ::openOverlaySettings,
                    onStart = ::onStartClicked,
                    onStop = { CaptureService.stop(this) }
                )
            }
        }
    }

    private fun onStartClicked() {
        if (!Settings.canDrawOverlays(this)) {
            viewModel.setTransientMessage("Enable display-over-other-apps first")
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjectionConsent()
    }

    private fun requestProjectionConsent() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}
