package dev.roesler.marquee.data

import android.content.Context
import androidx.core.content.edit

/**
 * The local, Shield-only watchlist, newest first. Held in a keyed index so the detail screen's
 * "is this saved?" check and the home shelf's ordering both read from memory instead of
 * re-parsing the stored array.
 */
class WatchlistStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private var index: LinkedHashMap<String, MediaItem>? = null

    @Synchronized
    fun load(): List<MediaItem> = index().values.toList()

    @Synchronized
    fun toggle(item: MediaItem): Boolean {
        val entries = index()
        if (entries.remove(item.key) != null) {
            persist(entries)
            return false
        }
        val updated = LinkedHashMap<String, MediaItem>(entries.size + 1)
        updated[item.key] = item.forHistory()
        entries.entries.take(MAX_ITEMS - 1).forEach { updated[it.key] = it.value }
        index = updated
        persist(updated)
        return true
    }

    @Synchronized
    fun contains(item: MediaItem): Boolean = index().containsKey(item.key)

    private fun index(): LinkedHashMap<String, MediaItem> =
        index ?: parseIndex(preferences.getString(KEY_ITEMS, null)).also { index = it }

    private fun parseIndex(raw: String?): LinkedHashMap<String, MediaItem> {
        val parsed = parseJsonArray(raw) { it.toMediaItemOrNull() }
        return LinkedHashMap<String, MediaItem>(parsed.size.coerceAtLeast(8)).apply {
            parsed.forEach { put(it.key, it) }
        }
    }

    private fun persist(entries: LinkedHashMap<String, MediaItem>) {
        preferences.edit { putString(KEY_ITEMS, entries.values.toJsonArrayString { it.toJson() }) }
    }

    companion object {
        private const val PREFERENCES = "marquee_watchlist"
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 100
    }
}
