package dev.roesler.marquee.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class WatchlistStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<MediaItem> {
        val raw = preferences.getString(KEY_ITEMS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        MediaItem(
                            id = item.optInt("id"),
                            type = MediaType.from(item.optString("type")),
                            title = item.optString("title"),
                            year = item.optString("year"),
                            posterUrl = item.optNullableString("poster"),
                            backdropUrl = item.optNullableString("backdrop"),
                            overview = item.optString("overview"),
                            rating = item.optDouble("rating"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun toggle(item: MediaItem): Boolean {
        val items = load().toMutableList()
        val existing = items.indexOfFirst { it.id == item.id && it.type == item.type }
        val saved = existing < 0
        if (saved) {
            items.add(0, item)
        } else {
            items.removeAt(existing)
        }
        save(items.take(MAX_ITEMS))
        return saved
    }

    fun contains(item: MediaItem): Boolean =
        load().any { it.id == item.id && it.type == item.type }

    private fun save(items: List<MediaItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("type", item.type.apiName)
                    .put("title", item.title)
                    .put("year", item.year)
                    .put("poster", item.posterUrl)
                    .put("backdrop", item.backdropUrl)
                    .put("overview", item.overview)
                    .put("rating", item.rating),
            )
        }
        preferences.edit { putString(KEY_ITEMS, array.toString()) }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    companion object {
        private const val PREFERENCES = "marquee_watchlist"
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 100
    }
}
