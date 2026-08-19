package com.personal.solitaireassistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.personal.solitaireassistant.settings.AssistantPreferences
import org.opencv.android.OpenCVLoader

class SolitaireAssistantApp : Application() {
    lateinit var preferences: AssistantPreferences
        private set

    @Volatile
    var openCvReady: Boolean = false
        private set

    /** ML Kit bundled text recognition is always available once the app starts. */
    val ocrReady: Boolean = true

    override fun onCreate() {
        super.onCreate()
        preferences = AssistantPreferences(this)
        openCvReady = OpenCVLoader.initLocal()
        ensureNotificationChannel()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CAPTURE_CHANNEL_ID,
            getString(R.string.capture_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.capture_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CAPTURE_CHANNEL_ID = "capture_channel"
    }
}
