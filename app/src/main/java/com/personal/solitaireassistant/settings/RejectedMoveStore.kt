package com.personal.solitaireassistant.settings

import android.content.Context

class RejectedMoveStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun reject(fingerprint: String) {
        if (fingerprint == "DRAW" || fingerprint == "RECYCLE") return
        val updated = all().toMutableSet()
        updated.add(fingerprint)
        // Drop any legacy stock fingerprints so old installs recover.
        updated.remove("DRAW")
        updated.remove("RECYCLE")
        prefs.edit().putStringSet(KEY, HashSet(updated)).apply()
    }

    fun all(): Set<String> {
        val raw = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
        if ("DRAW" !in raw && "RECYCLE" !in raw) return raw
        val cleaned = raw.filterNot { it == "DRAW" || it == "RECYCLE" }.toSet()
        prefs.edit().putStringSet(KEY, HashSet(cleaned)).apply()
        return cleaned
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS_NAME = "rejected_moves"
        private const val KEY = "fingerprints"
    }
}
