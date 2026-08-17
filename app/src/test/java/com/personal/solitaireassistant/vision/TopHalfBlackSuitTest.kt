package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TopHalfBlackSuitTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun topHalfMarginBeatsFullMarginOnKnownThinClubBadges() {
        val fixture = loadGolden("20260814_205456") ?: return
        val (sample, bitmap) = fixture
        val recognizer = CardRecognizer(context)
        try {
            listOf(
                slotKey("tableau:0", 2),
                slotKey("tableau:1", 4),
                slotKey("tableau:4", 4),
                slotKey("tableau:6", 7)
            ).forEach { key ->
                val slot = sample.slots.firstOrNull {
                    it.pile == key.first &&
                        it.index == key.second &&
                        !it.inferred &&
                        it.truth.kind == SlotKind.FaceUp &&
                        it.truth.suit == Suit.Clubs
                } ?: error("missing club slot $key in ${sample.id}")
                val scores = recognizer.blackSuitTemplateScores(bitmap, slot.bounds)
                val cropLeft = slot.bounds.left.toInt().coerceIn(0, bitmap.width - 1)
                val cropTop = slot.bounds.top.toInt().coerceIn(0, bitmap.height - 1)
                val cropRight = slot.bounds.right.toInt().coerceIn(cropLeft + 1, bitmap.width)
                val cropBottom = slot.bounds.bottom.toInt().coerceIn(cropTop + 1, bitmap.height)
                val crop = android.graphics.Bitmap.createBitmap(
                    bitmap,
                    cropLeft,
                    cropTop,
                    cropRight - cropLeft,
                    cropBottom - cropTop
                )
                val tiebreakShape = try {
                    SuitBadgeHeuristics.guessBlackSuit(
                        crop,
                        CardRecognizer.TOP_BLACK_SHAPE_VETO_MARGIN
                    )
                } finally {
                    crop.recycle()
                }
                assertTrue(
                    "${sample.id} ${slot.pile} full picks spades on a club badge",
                    scores.fullSpade > scores.fullClub
                )
                assertTrue(
                    "${sample.id} ${slot.pile} topMargin=${scores.topMargin} fullMargin=${scores.fullMargin}",
                    scores.topMargin > scores.fullMargin
                )
                val leaderCrop = android.graphics.Bitmap.createBitmap(
                    bitmap,
                    cropLeft,
                    cropTop,
                    cropRight - cropLeft,
                    cropBottom - cropTop
                )
                val leader = try {
                    recognizer.resolveBlackSuitLeader(scores, leaderCrop, tiebreakShape).first
                } finally {
                    leaderCrop.recycle()
                }
                assertEquals(
                    "${sample.id} ${slot.pile} shape=${tiebreakShape?.suit}@${tiebreakShape?.margin}",
                    Suit.Clubs,
                    leader
                )
            }
        } finally {
            recognizer.release()
            bitmap.recycle()
        }
    }

    @Test
    fun topHalfMarginBeatsFullMarginOn233444NineOfSpades() {
        val fixture = loadGolden("20260814_233444") ?: return
        val (sample, bitmap) = fixture
        val recognizer = CardRecognizer(context)
        try {
            val slot = sample.slots.first {
                it.pile == "tableau:1" &&
                    it.index == 4 &&
                    !it.inferred &&
                    it.truth.rank == Rank.Nine &&
                    it.truth.suit == Suit.Spades
            }
            val scores = recognizer.blackSuitTemplateScores(bitmap, slot.bounds)
            assertTrue(
                "${sample.id} ${slot.pile} fullMargin=${scores.fullMargin}",
                scores.fullMargin < CardRecognizer.BLACK_SUIT_TOP_TIEBREAK_MAX
            )
            assertTrue(
                "${sample.id} ${slot.pile} topMargin=${scores.topMargin} fullMargin=${scores.fullMargin}",
                scores.topMargin > scores.fullMargin
            )
            val cropLeft = slot.bounds.left.toInt().coerceIn(0, bitmap.width - 1)
            val cropTop = slot.bounds.top.toInt().coerceIn(0, bitmap.height - 1)
            val cropRight = slot.bounds.right.toInt().coerceIn(cropLeft + 1, bitmap.width)
            val cropBottom = slot.bounds.bottom.toInt().coerceIn(cropTop + 1, bitmap.height)
            val leaderCrop = android.graphics.Bitmap.createBitmap(
                bitmap,
                cropLeft,
                cropTop,
                cropRight - cropLeft,
                cropBottom - cropTop
            )
            val leader = try {
                recognizer.resolveBlackSuitLeader(scores, leaderCrop, null).first
            } finally {
                leaderCrop.recycle()
            }
            assertEquals(
                "${sample.id} ${slot.pile} should stay spades on sharp tip",
                Suit.Spades,
                leader
            )
        } finally {
            recognizer.release()
            bitmap.recycle()
        }
    }

    private fun slotKey(pile: String, index: Int) = pile to index

    private fun loadGolden(id: String): Pair<GoldenSample, android.graphics.Bitmap>? {
        val jsonStream = javaClass.classLoader!!.getResourceAsStream("golden/$id.json")
        val pngStream = javaClass.classLoader!!.getResourceAsStream("golden/$id.png")
        if (jsonStream == null || pngStream == null) {
            jsonStream?.close()
            pngStream?.close()
            return null
        }
        val sample = jsonStream.bufferedReader().use { GoldenTruthJson.fromJson(it.readText()) }
        val bitmap = pngStream.use { BitmapFactory.decodeStream(it)!! }
        return sample to bitmap
    }
}
