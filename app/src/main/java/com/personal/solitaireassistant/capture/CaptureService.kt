package com.personal.solitaireassistant.capture

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.personal.solitaireassistant.MainActivity
import com.personal.solitaireassistant.R
import com.personal.solitaireassistant.SolitaireAssistantApp
import com.personal.solitaireassistant.overlay.OverlayController
import com.personal.solitaireassistant.pipeline.AnalysisPipeline
import com.personal.solitaireassistant.settings.AssistantSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var prefsJob: Job? = null
    private var captureController: ScreenCaptureController? = null
    private var overlayController: OverlayController? = null
    private var pipeline: AnalysisPipeline? = null
    private val settingsRef = AtomicReference(AssistantSettings())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        _status.value = Status.Starting
        overlayController = OverlayController(
            this,
            onCancelHint = { pipeline?.cancelCurrentHint() },
            onLabelBoard = { takeGoldenSnapshot() }
        )
        pipeline = AnalysisPipeline(
            appContext = applicationContext,
            overlayController = overlayController!!,
            statusSink = { msg -> _statusMessage.value = msg }
        )
        val prefs = (application as SolitaireAssistantApp).preferences
        prefsJob = scope.launch {
            prefs.settings.collectLatest { assistantSettings ->
                settingsRef.set(assistantSettings)
                captureController?.updateInterval(assistantSettings.captureIntervalMs)
                overlayController?.setColor(assistantSettings.overlayColorArgb)
                pipeline?.updateSettings(assistantSettings)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data == null) {
                    _status.value = Status.Error("Missing MediaProjection consent data")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                beginProjection(resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            _status.value = Status.Error("MediaProjection failed: ${t.message}")
            stopSelf()
            return
        }
        if (projection == null) {
            _status.value = Status.Error("MediaProjection was null")
            stopSelf()
            return
        }

        overlayController?.showIdle()
        val settings = settingsRef.get()
        captureController = ScreenCaptureController(
            context = this,
            mediaProjection = projection,
            onFrame = { bitmap -> handleFrame(bitmap) },
            onStopped = {
                _status.value = Status.Stopped
                _statusMessage.value = "Capture stopped by system"
                stopSelf()
            },
            onBlackFrame = {
                _statusMessage.value =
                    "Black frame detected — game may block capture (FLAG_SECURE)"
            }
        ).also { it.start(settings.captureIntervalMs) }

        _status.value = Status.Running
        _statusMessage.value = "Capture running"
    }

    private fun handleFrame(bitmap: Bitmap) {
        val settings = settingsRef.get()
        if (settings.debugSaveFrames) {
            saveDebugFrame(bitmap)
        }
        pipeline?.onFrame(bitmap, settings)
    }

    private fun saveDebugFrame(bitmap: Bitmap) {
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(cacheDir, "frames").also { it.mkdirs() }
                val file = File(dir, "latest.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed saving debug frame", t)
            }
        }
    }

    private fun stopCapture() {
        captureController?.stop()
        captureController = null
        overlayController?.hide()
        pipeline?.clear()
        _status.value = Status.Stopped
        _statusMessage.value = "Stopped"
    }

    override fun onDestroy() {
        stopCapture()
        prefsJob?.cancel()
        scope.cancel()
        overlayController = null
        pipeline = null
        instance = null
        super.onDestroy()
    }

    private fun takeGoldenSnapshot(): Boolean {
        val snap = pipeline?.createSnapshot()
        if (snap == null) {
            _statusMessage.value = "No board frame yet — wait for capture"
            return false
        }
        PendingSnapshotHolder.set(snap)
        overlayController?.setReviewMode(true)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(MainActivity.EXTRA_OPEN_GOLDEN_REVIEW, true)
        }
        startActivity(intent)
        _statusMessage.value = "Snapshot captured — confirm cards"
        return true
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SolitaireAssistantApp.CAPTURE_CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    sealed class Status {
        data object Idle : Status()
        data object Starting : Status()
        data object Running : Status()
        data object Stopped : Status()
        data class Error(val message: String) : Status()
    }

    companion object {
        private const val TAG = "CaptureService"
        const val ACTION_START = "com.personal.solitaireassistant.START_CAPTURE"
        const val ACTION_STOP = "com.personal.solitaireassistant.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 42

        @Volatile
        private var instance: CaptureService? = null

        private val _status = MutableStateFlow<Status>(Status.Idle)
        val status: StateFlow<Status> = _status.asStateFlow()

        private val _statusMessage = MutableStateFlow("Idle")
        val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

        fun analysisLogPath(): String? = instance?.pipeline?.analysisLogPath

        fun snapshotBoard(): Boolean = instance?.takeGoldenSnapshot() == true

        fun setGoldenReviewActive(active: Boolean) {
            instance?.overlayController?.setReviewMode(active)
            instance?.pipeline?.setAssistantForeground(active)
        }

        fun cancelCurrentHint() {
            instance?.pipeline?.cancelCurrentHint()
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
