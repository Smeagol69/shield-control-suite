package dev.roesler.marquee.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.Collections

class TmdbClient(private val settingsStore: SettingsStore) {
    private val watchOptionsCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, WatchOptions>(WATCH_OPTIONS_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, WatchOptions>,
            ): Boolean = size > WATCH_OPTIONS_CACHE_SIZE
        },
    )

    fun trending(): List<MediaItem> = discoveryMediaList("/trending/all/week")

    fun popularMovies(): List<MediaItem> = discoveryMediaList("/movie/popular")

    fun popularTv(): List<MediaItem> = discoveryMediaList("/tv/popular")

    fun topRatedMovies(): List<MediaItem> = discoveryMediaList("/movie/top_rated")

    fun nowPlaying(): List<MediaItem> = discoveryMediaList("/movie/now_playing")

    fun catalogProviders(): List<CatalogProvider> {
        val region = settingsStore.load().region
        val providers = linkedMapOf<Int, CatalogProvider>()
        listOf("/watch/providers/movie", "/watch/providers/tv").forEach { path ->
            request(path, mapOf("watch_region" to region))
                .optJSONArray("results")
                .toObjectSequence()
                .mapNotNull { it.toCatalogProvider(region) }
                .forEach { candidate ->
                    val current = providers[candidate.id]
                    providers[candidate.id] = if (current == null) {
                        candidate
                    } else {
                        current.copy(
                            logoUrl = current.logoUrl ?: candidate.logoUrl,
                            packageName = current.packageName ?: candidate.packageName,
                            displayPriority = minOf(
                                current.displayPriority,
                                candidate.displayPriority,
                            ),
                        )
                    }
                }
        }
        return providers.values.sortedWith(
            compareBy<CatalogProvider>(
                ::majorProviderRank,
                CatalogProvider::displayPriority,
                CatalogProvider::name,
            ),
        )
    }

    fun providerTitles(
        providerId: Int,
        type: MediaType,
        sort: ProviderSort,
        genreId: Int? = null,
    ): List<MediaItem> {
        require(providerId > 0) { "Provider ID must be positive." }
        val settings = settingsStore.load()
        val parameters = linkedMapOf(
            "watch_region" to settings.region,
            "with_watch_providers" to providerId.toString(),
            "with_watch_monetization_types" to "flatrate|free|ads",
            "include_adult" to "false",
        )
        genreId?.takeIf { it > 0 }?.let { parameters["with_genres"] = it.toString() }
        when (sort) {
            ProviderSort.POPULAR -> {
                parameters["sort_by"] = "popularity.desc"
            }
            ProviderSort.NEWEST -> {
                parameters["sort_by"] = if (type == MediaType.MOVIE) {
                    "primary_release_date.desc"
                } else {
                    "first_air_date.desc"
                }
                parameters[
                    if (type == MediaType.MOVIE) {
                        "primary_release_date.lte"
                    } else {
                        "first_air_date.lte"
                    },
                ] = LocalDate.now().toString()
                parameters["vote_count.gte"] = "5"
            }
            ProviderSort.TOP_RATED -> {
                parameters["sort_by"] = "vote_average.desc"
                parameters["vote_count.gte"] = "100"
            }
        }
        return discoveryMediaList(
            path = "/discover/${type.apiName}",
            parameters = parameters,
            forcedType = type,
        )
    }

    fun searchTitles(query: String): List<MediaItem> =
        mediaList(request("/search/multi", mapOf("query" to query)))
            .filter { it.title.isNotBlank() }

    fun findTv(imdbId: String?, title: String, year: String): MediaItem? {
        val exact = imdbId
            ?.takeIf { it.matches(Regex("""tt\d{5,12}""")) }
            ?.let { externalId ->
                request(
                    "/find/$externalId",
                    mapOf("external_source" to "imdb_id"),
                ).optJSONArray("tv_results")
                    .toObjectSequence()
                    .mapNotNull { mediaItem(it, MediaType.TV) }
                    .firstOrNull()
            }
        if (exact != null) return exact.copy(imdbId = imdbId)

        return searchTitles(title)
            .asSequence()
            .filter { it.type == MediaType.TV }
            .sortedWith(
                compareByDescending<MediaItem> { it.title.equals(title, ignoreCase = true) }
                    .thenByDescending { year.isNotBlank() && it.year == year }
                    .thenByDescending(MediaItem::rating),
            )
            .firstOrNull()
    }

    fun searchPeople(query: String): List<Person> {
        val results = request("/search/person", mapOf("query" to query)).optJSONArray("results")
            ?: JSONArray()
        return people(results)
    }

    fun popularPeople(): List<Person> =
        people(request("/person/popular").optJSONArray("results") ?: JSONArray())

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
        val json = request(
            "/${item.type.apiName}/${item.id}",
            mapOf("append_to_response" to "external_ids,credits"),
        )
        val normalized = mediaItem(json, item.type) ?: item
        val genres = json.optJSONArray("genres")
            .toObjectSequence()
            .map { it.optString("name") }
            .filter(String::isNotBlank)
            .take(4)
            .toList()
        val castArray = json.optJSONObject("credits")?.optJSONArray("cast")
        val cast = castArray.toObjectSequence()
            .filter { it.optNullableString("profile_path") != null }
            .take(DETAIL_CAST_LIMIT)
            .map { member ->
                Person(
                    id = member.optInt("id"),
                    name = member.optString("name"),
                    photoUrl = image(member.optNullableString("profile_path"), "w342"),
                    knownFor = member.optString("character"),
                )
            }
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
            cast = cast,
        )
    }

    fun recommendations(item: MediaItem): List<MediaItem> =
        mediaList(request("/${item.type.apiName}/${item.id}/recommendations"))

    fun watchOptions(item: MediaItem): WatchOptions {
        val settings = settingsStore.load()
        val cacheKey = "${settings.region}:${item.type.apiName}:${item.id}"
        watchOptionsCache[cacheKey]?.let { return it }
        val results = request("/${item.type.apiName}/${item.id}/watch/providers")
            .optJSONObject("results")
        val region = results?.optJSONObject(settings.region)
            ?: results?.optJSONObject("US")
        if (region == null) {
            return WatchOptions(emptyList(), null).also {
                watchOptionsCache[cacheKey] = it
            }
        }

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
                    packageName = packageFor(id, provider.optString("provider_name")),
                    access = access,
                )
            }
        }
        return WatchOptions(
            providers = providers.values.toList(),
            webLink = region.optNullableString("link"),
        ).also { watchOptionsCache[cacheKey] = it }
    }

    fun isAvailableOn(item: MediaItem, providerId: Int): Boolean =
        watchOptions(item).providers.any { it.id == providerId }

    private fun mediaList(
        response: JSONObject,
        forcedType: MediaType? = null,
    ): List<MediaItem> = mediaItems(response, forcedType).take(RESULT_LIMIT)

    private fun discoveryMediaList(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        forcedType: MediaType? = null,
    ): List<MediaItem> = collectUniquePages(
        targetSize = DISCOVERY_RESULT_TARGET,
        maxPages = DISCOVERY_MAX_PAGES,
        keyOf = { item -> "${item.type.apiName}:${item.id}" },
    ) { page ->
        val response = request(
            path,
            parameters + ("page" to page.toString()),
        )
        PageResult(
            items = mediaItems(response, forcedType),
            totalPages = response.optInt("total_pages", page),
        )
    }

    private fun mediaItems(
        response: JSONObject,
        forcedType: MediaType? = null,
    ): List<MediaItem> {
        val results = response.optJSONArray("results") ?: return emptyList()
        return results.toObjectSequence()
            .mapNotNull { mediaItem(it, forcedType) }
            .filter { it.posterUrl != null }
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
            imdbId = json.optNullableString("imdb_id")
                ?: json.optJSONObject("external_ids")?.optNullableString("imdb_id"),
        )
    }

    private fun people(results: JSONArray): List<Person> = buildList {
        for (index in 0 until minOf(results.length(), RESULT_LIMIT)) {
            val person = results.optJSONObject(index) ?: continue
            val id = person.optInt("id")
            val name = person.optString("name")
            if (id <= 0 || name.isBlank()) continue
            val knownFor = person.optJSONArray("known_for")
                .toObjectSequence()
                .map { it.optString("title").ifBlank { it.optString("name") } }
                .filter(String::isNotBlank)
                .take(3)
                .joinToString()
            add(
                Person(
                    id = id,
                    name = name,
                    photoUrl = image(person.optNullableString("profile_path"), "w342"),
                    knownFor = knownFor,
                ),
            )
        }
    }

    private fun JSONObject.toCatalogProvider(region: String): CatalogProvider? {
        val id = optInt("provider_id")
        val name = optString("provider_name").trim()
        if (id <= 0 || name.isBlank()) return null
        val regionalPriorities = optJSONObject("display_priorities")
        val priority = if (regionalPriorities?.has(region) == true) {
            regionalPriorities.optInt(region)
        } else {
            optInt("display_priority", DEFAULT_PROVIDER_PRIORITY)
        }
        return CatalogProvider(
            id = id,
            name = name,
            logoUrl = image(optNullableString("logo_path"), "w185"),
            packageName = packageFor(id, name),
            displayPriority = priority,
        )
    }

    private fun majorProviderRank(provider: CatalogProvider): Int {
        MAJOR_PROVIDER_IDS[provider.id]?.let { return it }
        val normalized = provider.name.lowercase()
        val byName = MAJOR_PROVIDER_NAMES.indexOfFirst { normalized == it }
        return if (byName >= 0) byName else MAJOR_PROVIDER_NAMES.size + 1
    }

    private fun packageFor(id: Int, name: String): String? =
        PROVIDER_PACKAGES[id] ?: PROVIDER_PACKAGE_NAMES[name.lowercase()]

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
        val url = URI.create("$BASE_URL$path?$queryString").toString()
        val headers = if (credential.looksLikeBearerToken()) {
            mapOf("Authorization" to "Bearer $credential")
        } else {
            emptyMap()
        }
        return try {
            val response = JsonHttp.request(url = url, headers = headers)
            when (val status = response.status) {
                in 200..299 -> runCatching { JSONObject(response.body) }
                    .getOrElse { throw TmdbException.InvalidResponse }
                401 -> throw TmdbException.RejectedCredential
                429 -> throw TmdbException.RateLimited
                else -> throw TmdbException.Http(status)
            }
        } catch (error: TmdbException) {
            throw error
        } catch (error: IOException) {
            throw TmdbException.Network(error.message ?: "Network request failed.")
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
        data object InvalidResponse : TmdbException("TMDB returned invalid data.")
        data class Http(val status: Int) : TmdbException("TMDB request failed (HTTP $status).")
        data class Network(val detail: String) : TmdbException(detail)
    }

    companion object {
        private const val BASE_URL = "https://api.themoviedb.org/3"
        private const val IMAGE_BASE = "https://image.tmdb.org/t/p"
        private const val RESULT_LIMIT = 30
        private const val DETAIL_CAST_LIMIT = 18
        private const val DISCOVERY_RESULT_TARGET = 60
        private const val DISCOVERY_MAX_PAGES = 4
        private const val WATCH_OPTIONS_CACHE_SIZE = 256
        private const val DEFAULT_PROVIDER_PRIORITY = 10_000

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

        private val PROVIDER_PACKAGE_NAMES = mapOf(
            "netflix" to "com.netflix.ninja",
            "disney plus" to "com.disney.disneyplus",
            "amazon prime video" to "com.amazon.amazonvideo.livingroom",
            "amazon video" to "com.amazon.amazonvideo.livingroom",
            "max" to "com.wbd.stream",
            "hbo max" to "com.wbd.stream",
            "hulu" to "com.hulu.livingroomplus",
            "apple tv plus" to "com.apple.atve.androidtv.appletv",
            "apple tv" to "com.apple.atve.androidtv.appletv",
            "paramount plus" to "com.paramount.android.pplus",
            "peacock premium" to "com.peacocktv.peacockandroid",
            "peacock premium plus" to "com.peacocktv.peacockandroid",
            "crunchyroll" to "com.crunchyroll.crunchyroid",
            "fubotv" to "tv.fubo.mobile",
        )

        private val MAJOR_PROVIDER_NAMES = listOf(
            "amazon prime video",
            "apple tv plus",
            "disney plus",
            "hulu",
            "max",
            "paramount plus",
            "peacock premium",
            "netflix",
            "crunchyroll",
        )

        private val MAJOR_PROVIDER_IDS = mapOf(
            9 to 0,
            350 to 1,
            337 to 2,
            15 to 3,
            1899 to 4,
            531 to 5,
            386 to 6,
            8 to 7,
            283 to 8,
        )
    }
}
