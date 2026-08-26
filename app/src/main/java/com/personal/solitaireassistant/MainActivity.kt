package com.personal.solitaireassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.solitaireassistant.capture.CaptureService
import com.personal.solitaireassistant.capture.PendingSnapshotHolder
import com.personal.solitaireassistant.capture.SolitaireSmashLauncher
import com.personal.solitaireassistant.ui.GoldenReviewScreen
import com.personal.solitaireassistant.ui.SettingsScreen
import com.personal.solitaireassistant.ui.SettingsViewModel
import com.personal.solitaireassistant.ui.SettingsViewModelFactory
import com.personal.solitaireassistant.ui.theme.SolitaireAssistantTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels {
        val app = application as SolitaireAssistantApp
        SettingsViewModelFactory(app, app.preferences)
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                viewModel.setTransientMessage("Screen capture permission denied")
                return@registerForActivityResult
            }
            CaptureService.start(this, result.resultCode, result.data!!)
            val launched = SolitaireSmashLauncher.launch(this)
            viewModel.setTransientMessage(
                if (launched) {
                    "Capture starting — opening Solitaire Smash…"
                } else {
                    "Capture starting… Open Solitaire Smash manually"
                }
            )
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
        maybeOpenGoldenReview(intent)
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val serviceStatus by CaptureService.status.collectAsStateWithLifecycle()
            val statusMessage by CaptureService.statusMessage.collectAsStateWithLifecycle()
            SolitaireAssistantTheme {
                val snapshot =
                    if (uiState.showGoldenReview) PendingSnapshotHolder.peek() else null
                if (uiState.showGoldenReview && snapshot != null) {
                    GoldenReviewScreen(
                        bitmap = snapshot.bitmap,
                        slots = snapshot.slots,
                        onSave = viewModel::saveGoldenReview,
                        onDiscard = viewModel::discardGoldenReview,
                        onRecapture = {
                            viewModel.discardGoldenReview()
                            SolitaireSmashLauncher.launch(this)
                        },
                        initialTruths = snapshot.initialTruths,
                        allowRecapture = snapshot.allowRecapture,
                        suspiciousHints = snapshot.suspiciousHints,
                        title = if (snapshot.sourceErrorCaptureId != null) {
                            "Import error capture"
                        } else {
                            "Confirm recognized cards"
                        },
                        subtitle = if (snapshot.sourceErrorCaptureId != null) {
                            "Correct each card against the frozen PNG, then Save to add it to golden."
                        } else {
                            "Tap a row below to correct rank and suit. Inferred cascade cards are optional."
                        }
                    )
                } else {
                    if (uiState.showGoldenReview && snapshot == null) {
                        LaunchedEffect(Unit) { viewModel.closeGoldenReview() }
                    }
                    SettingsScreen(
                        state = uiState,
                        serviceStatus = serviceStatus,
                        statusMessage = statusMessage,
                        openCvReady = (application as SolitaireAssistantApp).openCvReady,
                        ocrReady = (application as SolitaireAssistantApp).ocrReady,
                        canDrawOverlays = Settings.canDrawOverlays(this),
                        analysisLogPath = CaptureService.analysisLogPath(),
                        onOverlayColor = viewModel::setOverlayColor,
                        onInterval = viewModel::setCaptureInterval,
                        onConfidence = viewModel::setConfidence,
                        onDebugFrames = viewModel::setDebugFrames,
                        onAutoCaptureRecognitionErrors = viewModel::setAutoCaptureRecognitionErrors,
                        onCaptureRawReadErrors = viewModel::setCaptureRawReadErrors,
                        onDeleteErrorCaptures = viewModel::deleteErrorCaptures,
                        onCompactErrorCaptures = viewModel::compactErrorCaptures,
                        onEvaluateErrorCaptures = viewModel::evaluateErrorCaptures,
                        onImportErrorCapture = viewModel::openErrorCaptureImport,
                        onDismissErrorCaptureImport = viewModel::dismissErrorCaptureImport,
                        onSelectErrorCaptureImport = viewModel::importErrorCapture,
                        onOpenOverlaySettings = ::openOverlaySettings,
                        onStart = ::onStartClicked,
                        onStop = { CaptureService.stop(this) },
                        onSnapshotBoard = ::onSnapshotClicked,
                        onEvaluateGolden = viewModel::evaluateGoldenSet
                    )
                    LaunchedEffect(Unit) { viewModel.maybeAutoEvaluateOnOpen() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeOpenGoldenReview(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshGoldenCount()
        viewModel.refreshErrorCaptureCount()
        // The move-suggestion arrow/Label/Cancel chrome is a system-wide
        // TYPE_APPLICATION_OVERLAY window - it floats above every app,
        // including this Activity's own Settings/Golden-Truth screens, with
        // no awareness of which app the user is actually looking at. A real
        // device log showed it stay visible on top of the Settings screen
        // for minutes because the underlying capture was still genuinely
        // watching a real, ongoing Solitaire Smash game the whole time (so
        // the existing isLivePlayScreen gate never had a reason to hide it)
        // - the arrow just isn't relevant here regardless of what's being
        // detected. Reuse the same reviewMode hide this app already applies
        // during golden-truth card review, but drive it from this
        // Activity's own foreground state instead: hidden whenever this
        // screen is what the user is looking at, restored once they leave
        // (e.g. Start launches Solitaire Smash and this Activity pauses).
        CaptureService.setGoldenReviewActive(true)
    }

    override fun onPause() {
        super.onPause()
        CaptureService.setGoldenReviewActive(false)
    }

    private fun maybeOpenGoldenReview(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_GOLDEN_REVIEW, false) == true) {
            viewModel.openGoldenReview()
        }
    }

    private fun onSnapshotClicked() {
        if (CaptureService.snapshotBoard()) {
            viewModel.openGoldenReview()
        } else {
            viewModel.setTransientMessage("No board frame yet — wait for capture")
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
        // Android 14+ otherwise shows a single-app vs entire-screen picker.
        // Entire-screen capture skips that chooser; we then open Solitaire Smash.
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    companion object {
        const val EXTRA_OPEN_GOLDEN_REVIEW =
            "com.personal.solitaireassistant.OPEN_GOLDEN_REVIEW"
    }
}
