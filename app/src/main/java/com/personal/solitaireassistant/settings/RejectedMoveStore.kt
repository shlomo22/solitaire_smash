package com.personal.solitaireassistant.settings

import android.content.Context

class RejectedMoveStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun reject(fingerprint: String) {
        val updated = all().toMutableSet()
        updated.add(fingerprint)
        prefs.edit().putStringSet(KEY, HashSet(updated)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val PREFS_NAME = "rejected_moves"
        private const val KEY = "fingerprints"
    }
}
