package com.personal.solitaireassistant.vision

import android.content.Context
import android.graphics.Bitmap
import com.personal.solitaireassistant.game.Card
import com.personal.solitaireassistant.game.GameState
import com.personal.solitaireassistant.game.MoveTransitionDescriber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves one screenshot + a plain-text board-state summary per confirmed
 * move across a whole game, so a game that ended stuck (including one the
 * user gave up on) can be pulled afterward and replayed move-by-move to
 * check whether a different line earlier on would have won.
 *
 * One subfolder per deal (started on [newSession]), files named by move
 * index within that deal: 0000.png/0000.txt is the opening deal, 0001 is
 * after the first move, and so on. Unlike [ErrorCaptureStore] this is not
 * about recognition mistakes - it is a full move-by-move record for a human
 * (or a future offline solver run) to look for a better line, so it saves
 * every confirmed move regardless of recognition confidence.
 *
 * Also appends one line per move to a cumulative `moves.log` in the same
 * subfolder, describing what changed since the previous confirmed move
 * ([MoveTransitionDescriber]) - a chronological summary that's much faster
 * to review than opening every NNNN.txt pair by hand. It is still derived
 * from the same per-frame recognition as the snapshots, so it inherits
 * whatever misreads are already in them; see [MoveTransitionDescriber]'s
 * own doc comment for what it can and can't infer (e.g. it only ever sees
 * the top card of stock/waste/foundation piles, never the full pile order).
 *
 * Path: files/move_history/<deal-timestamp>/
 * Pull with:
 * adb exec-out run-as com.personal.solitaireassistant sh -c 'cd files/move_history && tar cf - .' > move_history.tar
 */
class MoveHistoryStore(context: Context) {
    private val filesDir = context.applicationContext.filesDir
    private val timeFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var sessionDir: File? = null
    private var moveIndex = 0

    fun rootDir(): File = File(filesDir, "move_history").also { it.mkdirs() }

    fun pathForDisplay(): String = rootDir().absolutePath

    /** Starts a fresh subfolder for a new deal. Call when a new game is detected. */
    fun newSession() {
        sessionDir = File(rootDir(), timeFmt.format(Date())).also { it.mkdirs() }
        moveIndex = 0
    }

    /**
     * Saves the current frame + a text summary of [state] as the next move
     * in the current session, lazily starting one if [newSession] was never
     * called (covers the very first game after the assistant starts).
     * [previous] is the last state this store recorded (null for the very
     * first move of a session) and is used only to append a `moves.log`
     * line - the per-move snapshot/summary files don't depend on it.
     */
    fun record(bitmap: Bitmap, state: GameState, previous: GameState?) {
        val dir = sessionDir ?: File(rootDir(), timeFmt.format(Date())).also {
            it.mkdirs()
            sessionDir = it
        }
        val name = "%04d".format(moveIndex)
        moveIndex++
        val png = File(dir, "$name.png")
        FileOutputStream(png).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        File(dir, "$name.txt").writeText(describe(state))
        val transition = MoveTransitionDescriber.describe(previous, state)
        File(dir, "moves.log").appendText("$name $transition\n")
    }

    private fun describe(state: GameState): String = buildString {
        appendLine("stock: ${state.stock.size} cards")
        appendLine("waste: ${state.waste.joinToString(" ") { describeCard(it) }}")
        state.foundations.forEachIndexed { index, pile ->
            appendLine("foundation$index: ${pile.lastOrNull()?.let { describeCard(it) } ?: "-"}")
        }
        state.tableau.forEachIndexed { index, column ->
            appendLine("tableau$index: ${column.joinToString(" ") { describeCard(it) }}")
        }
    }

    /**
     * Unambiguous per-card text: [Card.toString] abbreviates rank to one
     * letter (Ten/Two/Three all collide on 'T'), which is fine for a quick
     * debug log line but not for a record meant to be replayed accurately.
     */
    private fun describeCard(card: Card): String = when {
        !card.faceUp -> "??"
        !card.known -> "?unread"
        else -> card.id +
            (if (card.suitAmbiguous) "~" else "") +
            (if (card.inferred) "*" else "")
    }
}
