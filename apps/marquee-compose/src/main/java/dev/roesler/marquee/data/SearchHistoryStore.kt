package dev.roesler.marquee.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/** Small, ordered local history so TV searches do not need to be typed twice. */
class SearchHistoryStore(
    context: Context,
    kind: SearchHistoryKind = SearchHistoryKind.TITLES,
) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val storageKey = kind.storageKey

    fun load(): List<String> = runCatching {
        val array = JSONArray(preferences.getString(storageKey, "[]"))
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }.take(HISTORY_LIMIT)
    }.getOrDefault(emptyList())

    fun record(query: String): List<String> = updateSearchHistory(load(), query).also(::save)

    fun clear() {
        preferences.edit { remove(storageKey) }
    }

    private fun save(queries: List<String>) {
        preferences.edit {
            putString(storageKey, JSONArray().also { array -> queries.forEach(array::put) }.toString())
        }
    }

    companion object {
        private const val PREFERENCES = "marquee_search_history"
        internal const val HISTORY_LIMIT = 8
        internal const val MAX_QUERY_LENGTH = 120
    }
}

enum class SearchHistoryKind(internal val storageKey: String) {
    /** Keeps the original key so upgrades preserve existing title history. */
    TITLES("queries"),
    PEOPLE("people_queries"),
}

/** Most-recent first, case-insensitive de-duplication, and a hard privacy/storage bound. */
internal fun updateSearchHistory(
    current: List<String>,
    query: String,
    limit: Int = SearchHistoryStore.HISTORY_LIMIT,
): List<String> {
    require(limit > 0) { "limit must be positive" }
    val normalized = query.trim()
        .replace(Regex("""\s+"""), " ")
        .take(SearchHistoryStore.MAX_QUERY_LENGTH)
    if (normalized.length < 2) return current.take(limit)
    return (listOf(normalized) + current.filterNot { it.equals(normalized, ignoreCase = true) })
        .take(limit)
}
