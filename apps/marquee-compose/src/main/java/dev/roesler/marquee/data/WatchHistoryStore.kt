package dev.roesler.marquee.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Every movie and show the viewer has watched, merged from the local playback bridge, Trakt
 * history, and explicit mark-watched actions.
 *
 * The whole set is kept in a keyed index in memory. Lookups the discovery pipeline performs on
 * every row — "have I seen this?" — are then a hash probe rather than a JSON parse, and writes
 * are skipped entirely when an observation says nothing new (see [isWorthPersisting]), which
 * matters because the playback bridge reports the active title once a second.
 */
class WatchHistoryStore(
    context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private var index: LinkedHashMap<String, WatchedTitle>? = null

    /**
     * Folds one observation into the history. Returns true when the stored history actually
     * changed, so callers can avoid rebuilding UI for a no-op update.
     */
    @Synchronized
    fun record(entry: WatchedTitle): Boolean {
        val entries = index()
        val stored = entries[entry.key]
        val merged = mergeWatchedTitle(stored, entry)
        if (!isWorthPersisting(stored, merged)) {
            // Still keep the newer timestamp in memory so ordering stays live between writes.
            entries[entry.key] = merged
            return false
        }
        entries[entry.key] = merged
        persist(entries)
        return true
    }

    @Synchronized
    fun recordAll(items: Collection<WatchedTitle>): Boolean {
        if (items.isEmpty()) return false
        val entries = index()
        var changed = false
        items.forEach { entry ->
            val stored = entries[entry.key]
            val merged = mergeWatchedTitle(stored, entry)
            changed = changed || isWorthPersisting(stored, merged)
            entries[entry.key] = merged
        }
        if (changed) persist(entries)
        return changed
    }

    /** Records a title the viewer confirmed they watched, from a button rather than a sensor. */
    fun recordWatched(
        item: MediaItem,
        source: WatchSource,
        completed: Boolean = true,
        episodeLabel: String? = null,
        providerName: String? = null,
    ): Boolean {
        val timestamp = now()
        return record(
            WatchedTitle(
                item = item.forHistory(),
                source = source,
                firstWatchedAtEpochMillis = timestamp,
                lastWatchedAtEpochMillis = timestamp,
                playCount = 1,
                completed = completed,
                progressPercent = if (completed) 100.0 else item.progressPercent,
                episodeLabel = episodeLabel,
                providerName = providerName,
            ),
        )
    }

    @Synchronized
    fun recent(limit: Int): List<WatchedTitle> =
        trimWatchHistory(index().values, limit)

    @Synchronized
    fun watchedKeys(): Set<String> = index().keys.toSet()

    @Synchronized
    fun contains(item: MediaItem): Boolean = index().containsKey(item.key)

    @Synchronized
    fun entryFor(item: MediaItem): WatchedTitle? = index()[item.key]

    @Synchronized
    fun size(): Int = index().size

    @Synchronized
    fun clear() {
        index = LinkedHashMap()
        preferences.edit { clear() }
    }

    private fun index(): LinkedHashMap<String, WatchedTitle> =
        index ?: load().also { index = it }

    private fun load(): LinkedHashMap<String, WatchedTitle> {
        val parsed = parseJsonArray(preferences.getString(KEY_ENTRIES, null)) { entry ->
            val item = entry.optJSONObject("item")?.toMediaItemOrNull()
                ?: return@parseJsonArray null
            val last = entry.optLong("last_watched_at")
            WatchedTitle(
                item = item,
                source = WatchSource.from(entry.optString("source")),
                firstWatchedAtEpochMillis = entry.optLong("first_watched_at").takeIf { it > 0L }
                    ?: last,
                lastWatchedAtEpochMillis = last,
                playCount = entry.optInt("play_count", 1).coerceAtLeast(1),
                completed = entry.optBoolean("completed"),
                progressPercent = entry.optDouble("progress")
                    .takeIf { it.isFinite() && it > 0.0 },
                episodeLabel = entry.optNullableString("episode"),
                providerName = entry.optNullableString("provider"),
            )
        }
        return LinkedHashMap<String, WatchedTitle>(parsed.size.coerceAtLeast(16)).apply {
            parsed.forEach { put(it.key, it) }
        }
    }

    private fun persist(entries: LinkedHashMap<String, WatchedTitle>) {
        if (entries.size > MAX_ENTRIES) {
            val keep = trimWatchHistory(entries.values, MAX_ENTRIES).mapTo(hashSetOf(), WatchedTitle::key)
            entries.keys.retainAll(keep)
        }
        val serialized = entries.values.toJsonArrayString { watched ->
            JSONObject()
                .put("item", watched.item.toJson())
                .put("source", watched.source.storageName)
                .put("first_watched_at", watched.firstWatchedAtEpochMillis)
                .put("last_watched_at", watched.lastWatchedAtEpochMillis)
                .put("play_count", watched.playCount)
                .put("completed", watched.completed)
                .put("progress", watched.progressPercent)
                .put("episode", watched.episodeLabel)
                .put("provider", watched.providerName)
        }
        preferences.edit { putString(KEY_ENTRIES, serialized) }
    }

    companion object {
        private const val PREFERENCES = "marquee_watch_history"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 600
    }
}
