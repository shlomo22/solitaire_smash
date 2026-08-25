package com.personal.solitaireassistant.vision

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ErrorCaptureEvaluatorTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun evaluateFixture225719ReportsCleanOrFixed() {
        val fixtureId = "error_20260824_225719"
        val jsonStream = javaClass.classLoader?.getResourceAsStream("recognition_errors/$fixtureId.json")
            ?: return
        val pngStream = javaClass.classLoader?.getResourceAsStream("recognition_errors/$fixtureId.png")
            ?: return

        val store = ErrorCaptureStore(context)
        val dir = store.dir()
        dir.listFiles()?.forEach { it.delete() }
        FileOutputStream(File(dir, "$fixtureId.png")).use { out ->
            pngStream.copyTo(out)
        }
        File(dir, "$fixtureId.json").writeText(jsonStream.bufferedReader().readText())

        val report = ErrorCaptureEvaluator.evaluate(context, store)
        assertTrue(report.sampleCount >= 1)
        assertTrue(
            "expected validator-clean after v1.4.29 fixes, got: ${report.summary()}",
            report.cleanCount >= 1 || report.fixedCount >= 1
        )
    }
}
