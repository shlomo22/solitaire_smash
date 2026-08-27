package com.personal.solitaireassistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.solitaireassistant.settings.AssistantPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesInstrumentedTest {
    @Test
    fun preferencesRoundTripInterval() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = AssistantPreferences(context)
        prefs.updateCaptureIntervalMs(1000L)
        val settings = prefs.settings.first()
        assertTrue(settings.captureIntervalMs in 150L..3000L)
    }
}

