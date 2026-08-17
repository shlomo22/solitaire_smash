package com.personal.solitaireassistant.vision

import com.personal.solitaireassistant.game.BoardRegion
import com.personal.solitaireassistant.game.Rank
import com.personal.solitaireassistant.game.Suit
import org.json.JSONArray
import org.json.JSONObject

data class GoldenSample(
    val id: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val slots: List<GoldenSlot>
)

data class GoldenSlot(
    val pile: String,
    val index: Int,
    val bounds: BoardRegion,
    val engine: SlotGuess,
    val truth: SlotGuess,
    val inferred: Boolean = false
)

data class RejectionMeta(
    val moveLabel: String,
    val fingerprint: String,
    val from: BoardRegion?,
    val to: BoardRegion?
)

object GoldenTruthJson {
    fun toJson(sample: GoldenSample, rejection: RejectionMeta? = null): String {
        val root = JSONObject()
        root.put("id", sample.id)
        root.put(
            "frameSize",
            JSONObject()
                .put("w", sample.frameWidth)
                .put("h", sample.frameHeight)
        )
        rejection?.let { meta ->
            root.put("rejectedMove", meta.moveLabel)
            root.put("fingerprint", meta.fingerprint)
            meta.from?.let { root.put("arrowFrom", boundsJson(it)) }
            meta.to?.let { root.put("arrowTo", boundsJson(it)) }
        }
        val slots = JSONArray()
        sample.slots.forEach { slot ->
            slots.put(
                JSONObject()
                    .put("pile", slot.pile)
                    .put("index", slot.index)
                    .put("inferred", slot.inferred)
                    .put("bounds", boundsJson(slot.bounds))
                    .put("engine", guessJson(slot.engine))
                    .put("truth", guessJson(slot.truth))
            )
        }
        root.put("slots", slots)
        return root.toString(2)
    }

    fun fromJson(text: String): GoldenSample {
        val root = JSONObject(text)
        val size = root.getJSONObject("frameSize")
        val slotsJson = root.getJSONArray("slots")
        val slots = buildList {
            for (i in 0 until slotsJson.length()) {
                val obj = slotsJson.getJSONObject(i)
                val bounds = obj.getJSONObject("bounds")
                add(
                    GoldenSlot(
                        pile = obj.getString("pile"),
                        index = obj.getInt("index"),
                        bounds = BoardRegion(
                            left = bounds.getDouble("l").toFloat(),
                            top = bounds.getDouble("t").toFloat(),
                            right = bounds.getDouble("r").toFloat(),
                            bottom = bounds.getDouble("b").toFloat()
                        ),
                        engine = guessFromJson(obj.getJSONObject("engine")),
                        truth = guessFromJson(obj.getJSONObject("truth")),
                        inferred = obj.optBoolean("inferred", false)
                    )
                )
            }
        }
        return GoldenSample(
            id = root.getString("id"),
            frameWidth = size.getInt("w"),
            frameHeight = size.getInt("h"),
            slots = slots
        )
    }

    private fun boundsJson(bounds: BoardRegion): JSONObject =
        JSONObject()
            .put("l", bounds.left.toDouble())
            .put("t", bounds.top.toDouble())
            .put("r", bounds.right.toDouble())
            .put("b", bounds.bottom.toDouble())

    private fun guessJson(guess: SlotGuess): JSONObject {
        val obj = JSONObject().put("kind", guess.kind.name)
        guess.rank?.let { obj.put("rank", it.name) }
        guess.suit?.let { obj.put("suit", it.name) }
        if (guess.suitAmbiguous) obj.put("suitAmbiguous", true)
        return obj
    }

    private fun guessFromJson(obj: JSONObject): SlotGuess {
        val kind = SlotKind.valueOf(obj.getString("kind"))
        val rank = obj.optString("rank", "").takeIf { it.isNotBlank() }?.let { Rank.valueOf(it) }
        val suit = obj.optString("suit", "").takeIf { it.isNotBlank() }?.let { Suit.valueOf(it) }
        return SlotGuess(
            kind = kind,
            rank = rank,
            suit = suit,
            suitAmbiguous = obj.optBoolean("suitAmbiguous", false)
        )
    }
}

fun RecognizedSlot.toGoldenSlot(truth: SlotGuess = engine): GoldenSlot = GoldenSlot(
    pile = pileRefKey(pile),
    index = index,
    bounds = bounds,
    engine = engine,
    truth = truth,
    inferred = inferred
)
