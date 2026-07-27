package dev.roesler.marquee.data

import org.json.JSONArray
import org.json.JSONObject

internal fun MediaItem.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("type", type.apiName)
        .put("title", title)
        .put("year", year)
        .put("poster", posterUrl)
        .put("backdrop", backdropUrl)
        .put("overview", overview)
        .put("rating", rating)
        .put("imdb", imdbId)
        .put("genre_ids", JSONArray().also { array -> genreIds.forEach { array.put(it) } })

internal fun JSONObject.toMediaItemOrNull(): MediaItem? {
    val id = optInt("id")
    val title = optString("title")
    if (id <= 0 || title.isBlank()) return null
    return MediaItem(
        id = id,
        type = MediaType.from(optString("type")),
        title = title,
        year = optString("year"),
        posterUrl = optNullableString("poster"),
        backdropUrl = optNullableString("backdrop"),
        overview = optString("overview"),
        rating = optDouble("rating").takeIf(Double::isFinite) ?: 0.0,
        imdbId = optNullableString("imdb"),
        genreIds = optJSONArray("genre_ids").toIntList(),
    )
}

internal fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

internal fun JSONArray?.toIntList(): List<Int> {
    if (this == null || length() == 0) return emptyList()
    return buildList(length()) {
        for (index in 0 until length()) {
            optInt(index, 0).takeIf { it > 0 }?.let(::add)
        }
    }
}

internal inline fun JSONArray?.forEachObject(action: (JSONObject) -> Unit) {
    if (this == null) return
    for (index in 0 until length()) {
        optJSONObject(index)?.let(action)
    }
}

/** Parses a stored JSON array once, skipping entries the current schema no longer accepts. */
internal fun <T> parseJsonArray(raw: String?, transform: (JSONObject) -> T?): List<T> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        buildList {
            JSONArray(raw).forEachObject { entry -> transform(entry)?.let(::add) }
        }
    }.getOrDefault(emptyList())
}

internal fun <T> Collection<T>.toJsonArrayString(transform: (T) -> JSONObject): String =
    JSONArray().apply { this@toJsonArrayString.forEach { put(transform(it)) } }.toString()
