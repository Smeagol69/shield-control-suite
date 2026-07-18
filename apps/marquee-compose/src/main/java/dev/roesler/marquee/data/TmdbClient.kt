package dev.roesler.marquee.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class TmdbClient(private val settingsStore: SettingsStore) {
    fun trending(): List<MediaItem> = mediaList(request("/trending/all/week"))

    fun popularMovies(): List<MediaItem> = mediaList(request("/movie/popular"))

    fun popularTv(): List<MediaItem> = mediaList(request("/tv/popular"))

    fun topRatedMovies(): List<MediaItem> = mediaList(request("/movie/top_rated"))

    fun nowPlaying(): List<MediaItem> = mediaList(request("/movie/now_playing"))

    fun searchTitles(query: String): List<MediaItem> =
        mediaList(request("/search/multi", mapOf("query" to query)))
            .filter { it.title.isNotBlank() }

    fun searchPeople(query: String): List<Person> {
        val results = request("/search/person", mapOf("query" to query)).optJSONArray("results")
            ?: JSONArray()
        return buildList {
            for (index in 0 until minOf(results.length(), RESULT_LIMIT)) {
                val person = results.optJSONObject(index) ?: continue
                val name = person.optString("name")
                if (name.isBlank()) continue
                val knownFor = person.optJSONArray("known_for")
                    .toObjectSequence()
                    .map { it.optString("title").ifBlank { it.optString("name") } }
                    .filter(String::isNotBlank)
                    .take(3)
                    .joinToString()
                add(
                    Person(
                        id = person.optInt("id"),
                        name = name,
                        photoUrl = image(person.optNullableString("profile_path"), "w342"),
                        knownFor = knownFor,
                    ),
                )
            }
        }
    }

    fun personCredits(personId: Int): List<MediaItem> {
        val cast = request("/person/$personId/combined_credits").optJSONArray("cast") ?: JSONArray()
        val seen = hashSetOf<String>()
        return cast.toObjectSequence()
            .mapNotNull(::mediaItem)
            .filter { seen.add("${it.type.apiName}:${it.id}") }
            .sortedByDescending(MediaItem::rating)
            .take(RESULT_LIMIT)
            .toList()
    }

    fun details(item: MediaItem): MediaDetails {
        val json = request("/${item.type.apiName}/${item.id}")
        val normalized = mediaItem(json, item.type) ?: item
        val genres = json.optJSONArray("genres")
            .toObjectSequence()
            .map { it.optString("name") }
            .filter(String::isNotBlank)
            .take(4)
            .toList()
        return MediaDetails(
            item = normalized.copy(
                posterUrl = normalized.posterUrl ?: item.posterUrl,
                backdropUrl = normalized.backdropUrl ?: item.backdropUrl,
            ),
            genres = genres,
            runtimeMinutes = json.optInt("runtime").takeIf { it > 0 }
                ?: json.optJSONArray("episode_run_time")?.optInt(0)?.takeIf { it > 0 },
            seasons = json.optInt("number_of_seasons").takeIf { it > 0 },
        )
    }

    fun recommendations(item: MediaItem): List<MediaItem> =
        mediaList(request("/${item.type.apiName}/${item.id}/recommendations"))

    fun watchOptions(item: MediaItem): WatchOptions {
        val settings = settingsStore.load()
        val results = request("/${item.type.apiName}/${item.id}/watch/providers")
            .optJSONObject("results")
        val region = results?.optJSONObject(settings.region)
            ?: results?.optJSONObject("US")
            ?: return WatchOptions(emptyList(), null)

        val providers = linkedMapOf<Int, WatchProvider>()
        listOf(
            "flatrate" to "Stream",
            "free" to "Free",
            "ads" to "With ads",
            "rent" to "Rent",
            "buy" to "Buy",
        ).forEach { (arrayName, access) ->
            val values = region.optJSONArray(arrayName) ?: JSONArray()
            for (index in 0 until values.length()) {
                val provider = values.optJSONObject(index) ?: continue
                val id = provider.optInt("provider_id")
                if (id <= 0 || providers.containsKey(id)) continue
                providers[id] = WatchProvider(
                    id = id,
                    name = provider.optString("provider_name"),
                    logoUrl = image(provider.optNullableString("logo_path"), "w185"),
                    packageName = PROVIDER_PACKAGES[id],
                    access = access,
                )
            }
        }
        return WatchOptions(
            providers = providers.values.toList(),
            webLink = region.optNullableString("link"),
        )
    }

    private fun mediaList(response: JSONObject): List<MediaItem> {
        val results = response.optJSONArray("results") ?: return emptyList()
        return results.toObjectSequence()
            .mapNotNull(::mediaItem)
            .filter { it.posterUrl != null }
            .take(RESULT_LIMIT)
            .toList()
    }

    private fun mediaItem(json: JSONObject, forcedType: MediaType? = null): MediaItem? {
        val title = json.optString("title").ifBlank { json.optString("name") }
        val id = json.optInt("id")
        if (id <= 0 || title.isBlank()) return null
        val type = forcedType ?: MediaType.from(
            json.optString("media_type"),
            hasMovieTitle = json.optString("title").isNotBlank(),
        )
        return MediaItem(
            id = id,
            type = type,
            title = title,
            year = json.optString("release_date")
                .ifBlank { json.optString("first_air_date") }
                .take(4),
            posterUrl = image(json.optNullableString("poster_path"), "w500"),
            backdropUrl = image(json.optNullableString("backdrop_path"), "w1280"),
            overview = json.optString("overview"),
            rating = json.optDouble("vote_average").takeIf(Double::isFinite) ?: 0.0,
        )
    }

    private fun request(path: String, parameters: Map<String, String> = emptyMap()): JSONObject {
        val settings = settingsStore.load()
        val credential = settings.tmdbCredential.trim()
        if (credential.isBlank()) throw TmdbException.MissingCredential

        val query = linkedMapOf(
            "language" to "en-US",
            "region" to settings.region,
        ).apply {
            putAll(parameters)
            if (!credential.looksLikeBearerToken()) put("api_key", credential)
        }
        val queryString = query.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        val connection = URI.create("$BASE_URL$path?$queryString")
            .toURL()
            .openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Marquee/2.0 AndroidTV")
            if (credential.looksLikeBearerToken()) {
                connection.setRequestProperty("Authorization", "Bearer $credential")
            }

            when (val status = connection.responseCode) {
                in 200..299 -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(body)
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> throw TmdbException.RejectedCredential
                429 -> throw TmdbException.RateLimited
                else -> throw TmdbException.Http(status)
            }
        } catch (error: TmdbException) {
            throw error
        } catch (error: IOException) {
            throw TmdbException.Network(error.message ?: "Network request failed.")
        } finally {
            connection.disconnect()
        }
    }

    private fun String.looksLikeBearerToken(): Boolean =
        startsWith("eyJ") && count { it == '.' } >= 2

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun image(path: String?, size: String): String? =
        path?.let { "$IMAGE_BASE/$size$it" }

    private fun JSONArray?.toObjectSequence(): Sequence<JSONObject> =
        if (this == null) emptySequence() else sequence {
            for (index in 0 until length()) optJSONObject(index)?.let { yield(it) }
        }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    sealed class TmdbException(message: String) : IOException(message) {
        data object MissingCredential : TmdbException("Add a TMDB API credential in Settings.")
        data object RejectedCredential : TmdbException("TMDB rejected that credential.")
        data object RateLimited : TmdbException("TMDB rate limit reached. Try again shortly.")
        data class Http(val status: Int) : TmdbException("TMDB request failed (HTTP $status).")
        data class Network(val detail: String) : TmdbException(detail)
    }

    companion object {
        private const val BASE_URL = "https://api.themoviedb.org/3"
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 12_000
        private const val RESULT_LIMIT = 30

        val PROVIDER_PACKAGES = mapOf(
            8 to "com.netflix.ninja",
            337 to "com.disney.disneyplus",
            9 to "com.amazon.amazonvideo.livingroom",
            119 to "com.amazon.amazonvideo.livingroom",
            1899 to "com.wbd.stream",
            384 to "com.wbd.stream",
            15 to "com.hulu.livingroomplus",
            2 to "com.apple.atve.androidtv.appletv",
            350 to "com.apple.atve.androidtv.appletv",
            531 to "com.cbs.ott",
            386 to "com.peacocktv.peacockandroid",
            387 to "com.peacocktv.peacockandroid",
            283 to "com.crunchyroll.crunchyroid",
            257 to "tv.fubo.mobile",
            1770 to "com.paramount.android.pplus",
        )
    }
}
