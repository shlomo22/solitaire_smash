package com.personal.solitaireassistant.vision

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ErrorCaptureCompactTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun compactRemovesConsecutiveCapturesWithSameViolations() {
        val fixtureId = "error_20260824_233441"
        val jsonText = javaClass.classLoader!!
            .getResourceAsStream("recognition_errors/$fixtureId.json")!!
            .bufferedReader()
            .readText()
        val pngBytes = javaClass.classLoader!!
            .getResourceAsStream("recognition_errors/$fixtureId.png")!!
            .readBytes()

        val store = ErrorCaptureStore(context)
        store.dir().listFiles()?.forEach { it.delete() }

        writeCapture(store, "error_20260825_100000", jsonText, pngBytes)
        writeCapture(
            store,
            "error_20260825_100030",
            jsonText.replace("\"id\": \"error_20260824_233441\"", "\"id\": \"error_20260825_100030\""),
            pngBytes
        )
        writeCapture(
            store,
            "error_20260825_100100",
            jsonText.replace("\"id\": \"error_20260824_233441\"", "\"id\": \"error_20260825_100100\""),
            pngBytes
        )
        writeCapture(store, "error_20260824_225719", loadFixtureJson("error_20260824_225719"), loadFixturePng("error_20260824_225719"))

        assertEquals(4, store.count())

        val result = store.compactConsecutiveDuplicates()

        assertEquals(2, result.removed)
        assertEquals(2, result.remaining)
        assertEquals(2, store.count())
        assertTrue(store.listIdsOldestFirst().contains("error_20260825_100000"))
        assertTrue(store.listIdsOldestFirst().contains("error_20260824_225719"))
    }

    @Test
    fun compactKeepsDistinctViolationSetsEvenWhenAdjacent() {
        val store = ErrorCaptureStore(context)
        store.dir().listFiles()?.forEach { it.delete() }

        writeCapture(store, "error_20260825_110000", loadFixtureJson("error_20260824_233441"), loadFixturePng("error_20260824_233441"))
        writeCapture(store, "error_20260825_110030", loadFixtureJson("error_20260824_225719"), loadFixturePng("error_20260824_225719"))

        val result = store.compactConsecutiveDuplicates()

        assertEquals(0, result.removed)
        assertEquals(2, store.count())
    }

    private fun writeCapture(
        store: ErrorCaptureStore,
        id: String,
        jsonText: String,
        pngBytes: ByteArray
    ) {
        val dir = store.dir()
        File(dir, "$id.json").writeText(jsonText.replace(Regex("\"id\": \"error_[^\"]+\""), "\"id\": \"$id\""))
        FileOutputStream(File(dir, "$id.png")).use { out -> out.write(pngBytes) }
    }

    private fun loadFixtureJson(id: String): String =
        javaClass.classLoader!!.getResourceAsStream("recognition_errors/$id.json")!!
            .bufferedReader()
            .readText()

    private fun loadFixturePng(id: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("recognition_errors/$id.png")!!.readBytes()
}
