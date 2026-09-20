package dev.roesler.marquee.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Titles the viewer has waved away without watching them.
 *
 * Every other negative signal the model has requires actually sitting through something: a
 * dislike means you watched it, an abandon means you started it. There was no way to say "not
 * this, and stop offering it" about a poster on a shelf — which is the cheapest and most common
 * judgement a person makes while browsing, and the one the model most wanted.
 *
 * Kept apart from [TasteStore] on purpose. A dislike is a verdict on something seen and is worth
 * syncing to Trakt as a rating; this is a local steering gesture about something unseen, and
 * pushing it as a 1-star review of a film the viewer never watched would be a lie about them.
 */
class SuppressionStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private var index: LinkedHashMap<String, Long>? = null

    @Synchronized
    fun keys(): Set<String> = index().keys.toSet()

    @Synchronized
    fun contains(item: MediaItem): Boolean = index().containsKey(item.key)

    /** Records a title as unwanted, or lifts it again if it was already suppressed. */
    @Synchronized
    fun toggle(item: MediaItem, now: Long = System.currentTimeMillis()): Boolean {
        val entries = index()
        val suppressed = if (entries.remove(item.key) == null) {
            entries[item.key] = now
            true
        } else {
            false
        }
        persist(entries)
        return suppressed
    }

    /** Suppressed titles as model input, oldest first. */
    @Synchronized
    fun entries(): List<Pair<String, Long>> = index().entries.map { it.key to it.value }

    @Synchronized
    fun size(): Int = index().size

    @Synchronized
    fun clear() {
        index = LinkedHashMap()
        preferences.edit { clear() }
    }

    private fun index(): LinkedHashMap<String, Long> = index ?: load().also { index = it }

    private fun load(): LinkedHashMap<String, Long> {
        val raw = preferences.getString(KEY_ENTRIES, null)
        if (raw.isNullOrBlank()) return LinkedHashMap()
        return runCatching {
            val json = JSONObject(raw)
            val parsed = LinkedHashMap<String, Long>(json.length().coerceAtLeast(8))
            json.keys().forEach { key -> parsed[key] = json.optLong(key) }
            parsed
        }.getOrDefault(LinkedHashMap())
    }

    private fun persist(entries: LinkedHashMap<String, Long>) {
        // Oldest go first: a wave-off is a statement about right now, and keeping them forever
        // would let a years-old shrug quietly narrow the catalog.
        val trimmed = if (entries.size > MAX_ENTRIES) {
            entries.entries
                .sortedByDescending { it.value }
                .take(MAX_ENTRIES)
                .associateTo(LinkedHashMap()) { it.key to it.value }
        } else {
            entries
        }
        index = trimmed
        val json = JSONObject()
        trimmed.forEach { (key, at) -> json.put(key, at) }
        preferences.edit { putString(KEY_ENTRIES, json.toString()) }
    }

    private companion object {
        const val PREFERENCES = "marquee_suppressed"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 400
    }
}
