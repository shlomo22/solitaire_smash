package com.personal.solitaireassistant.vision

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Move
import com.personal.solitaireassistant.game.PileRef
import com.personal.solitaireassistant.game.Suit
import com.personal.solitaireassistant.solver.MoveGenerator
import com.personal.solitaireassistant.solver.MoveSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SmashScreenshotFixtureTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun boardGeometryMatchesSolitaireSmashLayout() {
        val bitmap = load("screenshots/device_5.png")
        val locator = BoardLocator()
        val board = locator.locate(bitmap)
        val foundations = locator.foundationRegions(board)
        val stock = locator.stockRegion(board)
        val waste = locator.wasteTopRegion(board)
        val cols = locator.tableauColumnRegions(board)

        assertTrue(foundations.size == 4 && cols.size == 7)
        assertTrue(foundations.last().right < waste.left + 20f)
        assertTrue(waste.centerX < stock.centerX)
        assertTrue(stock.left > bitmap.width * 0.80f)
        assertTrue(cols[0].top > foundations[0].bottom)
        // Foundations live below the time/score header, not in it.
        assertTrue(foundations[0].top > bitmap.height * 0.18f)
        assertTrue(cols[0].top > bitmap.height * 0.32f)
        bitmap.recycle()
    }

    @Test
    fun device5_detectsEmptyColumnAndFaceDownStack() {
        val bitmap = load("screenshots/device_5.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        assertNotNull(result.state)
        val state = result.state!!

        println(result.diagnostics.joinToString("\n"))

        // Ground truth (device_5): col1 empty; col0 face-up; col6 has face-downs.
        assertTrue(
            "expected empty col1, sizes=${state.tableau.map { it.size }}",
            state.tableau[1].isEmpty()
        )
        assertTrue(
            "expected occupied col0, sizes=${state.tableau.map { it.size }}",
            state.tableau[0].isNotEmpty() && state.tableau[0].last().faceUp
        )
        val faceDown = state.tableau.sumOf { col -> col.count { !it.faceUp } }
        assertTrue(
            "expected face-down cards, sizes=${state.tableau.map { it.size }} diag=${result.diagnostics}",
            faceDown >= 4
        )
        assertTrue("stock should be present", state.stock.isNotEmpty())
        assertTrue(
            "foundations should detect at least one face-up card, diag=${result.diagnostics}",
            state.foundations.count { it.isNotEmpty() } >= 1
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun device1_detectsOpeningDealShape() {
        val bitmap = load("screenshots/device_1.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        assertNotNull(result.board)
        assertNotNull(result.state)
        val state = result.state!!
        println(result.diagnostics.joinToString("\n"))

        // Opening deal: 0..6 face-down counts roughly 0,1,2,3,4,5,6 with face-up tops.
        assertEquals("col0 should be a single face-up", 1, state.tableau[0].size)
        assertTrue(state.tableau[0].single().faceUp)
        assertTrue(
            "col6 should be deepest, sizes=${state.tableau.map { it.size }}",
            state.tableau[6].size >= 4
        )
        // Deal shape should deepen left→right overall.
        assertTrue(
            "deal should deepen toward the right, sizes=${state.tableau.map { it.size }}",
            state.tableau[0].size <= state.tableau[3].size &&
                state.tableau[3].size <= state.tableau[6].size + 2
        )
        val total = state.tableau.sumOf { it.size }
        assertTrue("expected ~28 deal cards, got $total", total in 20..35)
        assertTrue("all foundations empty on deal", state.foundations.all { it.isEmpty() })
        assertTrue(state.stock.isNotEmpty())

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun colorAnalyzerFlagsTealBacksOnDeviceShot() {
        val bitmap = load("screenshots/device_5.png")
        val locator = BoardLocator()
        val board = locator.locate(bitmap)
        val cols = locator.tableauColumnRegions(board)
        val top = cols[6].copy(bottom = cols[6].top + cols[6].width * board.profile.cardAspect * 0.2f)
        val stats = SmashColorAnalyzer.analyze(bitmap, top)
        assertTrue(
            "expected teal back in col6 top teal=${stats.tealRatio} white=${stats.whiteRatio}",
            SmashColorAnalyzer.looksFaceDown(stats) || stats.tealRatio > 0.15f
        )
        val f0 = locator.foundationRegions(board)[0]
        val fStats = SmashColorAnalyzer.analyze(bitmap, f0)
        assertTrue(
            "foundation0 should look face-up white=${fStats.whiteRatio} red=${fStats.redInkRatio}",
            SmashColorAnalyzer.looksFaceUp(fStats)
        )
        bitmap.recycle()
    }

    @Test
    fun latestLiveBoardRecognizesVisibleRanks() {
        val bitmap = load("screenshots/device_live_2.png")
        val recognizer = CardRecognizer(context, minConfidence = 0.55f)
        val cards = listOf(
            Rank.Two to BoardRegion(5f, 223f, 69f, 313f),
            Rank.Ace to BoardRegion(72f, 223f, 136f, 313f),
            Rank.Five to BoardRegion(337f, 223f, 401f, 313f),
            Rank.Jack to BoardRegion(5f, 404f, 69f, 494f),
            Rank.King to BoardRegion(72f, 383f, 136f, 473f),
            Rank.Four to BoardRegion(139f, 404f, 203f, 494f),
            Rank.Six to BoardRegion(205f, 383f, 269f, 473f),
            Rank.Queen to BoardRegion(337f, 405f, 401f, 495f),
            Rank.Eight to BoardRegion(403f, 491f, 467f, 581f)
        )

        cards.forEach { (expected, region) ->
            val hit = recognizer.recognize(bitmap, region)
            assertEquals(
                "expected $expected in $region, diagnostic=${hit.diagnostic}",
                expected,
                hit.card?.rank
            )
        }

        recognizer.release()
        bitmap.recycle()
    }

    @Test
    fun latestLiveBoardProducesCardMoveNotStockFallback() {
        val bitmap = load("screenshots/device_live_2.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)
        println(result.diagnostics.joinToString("\n"))

        val known = state.tableau.sumOf { col -> col.count { it.faceUp && it.known } } +
            state.foundations.sumOf { pile -> pile.count { it.known } } +
            state.waste.count { it.known }
        assertTrue("expected several known ranks, diagnostics=${result.diagnostics}", known >= 5)
        val expectedTops = listOf(
            Rank.Jack,
            Rank.King,
            Rank.Four,
            Rank.Six,
            Rank.Four,
            Rank.Queen,
            Rank.Eight
        )
        assertEquals(
            "wrong tableau tops; diagnostics=${result.diagnostics}",
            expectedTops,
            state.tableau.map { it.lastOrNull()?.rank }
        )

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            "expected waste 5 onto tableau 6; got ${best.move.label}; diagnostics=${result.diagnostics}",
            Move.WasteToTableau(toColumn = 3),
            best.move
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun thirdLiveBoardRecommendsRedSevenOntoBlackEight() {
        val bitmap = load("screenshots/device_live_3.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)
        println(result.diagnostics.joinToString("\n"))

        assertEquals(
            listOf(
                Rank.Ten,
                Rank.Seven,
                Rank.Two,
                Rank.Two,
                Rank.Eight,
                Rank.Seven,
                Rank.Jack
            ),
            state.tableau.map { it.lastOrNull()?.rank }
        )
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "expected tableau 6 -> 5 (red 7 onto black 8), got ${best.move.label}",
            best.move is Move.TableauToTableau &&
                best.move.fromColumn == 5 &&
                best.move.toColumn == 4
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun fourthLiveBoardRejectsIllegalSevenOntoFive() {
        val bitmap = load("screenshots/device_live_4.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)
        println(result.diagnostics.joinToString("\n"))

        assertEquals(
            listOf(
                Rank.Ten,
                Rank.Seven,
                Rank.Two,
                Rank.Two,
                Rank.Seven,
                Rank.Jack,
                Rank.Five
            ),
            state.tableau.map { it.lastOrNull()?.rank }
        )
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            "no tableau move is legal on this board; expected stock draw",
            Move.DrawStock,
            best.move
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun fifthLiveBoardPrefersWasteAceOntoBlackTwo() {
        val bitmap = load("screenshots/device_live_5.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        assertEquals(Rank.Ace, state.waste.lastOrNull()?.rank)
        assertEquals(
            listOf(
                Rank.Jack,
                null,
                Rank.Two,
                Rank.Ten,
                Rank.Six,
                Rank.Nine,
                Rank.Six
            ),
            state.tableau.map { it.lastOrNull()?.rank }
        )
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(Move.WasteToTableau(toColumn = 2), best.move)

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun sixthLiveBoardDoesNotMoveClubTwoOntoSpadeAce() {
        val bitmap = load("screenshots/device_live_6.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        assertEquals(Suit.Diamonds, state.foundations[0].lastOrNull()?.suit)
        assertEquals(Suit.Spades, state.foundations[1].lastOrNull()?.suit)
        assertEquals(Suit.Clubs, state.tableau[2].lastOrNull()?.suit)
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "club 2 must not move onto spade A; got ${best.move.label}",
            best.move !is Move.TableauToFoundation ||
                best.move.fromColumn != 2 ||
                best.move.toFoundation != 1
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun seventhLiveBoardDoesNotMoveHeartThreeOntoDiamondTwo() {
        val bitmap = load("screenshots/device_live_7.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        assertEquals(Suit.Diamonds, state.foundations[0].lastOrNull()?.suit)
        assertEquals(Rank.Three, state.tableau[5].lastOrNull()?.rank)
        assertEquals(Suit.Hearts, state.tableau[5].lastOrNull()?.suit)
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "heart 3 must not move onto diamond 2; got ${best.move.label}",
            best.move !is Move.TableauToFoundation ||
                best.move.fromColumn != 5 ||
                best.move.toFoundation != 0
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun eighthLiveBoardMovesQueenJackRunOntoKing() {
        val bitmap = load("screenshots/device_live_8.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        assertEquals(
            listOf(Rank.Queen, Rank.Jack),
            state.tableau[4].takeLast(2).map { it.rank }
        )
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "expected Q+J from tableau 5 onto red K in tableau 6; got ${best.move.label}",
            best.move is Move.TableauToTableau &&
                best.move.fromColumn == 4 &&
                best.move.toColumn == 5 &&
                state.tableau[4][best.move.startIndex].rank == Rank.Queen
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun ninthLiveBoardMovesEightSevenRunOntoNine() {
        val bitmap = load("screenshots/device_live_9.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "expected 8-7 from tableau 4 onto red 9 in tableau 6; got ${best.move.label}",
            best.move is Move.TableauToTableau &&
                best.move.fromColumn == 3 &&
                best.move.toColumn == 5 &&
                state.tableau[3][best.move.startIndex].rank == Rank.Eight
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun tenthLiveBoardMovesNineThroughFourOntoBlackTen() {
        val bitmap = load("screenshots/device_live_10.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        assertEquals(
            listOf(Rank.Nine, Rank.Eight, Rank.Seven, Rank.Six, Rank.Five, Rank.Four),
            state.tableau[5].takeLast(6).map { it.rank }
        )
        assertEquals(Suit.Diamonds, state.foundations[0].lastOrNull()?.suit)
        assertEquals(Suit.Diamonds, state.tableau[4].lastOrNull()?.suit)
        val legalMoves = MoveGenerator.generate(state)
        assertTrue(
            "9-4 onto black 10 should be legal",
            legalMoves.filterIsInstance<Move.TableauToTableau>().any { move ->
                move.fromColumn == 5 &&
                    move.toColumn == 3 &&
                    state.tableau[5][move.startIndex].rank == Rank.Nine
            }
        )
        assertTrue(
            "2 of diamonds onto ace of diamonds should be legal",
            legalMoves.contains(Move.TableauToFoundation(fromColumn = 4, toFoundation = 0))
        )
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            "safe 2D foundation move also reveals a card and should rank first",
            Move.TableauToFoundation(fromColumn = 4, toFoundation = 0),
            best.move
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun deepFourIsNotMisreadAsFoundationAce() {
        val bitmap = load("screenshots/device_live_11.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(Rank.Four, state.tableau[6].lastOrNull()?.rank)
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "4 must not be sent to an empty foundation; got ${best.move.label}",
            best.move !is Move.TableauToFoundation || best.move.fromColumn != 6
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun deepThreeIsNotMisreadAsFoundationAce() {
        val bitmap = load("screenshots/device_live_12.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(Rank.Three, state.tableau[6].lastOrNull()?.rank)
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "3 without its matching 2 must not move to foundation; got ${best.move.label}",
            best.move !is Move.TableauToFoundation || best.move.fromColumn != 6
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun thirteenthLiveBoardMovesNineRunOntoRedTen() {
        val bitmap = load("screenshots/device_live_13.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        assertEquals(
            listOf(Rank.Nine, Rank.Eight, Rank.Seven, Rank.Six),
            state.tableau[3].takeLast(4).map { it.rank }
        )
        assertEquals(
            listOf(Rank.Nine, Rank.Eight, Rank.Seven, Rank.Six, Rank.Five),
            state.tableau[4].takeLast(5).map { it.rank }
        )
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "expected one of the black 9 runs onto a red 10; got ${best.move.label}",
            best.move is Move.TableauToTableau &&
                best.move.fromColumn in setOf(3, 4) &&
                best.move.toColumn in setOf(0, 2) &&
                state.tableau[best.move.fromColumn][best.move.startIndex].rank == Rank.Nine
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun fourteenthLiveBoardDoesNotMoveRedSixOntoRedSeven() {
        val bitmap = load("screenshots/device_live_14.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        assertEquals(result.diagnostics.joinToString("\n"), Rank.Six, state.wasteTop()?.rank)
        assertEquals(Suit.Diamonds, state.wasteTop()?.suit)
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            "the exposed 3S should advance onto the 2S foundation",
            Move.TableauToFoundation(fromColumn = 1, toFoundation = 1),
            best.move
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun fullCardTemplateDistinguishesTenFromNine() {
        val bitmap = load("screenshots/Screenshot_20260811_144901_Solitaire Smash.jpg")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val bounds = requireNotNull(
            result.locations[PileRef.Tableau(0)]?.lastOrNull()?.bounds
        )
        val recognizer = CardRecognizer(context, minConfidence = 0.55f)

        val scores = recognizer.exactRankTemplateScores(
            bitmap,
            bounds,
            setOf(Rank.Nine, Rank.Ten)
        )
        assertTrue(
            "10 template must beat 9 for the two-character glyph; scores=$scores",
            (scores[Rank.Ten] ?: 0f) > (scores[Rank.Nine] ?: 0f)
        )

        recognizer.release()
        detector.release()
        bitmap.recycle()
    }

    @Test
    fun fifteenthLiveBoardAvoidsNonRevealingJackRun() {
        val bitmap = load("screenshots/device_live_15.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        val best = requireNotNull(MoveSelector.bestMove(state))
        assertEquals(
            "prefer the move that creates an empty column over shifting J-4 onto Q",
            Move.TableauToTableau(fromColumn = 3, startIndex = 0, toColumn = 4),
            best.move
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun sixteenthLiveBoardDoesNotInferKingUnderFaceDownCard() {
        val bitmap = load("screenshots/device_live_16.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        assertEquals(
            "only the queen is exposed in tableau 7; ${result.diagnostics}",
            listOf(Rank.Queen),
            state.tableau[6].filter { it.faceUp }.map { it.rank }
        )
        assertTrue(
            "a queen cannot move to empty tableau 2",
            MoveGenerator.generate(state).filterIsInstance<Move.TableauToTableau>().none {
                it.fromColumn == 6 && it.toColumn == 1
            }
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun twentyFirstLiveBoardDoesNotInventKingAboveQueen() {
        val bitmap = load("screenshots/device_live_21.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(
            listOf(Rank.Queen),
            state.tableau[6].filter { it.faceUp }.map { it.rank }
        )
        assertTrue(
            MoveGenerator.generate(state).filterIsInstance<Move.TableauToTableau>().none {
                it.fromColumn == 6 && it.toColumn == 0
            }
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun twentySecondLiveBoardKeepsExposedKingAboveQueenRun() {
        val bitmap = load("screenshots/device_live_22.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        assertEquals(
            result.diagnostics.joinToString("\n"),
            Rank.King,
            state.tableau[0].firstOrNull { it.faceUp }?.rank
        )
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            "moving Q-run onto K reveals nothing and leaves K behind; got ${best.move.label}",
            best.move !is Move.TableauToTableau ||
                best.move.fromColumn != 0 ||
                best.move.toColumn != 1
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun twentyThirdLiveBoardRecognizesClubFive() {
        val bitmap = load("screenshots/device_live_23.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)
        val recognizer = CardRecognizer(context, minConfidence = 0.55f)
        val bounds = requireNotNull(result.locations[PileRef.Tableau(6)]?.lastOrNull()?.bounds)
        val scores = recognizer.suitTemplateScores(
            bitmap,
            bounds,
            setOf(Suit.Clubs, Suit.Spades)
        )

        assertEquals("scores=$scores", Suit.Clubs, state.tableau[6].lastOrNull()?.suit)
        assertTrue(
            MoveGenerator.generate(state).none {
                it == Move.TableauToFoundation(fromColumn = 6, toFoundation = 3)
            }
        )

        recognizer.release()
        detector.release()
        bitmap.recycle()
    }

    @Test
    fun twentyFourthLiveBoardRecognizesClubTwo() {
        val bitmap = load("screenshots/device_live_24.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)
        val recognizer = CardRecognizer(context, minConfidence = 0.55f)
        val bounds = requireNotNull(result.locations[PileRef.Tableau(6)]?.lastOrNull()?.bounds)
        val scores = recognizer.suitTemplateScores(
            bitmap,
            bounds,
            setOf(Suit.Clubs, Suit.Spades)
        )

        assertEquals("scores=$scores", Suit.Clubs, state.tableau[6].lastOrNull()?.suit)
        assertTrue(
            MoveGenerator.generate(state).none {
                it == Move.TableauToFoundation(fromColumn = 6, toFoundation = 2)
            }
        )

        recognizer.release()
        detector.release()
        bitmap.recycle()
    }

    @Test
    fun twentyFifthLiveBoardRecognizesWasteJack() {
        val bitmap = load("screenshots/device_live_25.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)
        val recognizer = CardRecognizer(context, minConfidence = 0.55f)
        val wasteBounds = requireNotNull(result.locations[PileRef.Waste]?.lastOrNull()?.bounds)
        val rankScores = recognizer.exactRankTemplateScores(
            bitmap,
            wasteBounds,
            setOf(Rank.Seven, Rank.Jack)
        )

        assertEquals(result.diagnostics.joinToString("\n"), Rank.Jack, state.wasteTop()?.rank)
        assertTrue(
            "Jack must clearly beat Seven; scores=$rankScores",
            (rankScores[Rank.Jack] ?: 0f) > (rankScores[Rank.Seven] ?: 0f) + 0.35f
        )
        assertTrue(MoveSelector.bestMove(state)?.move !is Move.WasteToFoundation)

        recognizer.release()
        detector.release()
        bitmap.recycle()
    }

    @Test
    fun confirmedWasteSevenDoesNotMatchJack() {
        val bitmap = load("screenshots/Screenshot_20260811_145050_Solitaire Smash.jpg")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val recognizer = CardRecognizer(context, minConfidence = 0.55f)
        val wasteBounds = requireNotNull(result.locations[PileRef.Waste]?.lastOrNull()?.bounds)
        val scores = recognizer.exactRankTemplateScores(
            bitmap,
            wasteBounds,
            setOf(Rank.Seven, Rank.Jack)
        )

        assertTrue(
            "Seven must not strongly match Jack; scores=$scores",
            (scores[Rank.Jack] ?: 0f) < (scores[Rank.Seven] ?: 0f) + 0.35f
        )

        recognizer.release()
        detector.release()
        bitmap.recycle()
    }

    @Test
    fun twentySixthLiveBoardRecognizesWasteDiamondFive() {
        val bitmap = load("screenshots/device_live_26.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val result = detector.detect(bitmap)
        val state = requireNotNull(result.state)

        assertEquals(result.diagnostics.joinToString("\n"), Rank.Five, state.wasteTop()?.rank)
        assertEquals(Suit.Diamonds, state.wasteTop()?.suit)
        assertTrue(MoveSelector.bestMove(state)?.move !is Move.WasteToFoundation)

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun twentySeventhLiveBoardRecognizesWasteClubNine() {
        val bitmap = load("screenshots/device_live_27.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(Rank.Nine, state.wasteTop()?.rank)
        assertEquals(Suit.Clubs, state.wasteTop()?.suit)
        assertTrue(MoveSelector.bestMove(state)?.move !is Move.WasteToFoundation)

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun twentyEighthLiveBoardDoesNotInventFiveAboveFour() {
        val bitmap = load("screenshots/device_live_28.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(
            listOf(Rank.Four),
            state.tableau[6].filter { it.faceUp }.map { it.rank }
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun twentyNinthLiveBoardRecognizesWasteHeartEight() {
        val bitmap = load("screenshots/device_live_29.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(Rank.Eight, state.wasteTop()?.rank)
        assertEquals(Suit.Hearts, state.wasteTop()?.suit)

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun thirtiethLiveBoardKeepsKingAboveQueenRun() {
        val bitmap = load("screenshots/device_live_30.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(Rank.King, state.tableau[0].firstOrNull { it.faceUp }?.rank)
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            best.move !is Move.TableauToTableau ||
                best.move.fromColumn != 0 ||
                best.move.toColumn != 1
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun thirtyFirstLiveBoardKeepsKingAboveQueenRun() {
        val bitmap = load("screenshots/device_live_31.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(Rank.King, state.tableau[4].firstOrNull { it.faceUp }?.rank)
        val best = requireNotNull(MoveSelector.bestMove(state))
        assertTrue(
            best.move !is Move.TableauToTableau ||
                best.move.fromColumn != 4 ||
                best.move.toColumn != 1
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun thirtySecondLiveBoardRecognizesWasteDiamondFour() {
        val bitmap = load("screenshots/device_live_32.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val detection = detector.detect(bitmap)
        val state = requireNotNull(detection.state)

        assertEquals(detection.diagnostics.joinToString("\n"), Rank.Four, state.wasteTop()?.rank)
        assertEquals(detection.diagnostics.joinToString("\n"), Suit.Diamonds, state.wasteTop()?.suit)

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun thirtyThirdLiveBoardDoesNotInventKingAboveQueen() {
        val bitmap = load("screenshots/device_live_33.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(
            listOf(Rank.Queen, Rank.Jack, Rank.Ten),
            state.tableau[2].filter { it.faceUp }.map { it.rank }
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun thirtyFourthLiveBoardKeepsKingAndQueenAboveLongRun() {
        val bitmap = load("screenshots/device_live_34.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(
            listOf(Rank.King, Rank.Queen),
            state.tableau[6].filter { it.faceUp }.take(2).map { it.rank }
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun thirtyFifthLiveBoardDoesNotInventSixAboveFive() {
        val bitmap = load("screenshots/device_live_35.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)

        assertEquals(
            listOf(Rank.Five),
            state.tableau[5].filter { it.faceUp }.map { it.rank }
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun latestBlackSuitBoardsKeepClubsOutOfSpadeFoundations() {
        data class Fixture(
            val path: String,
            val column: Int,
            val rank: Rank,
            val suit: Suit,
            val allowFoundation: Boolean
        )

        listOf(
            Fixture("screenshots/device_live_36.png", 2, Rank.Four, Suit.Clubs, false),
            Fixture("screenshots/device_live_37.png", 4, Rank.Four, Suit.Spades, true),
            Fixture("screenshots/device_live_38.png", 6, Rank.Three, Suit.Clubs, false)
        ).forEach { fixture ->
            val bitmap = load(fixture.path)
            val detector = GameStateDetector(context, minConfidence = 0.55f)
            val detection = detector.detect(bitmap)
            val state = requireNotNull(detection.state)
            val card = state.tableau[fixture.column].last()
            val best = MoveSelector.bestMove(state)?.move

            assertEquals(fixture.path, fixture.rank, card.rank)
            assertEquals(fixture.path, fixture.suit, card.suit)
            if (!fixture.allowFoundation) {
                assertTrue(
                    fixture.path,
                    best !is Move.TableauToFoundation ||
                        best.fromColumn != fixture.column
                )
            }

            detector.release()
            bitmap.recycle()
        }
    }

    @Test
    fun fortyFirstLiveBoardDoesNotMoveSevenOntoNineAsEight() {
        val bitmap = load("screenshots/device_live_41.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val detection = detector.detect(bitmap)
        val state = requireNotNull(detection.state)
        val seven = state.tableau[2].last()
        val nine = state.tableau[4].last()
        val best = MoveSelector.bestMove(state)?.move

        assertEquals(Rank.Seven, seven.rank)
        assertEquals(Suit.Hearts, seven.suit)
        assertEquals(Rank.Nine, nine.rank)
        assertEquals(Suit.Clubs, nine.suit)
        assertTrue(
            best !is Move.TableauToTableau ||
                best.fromColumn != 2 ||
                best.toColumn != 4
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun fortiethLiveBoardDoesNotMoveSpadeTwoOntoClubAce() {
        val bitmap = load("screenshots/device_live_40.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val detection = detector.detect(bitmap)
        val state = requireNotNull(detection.state)
        val card = state.tableau[4].last()
        val foundation = state.foundations[1].lastOrNull()
        val best = MoveSelector.bestMove(state)?.move

        assertEquals(Rank.Two, card.rank)
        assertTrue(
            "2 must stay Spades or be ambiguous, got $card",
            card.suit == Suit.Spades || card.suitAmbiguous
        )
        assertEquals(Rank.Ace, foundation?.rank)
        assertEquals(Suit.Clubs, foundation?.suit)
        assertTrue(
            best !is Move.TableauToFoundation ||
                best.fromColumn != 4 ||
                best.toFoundation != 1
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun thirtyNinthLiveBoardDoesNotMoveBlackTenOntoBlackQueen() {
        val bitmap = load("screenshots/device_live_39.png")
        val detector = GameStateDetector(context, minConfidence = 0.55f)
        val state = requireNotNull(detector.detect(bitmap).state)
        val best = MoveSelector.bestMove(state)?.move

        assertEquals(Rank.Queen, state.tableau[3].last().rank)
        assertEquals(Suit.Spades, state.tableau[3].last().suit)
        assertEquals(Rank.Ten, state.tableau[6].last().rank)
        assertEquals(Suit.Spades, state.tableau[6].last().suit)
        assertTrue(
            best !is Move.TableauToTableau ||
                best.fromColumn != 6 ||
                best.toColumn != 3
        )

        detector.release()
        bitmap.recycle()
    }

    @Test
    fun latestIssueScreenshotsRejectFalseMoves() {
        fun check(index: Int, assertion: (com.personal.solitaireassistant.game.GameState, Move?) -> Unit) {
            val path = "screenshots/issue_unknown_${index.toString().padStart(2, '0')}.png"
            val bitmap = load(path)
            val detector = GameStateDetector(context, minConfidence = 0.55f)
            val detection = detector.detect(bitmap)
            val state = requireNotNull(detection.state)
            assertion(state, MoveSelector.bestMove(state)?.move)
            detector.release()
            bitmap.recycle()
        }

        check(1) { state, best ->
            assertEquals(Rank.King, state.tableau[1].firstOrNull { it.faceUp }?.rank)
            assertTrue(
                best !is Move.TableauToTableau ||
                    best.fromColumn != 1 ||
                    best.toColumn != 0
            )
        }
        check(6) { _, best ->
            assertTrue(best !is Move.TableauToFoundation || best.fromColumn != 1)
        }
        check(12) { state, best ->
            assertEquals(Rank.Ten, state.wasteTop()?.rank)
            assertEquals(Suit.Diamonds, state.wasteTop()?.suit)
            assertTrue(best !is Move.WasteToTableau)
        }
        check(15) { state, best ->
            assertEquals(Rank.Queen, state.wasteTop()?.rank)
            assertEquals(Suit.Spades, state.wasteTop()?.suit)
            assertTrue(best !is Move.WasteToTableau)
        }
        check(18) { state, best ->
            assertEquals(
                listOf(Rank.Jack, Rank.Ten, Rank.Nine, Rank.Eight, Rank.Seven),
                state.tableau[3].filter { it.faceUp }.map { it.rank }
            )
            assertTrue(
                best !is Move.TableauToTableau ||
                    best.fromColumn != 3 ||
                    best.toColumn != 1
            )
        }
        check(19) { _, best ->
            assertTrue(best !is Move.TableauToFoundation || best.fromColumn != 4)
        }
        check(20) { _, best ->
            assertTrue(best !is Move.TableauToFoundation || best.fromColumn != 1)
        }
        check(23) { state, best ->
            assertEquals(Rank.Eight, state.wasteTop()?.rank)
            assertEquals(Suit.Spades, state.wasteTop()?.suit)
            assertTrue(best !is Move.WasteToFoundation)
        }
    }

    @Test
    fun confirmedGroundTruthBoardsRecognizePlayableRanks() {
        val failures = mutableListOf<String>()
        val boards = listOf(
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_144901_Solitaire Smash.jpg",
                foundations = listOf(null, null, null, null),
                waste = null,
                tableau = listOf(
                    Rank.Ten,
                    Rank.Seven,
                    Rank.Two,
                    Rank.Two,
                    Rank.Seven,
                    Rank.Jack,
                    Rank.Five
                )
            ),
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_135516_Solitaire Smash.jpg",
                foundations = listOf(Rank.Two, Rank.Ace, null, null),
                waste = null,
                tableau = listOf(
                    null,
                    Rank.Jack,
                    Rank.Ten,
                    Rank.Six,
                    Rank.Seven,
                    Rank.Nine,
                    Rank.Queen
                )
            ),
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_114557_Solitaire Smash.jpg",
                foundations = listOf(Rank.Two, Rank.Two, null, null),
                waste = Rank.Five,
                tableau = listOf(
                    null,
                    Rank.Jack,
                    Rank.Ten,
                    null,
                    Rank.Eight,
                    Rank.Four,
                    Rank.Ten
                )
            ),
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_143048_Solitaire Smash.jpg",
                foundations = listOf(Rank.Two, Rank.Ace, null, null),
                waste = Rank.Five,
                tableau = listOf(
                    Rank.Jack,
                    Rank.King,
                    Rank.Four,
                    Rank.Six,
                    Rank.Four,
                    Rank.Queen,
                    Rank.Eight
                )
            ),
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_144944_Solitaire Smash.jpg",
                foundations = listOf(Rank.Two, Rank.Ace, null, null),
                waste = Rank.Three,
                tableau = listOf(
                    Rank.Nine,
                    Rank.Six,
                    Rank.Nine,
                    Rank.Two,
                    Rank.Seven,
                    Rank.Ten,
                    Rank.Four
                )
            ),
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_143208_Solitaire Smash.jpg",
                foundations = listOf(Rank.Two, Rank.Ace, null, null),
                waste = Rank.Five,
                tableau = listOf(
                    Rank.Jack,
                    Rank.King,
                    Rank.Four,
                    Rank.Six,
                    Rank.Four,
                    Rank.Queen,
                    Rank.Eight
                )
            ),
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_145006_Solitaire Smash.jpg",
                foundations = listOf(Rank.Two, Rank.Ace, Rank.Ace, null),
                waste = Rank.King,
                tableau = listOf(
                    Rank.Nine,
                    Rank.Five,
                    Rank.Nine,
                    Rank.Two,
                    Rank.Seven,
                    Rank.Ten,
                    Rank.Four
                )
            ),
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_145024_Solitaire Smash.jpg",
                foundations = listOf(Rank.Two, Rank.Two, Rank.Ace, null),
                waste = Rank.Three,
                tableau = listOf(
                    Rank.Nine,
                    Rank.Four,
                    Rank.Nine,
                    Rank.Two,
                    Rank.King,
                    Rank.Seven,
                    Rank.Four
                )
            ),
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_145050_Solitaire Smash.jpg",
                foundations = listOf(Rank.Two, Rank.Two, Rank.Ace, Rank.Ace),
                waste = Rank.Seven,
                tableau = listOf(
                    Rank.Nine,
                    Rank.Two,
                    Rank.Eight,
                    Rank.Jack,
                    Rank.King,
                    Rank.Six,
                    Rank.Four
                )
            ),
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_145037_Solitaire Smash.jpg",
                foundations = listOf(Rank.Two, Rank.Two, Rank.Ace, Rank.Ace),
                waste = Rank.Three,
                tableau = listOf(
                    Rank.Nine,
                    Rank.Four,
                    Rank.Eight,
                    Rank.Two,
                    Rank.King,
                    Rank.Six,
                    Rank.Four
                )
            ),
            ConfirmedBoard(
                path = "screenshots/Screenshot_20260811_145121_Solitaire Smash.jpg",
                foundations = listOf(Rank.Two, Rank.Three, Rank.Ace, Rank.Two),
                waste = Rank.Seven,
                tableau = listOf(
                    Rank.Eight,
                    Rank.Three,
                    Rank.Nine,
                    Rank.Jack,
                    Rank.Six,
                    Rank.Jack,
                    Rank.Four
                )
            )
        )

        boards.forEach { expected ->
            val bitmap = load(expected.path)
            val detector = GameStateDetector(context, minConfidence = 0.55f)
            val result = detector.detect(bitmap)
            val state = requireNotNull(result.state)
            val actualFoundations = state.foundations.map { it.lastOrNull()?.rank }
            val actualWaste = state.waste.lastOrNull()?.rank
            val actualTableau = state.tableau.map { it.lastOrNull()?.rank }
            if (expected.foundations != actualFoundations) {
                failures += "${expected.path} foundations expected=${expected.foundations} actual=$actualFoundations"
            }
            if (expected.waste != actualWaste) {
                failures += "${expected.path} waste expected=${expected.waste} actual=$actualWaste"
            }
            if (expected.tableau != actualTableau) {
                failures += "${expected.path} tableau expected=${expected.tableau} actual=$actualTableau " +
                    "diagnostics=${result.diagnostics.filter { it.startsWith("tableau") }}"
            }
            detector.release()
            bitmap.recycle()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private data class ConfirmedBoard(
        val path: String,
        val foundations: List<Rank?>,
        val waste: Rank?,
        val tableau: List<Rank?>
    )

    private fun load(path: String) =
        javaClass.classLoader!!.getResourceAsStream(path).use { stream ->
            assertNotNull("missing $path", stream)
            BitmapFactory.decodeStream(stream)!!.also {
                assertTrue(it.width > 100 && it.height > 100)
            }
        }
}
