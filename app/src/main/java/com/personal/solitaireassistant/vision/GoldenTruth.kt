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
    val inferred: Boolean = false,
    val confidence: Float? = null,
    val diagnostic: String? = null,
    val trace: RecognitionTrace? = null
)

data class RejectionMeta(
    val moveLabel: String,
    val fingerprint: String,
    val from: BoardRegion?,
    val to: BoardRegion?
)

data class ErrorCaptureMeta(
    val stateSignature: String,
    val stableHits: Int,
    val detectionConfidence: Float,
    val diagnostics: List<String>,
    val violations: List<RecognitionViolation>,
    val violationSource: String = ErrorCapturePolicy.VIOLATION_SOURCE_FINAL
)

object GoldenTruthJson {
    fun toJson(
        sample: GoldenSample,
        rejection: RejectionMeta? = null,
        errorCapture: ErrorCaptureMeta? = null
    ): String {
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
        errorCapture?.let { meta ->
            root.put("captureType", "recognition_error")
            root.put("stateSignature", meta.stateSignature)
            root.put("stableHits", meta.stableHits)
            root.put("detectionConfidence", meta.detectionConfidence.toDouble())
            root.put("diagnostics", JSONArray(meta.diagnostics))
            root.put("violations", violationsJson(meta.violations))
            root.put("violationSource", meta.violationSource)
        }
        val slots = JSONArray()
        sample.slots.forEach { slot ->
            val slotJson = JSONObject()
                .put("pile", slot.pile)
                .put("index", slot.index)
                .put("inferred", slot.inferred)
                .put("bounds", boundsJson(slot.bounds))
                .put("engine", guessJson(slot.engine))
                .put("truth", guessJson(slot.truth))
            slot.confidence?.let { slotJson.put("confidence", it.toDouble()) }
            slot.diagnostic?.let { slotJson.put("diagnostic", it) }
            slot.trace?.let { slotJson.put("trace", traceJson(it)) }
            slots.put(slotJson)
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
                        inferred = obj.optBoolean("inferred", false),
                        confidence = obj.optDouble("confidence")
                            .takeIf { !it.isNaN() && obj.has("confidence") }
                            ?.toFloat(),
                        diagnostic = obj.optString("diagnostic", "")
                            .takeIf { it.isNotBlank() },
                        trace = traceFromJson(obj.optJSONObject("trace"))
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

    private fun traceFromJson(obj: JSONObject?): RecognitionTrace? {
        if (obj == null) return null
        val postStepsJson = obj.optJSONArray("postSteps")
        val postSteps = buildList {
            if (postStepsJson != null) {
                for (i in 0 until postStepsJson.length()) {
                    add(postStepsJson.getString(i))
                }
            }
        }
        return RecognitionTrace(
            rankSource = obj.optString("rankSource", "").takeIf { it.isNotBlank() },
            rankScore = obj.optDouble("rankScore")
                .takeIf { !it.isNaN() && obj.has("rankScore") }
                ?.toFloat(),
            rankTemplates = obj.optString("rankTemplates", "").takeIf { it.isNotBlank() },
            suitSource = obj.optString("suitSource", "").takeIf { it.isNotBlank() },
            suitScore = obj.optDouble("suitScore")
                .takeIf { !it.isNaN() && obj.has("suitScore") }
                ?.toFloat(),
            suitTemplates = obj.optString("suitTemplates", "").takeIf { it.isNotBlank() },
            postSteps = postSteps
        )
    }

    private fun traceJson(trace: RecognitionTrace): JSONObject {
        val obj = JSONObject()
        trace.rankSource?.let { obj.put("rankSource", it) }
        trace.rankScore?.let { obj.put("rankScore", it.toDouble()) }
        trace.rankTemplates?.let { obj.put("rankTemplates", it) }
        trace.suitSource?.let { obj.put("suitSource", it) }
        trace.suitScore?.let { obj.put("suitScore", it.toDouble()) }
        trace.suitTemplates?.let { obj.put("suitTemplates", it) }
        if (trace.postSteps.isNotEmpty()) {
            obj.put("postSteps", JSONArray(trace.postSteps))
        }
        return obj
    }

    private fun violationsJson(violations: List<RecognitionViolation>): JSONArray {
        val array = JSONArray()
        violations.forEach { violation ->
            when (violation) {
                is RecognitionViolation.DuplicateCard -> array.put(
                    JSONObject()
                        .put("type", "duplicate")
                        .put("cardId", violation.cardId)
                        .put("locations", JSONArray(violation.locations))
                )
                is RecognitionViolation.CascadeBreak -> array.put(
                    JSONObject()
                        .put("type", "cascade_break")
                        .put("pile", violation.pile)
                        .put("lowerIndex", violation.lowerIndex)
                        .put("upperIndex", violation.upperIndex)
                        .put("lower", violation.lowerCard)
                        .put("upper", violation.upperCard)
                )
            }
        }
        return array
    }

    fun parseErrorCaptureMeta(text: String): ErrorCaptureMeta? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optString("captureType") != "recognition_error") return null
        val diagnosticsJson = root.optJSONArray("diagnostics") ?: JSONArray()
        val diagnostics = buildList {
            for (i in 0 until diagnosticsJson.length()) {
                add(diagnosticsJson.getString(i))
            }
        }
        val violationsArray = root.optJSONArray("violations") ?: JSONArray()
        val violations = buildList {
            for (i in 0 until violationsArray.length()) {
                val obj = violationsArray.getJSONObject(i)
                when (obj.getString("type")) {
                    "duplicate" -> {
                        val locationsJson = obj.getJSONArray("locations")
                        val locations = buildList {
                            for (j in 0 until locationsJson.length()) {
                                add(locationsJson.getString(j))
                            }
                        }
                        add(
                            RecognitionViolation.DuplicateCard(
                                cardId = obj.getString("cardId"),
                                locations = locations
                            )
                        )
                    }
                    "cascade_break" -> add(
                        RecognitionViolation.CascadeBreak(
                            pile = obj.getString("pile"),
                            lowerIndex = obj.getInt("lowerIndex"),
                            upperIndex = obj.getInt("upperIndex"),
                            lowerCard = obj.getString("lower"),
                            upperCard = obj.getString("upper")
                        )
                    )
                }
            }
        }
        return ErrorCaptureMeta(
            stateSignature = root.getString("stateSignature"),
            stableHits = root.getInt("stableHits"),
            detectionConfidence = root.getDouble("detectionConfidence").toFloat(),
            diagnostics = diagnostics,
            violations = violations,
            violationSource = root.optString(
                "violationSource",
                ErrorCapturePolicy.VIOLATION_SOURCE_FINAL
            )
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

fun RecognizedSlot.toErrorCaptureSlot(): GoldenSlot = GoldenSlot(
    pile = pileRefKey(pile),
    index = index,
    bounds = bounds,
    engine = engine,
    truth = engine,
    inferred = inferred,
    confidence = confidence,
    diagnostic = diagnostic,
    trace = trace
)
