package dev.roesler.marquee.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import kotlin.math.max
import kotlin.math.roundToInt

data class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAtEpochMillis: Long,
    val pollingIntervalSeconds: Int,
)

sealed interface TraktPollResult {
    data object Pending : TraktPollResult
    data class SlowDown(val additionalSeconds: Int) : TraktPollResult
    data class Authorized(val tokens: TraktTokens) : TraktPollResult
    data class Failed(val message: String) : TraktPollResult
}

/** One title in the account's watched library, with how often and how recently it was played. */
data class TraktWatchedEntry(
    val item: MediaItem,
    val plays: Int,
    val lastWatchedAtEpochMillis: Long,
)

data class TraktProfile(
    val username: String,
    val displayName: String?,
)

class TraktClient(
    private val settingsStore: SettingsStore,
    private val traktStore: TraktStore,
) {
    fun requestDeviceCode(): TraktDeviceCode {
        val credentials = credentials()
        val response = request(
            path = "/oauth/device/code",
            method = "POST",
            body = JSONObject().put("client_id", credentials.clientId),
            includeApiHeaders = false,
        )
        if (response.status !in 200..299) throw TraktException.Http(response.status)
        val json = response.objectBody()
        val expiresIn = json.optLong("expires_in").coerceAtLeast(1L)
        return TraktDeviceCode(
            deviceCode = json.requiredString("device_code"),
            userCode = json.requiredString("user_code"),
            verificationUrl = json.requiredString("verification_url"),
            expiresAtEpochMillis = System.currentTimeMillis() + expiresIn * 1_000L,
            pollingIntervalSeconds = max(5, json.optInt("interval", 5)),
        )
    }

    fun pollDeviceToken(deviceCode: String): TraktPollResult {
        val credentials = credentials()
        val response = request(
            path = "/oauth/device/token",
            method = "POST",
            body = JSONObject()
                .put("code", deviceCode)
                .put("client_id", credentials.clientId)
                .put("client_secret", credentials.clientSecret),
            includeApiHeaders = false,
        )
        return when (response.status) {
            200 -> TraktPollResult.Authorized(
                response.objectBody().toTokens().also(traktStore::saveTokens),
            )
            400 -> TraktPollResult.Pending
            404 -> TraktPollResult.Failed("Trakt no longer recognizes this device code.")
            409 -> TraktPollResult.Failed("This Trakt device code was already used.")
            410 -> TraktPollResult.Failed("The Trakt device code expired. Start again.")
            418 -> TraktPollResult.Failed("The Trakt authorization request was denied.")
            429 -> TraktPollResult.SlowDown(response.retryAfterSeconds ?: 5)
            else -> TraktPollResult.Failed("Trakt authorization failed (HTTP ${response.status}).")
        }
    }

    fun profile(): TraktProfile {
        val json = authorizedRequest("/users/settings").objectBody()
        val user = json.optJSONObject("user")
            ?: throw TraktException.InvalidResponse("Trakt did not return an account.")
        val username = user.requiredString("username")
        return TraktProfile(
            username = username,
            displayName = user.optNullableString("name"),
        ).also {
            traktStore.saveAccountName(it.displayName ?: it.username)
        }
    }

    fun recommendations(type: MediaType): List<MediaItem> =
        authorizedRequest(
            "/recommendations/${type.traktPlural()}?limit=$RESULT_LIMIT" +
                "&ignore_watchlisted=true&extended=full%2Cimages",
        ).arrayBody().toMediaItems(type)

    fun watchlist(type: MediaType): List<MediaItem> =
        authorizedRequest(
            "/sync/watchlist/${type.traktPlural()}/rank/asc?limit=$RESULT_LIMIT" +
                "&extended=full%2Cimages",
        ).arrayBody().toMediaItems(type)

    fun recentHistory(): List<MediaItem> {
        return authorizedRequest(
            "/sync/history?limit=$RESULT_LIMIT&extended=full%2Cimages",
        ).arrayBody().toMediaItems(MediaType.MOVIE)
    }

    /**
     * Every distinct title the account has watched, with its play count and last-watched time.
     *
     * This is the right shape for training. `/sync/history` returns play *events* — six hundred
     * of them collapse to a couple of dozen titles once repeated episodes are folded together —
     * whereas `/sync/watched` returns the library itself, several times more titles, each with
     * the play count that distinguishes "watched once" from "watched four times". Real
     * timestamps come with it, so recency decay finally applies to imported history instead of
     * every old play arriving stamped as if it happened today.
     */
    fun watchedLibrary(): List<TraktWatchedEntry> {
        val entries = LinkedHashMap<String, TraktWatchedEntry>()
        listOf("movies" to MediaType.MOVIE, "shows" to MediaType.TV).forEach { (path, type) ->
            val array = runCatching {
                authorizedRequest("/sync/watched/$path?extended=full").arrayBody()
            }.getOrNull() ?: return@forEach
            for (index in 0 until array.length()) {
                val wrapper = array.optJSONObject(index) ?: continue
                val parsed = wrapper.toMediaItem(type) ?: continue
                val media = wrapper.optJSONObject(if (type == MediaType.MOVIE) "movie" else "show")
                val item = parsed.copy(genreIds = media.traktGenreIds(type))
                entries.putIfAbsent(
                    item.key,
                    TraktWatchedEntry(
                        item = item,
                        plays = wrapper.optInt("plays", 1).coerceAtLeast(1),
                        lastWatchedAtEpochMillis = parseIsoMillis(
                            wrapper.optNullableString("last_watched_at"),
                        ),
                    ),
                )
            }
        }
        return entries.values.toList()
    }

    /**
     * Pages deep into the watch history for model training.
     *
     * [recentHistory] is deliberately short because it feeds a "recently watched" shelf, but the
     * taste model wants every play it can get — a viewer with hundreds of logged titles has
     * hundreds of examples to learn from, and truncating to the newest eighteen throws away
     * almost all of the signal. Images are skipped here since nothing renders these directly.
     */
    fun fullHistory(maxPages: Int = HISTORY_MAX_PAGES): List<MediaItem> {
        val collected = LinkedHashMap<String, MediaItem>()
        for (page in 1..maxPages) {
            val batch = authorizedRequest(
                "/sync/history?page=$page&limit=$HISTORY_PAGE_SIZE&extended=full",
            ).arrayBody().toMediaItems(MediaType.MOVIE, limit = HISTORY_PAGE_SIZE)
            if (batch.isEmpty()) break
            batch.forEach { collected.putIfAbsent(it.key, it) }
            if (batch.size < HISTORY_PAGE_SIZE) break
        }
        return collected.values.toList()
    }

    fun playbackProgress(): List<MediaItem> =
        authorizedRequest(
            "/sync/playback?limit=$PLAYBACK_LIMIT&extended=full%2Cimages",
        ).arrayBody().toPlaybackItems()

    fun setWatchlisted(item: MediaItem, watchlisted: Boolean) {
        val collection = if (item.type == MediaType.MOVIE) "movies" else "shows"
        val body = JSONObject().put(
            collection,
            JSONArray().put(
                JSONObject().put(
                    "ids",
                    JSONObject().put("tmdb", item.id),
                ),
            ),
        )
        val path = if (watchlisted) "/sync/watchlist" else "/sync/watchlist/remove"
        val response = authorizedRequest(path, method = "POST", body = body)
        val accepted = if (watchlisted) response.status == 201 else response.status == 200
        if (!accepted) throw TraktException.Http(response.status)
    }

    fun markWatched(item: MediaItem) {
        val collection = if (item.type == MediaType.MOVIE) "movies" else "shows"
        val body = JSONObject().put(
            collection,
            JSONArray().put(
                JSONObject()
                    .put("ids", JSONObject().put("tmdb", item.id)),
            ),
        )
        val response = authorizedRequest("/sync/history", method = "POST", body = body)
        if (response.status !in 200..299) throw TraktException.Http(response.status)
    }

    fun setRating(item: MediaItem, rating: Int) {
        val response = authorizedRequest(
            "/sync/ratings",
            method = "POST",
            body = ratingBody(item, rating),
        )
        if (response.status !in 200..299) throw TraktException.Http(response.status)
    }

    fun removeRating(item: MediaItem) {
        val response = authorizedRequest(
            "/sync/ratings/remove",
            method = "POST",
            body = ratingBody(item, null),
        )
        if (response.status !in 200..299) throw TraktException.Http(response.status)
    }

    private fun ratingBody(item: MediaItem, rating: Int?): JSONObject {
        val collection = if (item.type == MediaType.MOVIE) "movies" else "shows"
        val entry = JSONObject().put("ids", JSONObject().put("tmdb", item.id))
        if (rating != null) entry.put("rating", rating)
        return JSONObject().put(collection, JSONArray().put(entry))
    }

    fun revokeAndClear() {
        val tokens = traktStore.loadTokens()
        val credentials = runCatching { credentials() }.getOrNull()
        try {
            if (tokens != null && credentials != null) {
                request(
                    path = "/oauth/revoke",
                    method = "POST",
                    body = JSONObject()
                        .put("token", tokens.accessToken)
                        .put("client_id", credentials.clientId)
                        .put("client_secret", credentials.clientSecret),
                    includeApiHeaders = false,
                )
            }
        } finally {
            traktStore.clear()
        }
    }

    private fun authorizedRequest(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JsonHttpResponse {
        var token = accessToken()
        var response = request(path, method, body, bearerToken = token)
        if (response.status == 401) {
            token = accessToken(forceRefresh = true)
            response = request(path, method, body, bearerToken = token)
        }
        when (response.status) {
            in 200..299 -> return response
            401 -> throw TraktException.RejectedAuthorization
            429 -> throw TraktException.RateLimited
            else -> throw TraktException.Http(response.status)
        }
    }

    @Synchronized
    private fun accessToken(forceRefresh: Boolean = false): String {
        val current = traktStore.loadTokens() ?: throw TraktException.NotConnected
        val now = System.currentTimeMillis() / 1_000L
        if (!forceRefresh && current.expiresAtEpochSeconds > now + REFRESH_SKEW_SECONDS) {
            return current.accessToken
        }

        val credentials = credentials()
        val response = request(
            path = "/oauth/token",
            method = "POST",
            body = JSONObject()
                .put("refresh_token", current.refreshToken)
                .put("client_id", credentials.clientId)
                .put("client_secret", credentials.clientSecret)
                .put("redirect_uri", credentials.redirectUri)
                .put("grant_type", "refresh_token"),
            includeApiHeaders = false,
        )
        if (response.status != 200) {
            if (response.status in setOf(400, 401, 403)) traktStore.clear()
            throw TraktException.RefreshFailed(response.status)
        }
        return response.objectBody().toTokens()
            .also(traktStore::saveTokens)
            .accessToken
    }

    private fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        bearerToken: String? = null,
        includeApiHeaders: Boolean = true,
    ): JsonHttpResponse {
        val settings = settingsStore.load()
        val headers = linkedMapOf<String, String>()
        if (includeApiHeaders) {
            headers["trakt-api-version"] = "2"
            headers["trakt-api-key"] = settings.traktClientId
        }
        if (!bearerToken.isNullOrBlank()) headers["Authorization"] = "Bearer $bearerToken"
        return try {
            JsonHttp.request(
                url = "$BASE_URL$path",
                method = method,
                body = body?.toString(),
                headers = headers,
            )
        } catch (error: IOException) {
            throw TraktException.Network(error.message ?: "Trakt network request failed.")
        }
    }

    private fun credentials(): Credentials {
        val settings = settingsStore.load()
        if (settings.traktClientId.isBlank() || settings.traktClientSecret.isBlank()) {
            throw TraktException.MissingCredentials
        }
        return Credentials(
            clientId = settings.traktClientId,
            clientSecret = settings.traktClientSecret,
            redirectUri = settings.traktRedirectUri.ifBlank { DEFAULT_TRAKT_REDIRECT_URI },
        )
    }

    private fun JsonHttpResponse.objectBody(): JSONObject =
        runCatching { JSONObject(body) }
            .getOrElse { throw TraktException.InvalidResponse("Trakt returned invalid JSON.") }

    private fun JsonHttpResponse.arrayBody(): JSONArray =
        runCatching { JSONArray(body) }
            .getOrElse { throw TraktException.InvalidResponse("Trakt returned invalid JSON.") }

    private fun JSONObject.toTokens(): TraktTokens {
        val createdAt = optLong("created_at").takeIf { it > 0 }
            ?: System.currentTimeMillis() / 1_000L
        val expiresIn = optLong("expires_in").coerceAtLeast(1L)
        return TraktTokens(
            accessToken = requiredString("access_token"),
            refreshToken = requiredString("refresh_token"),
            expiresAtEpochSeconds = createdAt + expiresIn,
        )
    }

    private fun JSONArray.toMediaItems(
        defaultType: MediaType,
        limit: Int = RESULT_LIMIT,
    ): List<MediaItem> =
        buildList {
            for (index in 0 until length()) {
                val wrapper = optJSONObject(index) ?: continue
                wrapper.toMediaItem(defaultType)?.let(::add)
            }
        }.distinctBy { it.key }
            .take(limit)

    private fun JSONArray.toPlaybackItems(): List<MediaItem> =
        buildList {
            for (index in 0 until length()) {
                val wrapper = optJSONObject(index) ?: continue
                wrapper.toPlaybackItem()?.let(::add)
            }
        }.distinctBy { it.key }
            .take(PLAYBACK_LIMIT)

    private fun JSONObject.toPlaybackItem(): MediaItem? {
        val item = toMediaItem(MediaType.MOVIE) ?: return null
        val progress = optDouble("progress")
            .takeIf { it.isFinite() && it > 0.0 && it < 100.0 }
            ?: return null
        val episode = optJSONObject("episode")
        val episodeLabel = episode?.let {
            val season = it.optInt("season").takeIf { value -> value >= 0 }
            val number = it.optInt("number").takeIf { value -> value > 0 }
            val position = if (season != null && number != null) {
                "S${season.toString().padStart(2, '0')} E${number.toString().padStart(2, '0')}"
            } else {
                null
            }
            listOfNotNull(position, it.optString("title").takeIf(String::isNotBlank))
                .joinToString(" · ")
                .takeIf(String::isNotBlank)
        }
        val context = listOfNotNull(
            episodeLabel,
            "${progress.roundToInt()}% watched",
        ).joinToString(" · ")
        return item.copy(
            progressPercent = progress,
            contextLabel = context,
        )
    }

    private fun JSONObject.toMediaItem(defaultType: MediaType): MediaItem? {
        val type = when {
            has("movie") -> MediaType.MOVIE
            has("show") -> MediaType.TV
            else -> defaultType
        }
        val media = when {
            type == MediaType.MOVIE && optJSONObject("movie") != null -> optJSONObject("movie")
            type == MediaType.TV && optJSONObject("show") != null -> optJSONObject("show")
            else -> this
        } ?: return null
        val ids = media.optJSONObject("ids") ?: return null
        val tmdbId = ids.optInt("tmdb")
        val title = media.optString("title")
        if (tmdbId <= 0 || title.isBlank()) return null

        val images = media.optJSONObject("images")
        return MediaItem(
            id = tmdbId,
            type = type,
            title = title,
            year = media.optInt("year").takeIf { it > 0 }?.toString().orEmpty(),
            posterUrl = images.firstMediaImage("poster"),
            backdropUrl = images.firstMediaImage("fanart"),
            overview = media.optString("overview"),
            rating = media.optDouble("rating").takeIf(Double::isFinite) ?: 0.0,
            imdbId = ids.optString("imdb")
                .takeIf { it.matches(IMDB_ID) },
        )
    }

    /**
     * Translates Trakt's genre slugs into TMDB genre ids.
     *
     * Both sides are needed on the same scale: the model keys features by TMDB genre id because
     * that is what catalog rows carry, so an imported title tagged `science-fiction` has to
     * arrive as the same feature a discovered one would. Television uses TMDB's own combined
     * buckets — `Sci-Fi & Fantasy`, `Action & Adventure` — rather than the film ids, or shows
     * and films would train two disconnected halves of the profile.
     */
    private fun JSONObject?.traktGenreIds(type: MediaType): List<Int> {
        val array = this?.optJSONArray("genres") ?: return emptyList()
        val ids = LinkedHashSet<Int>()
        for (index in 0 until array.length()) {
            val slug = array.optString(index).lowercase().takeIf(String::isNotBlank) ?: continue
            val mapped = if (type == MediaType.MOVIE) {
                MOVIE_GENRE_IDS[slug]
            } else {
                TV_GENRE_IDS[slug]
            }
            if (mapped != null) ids += mapped
        }
        return ids.toList()
    }

    private fun parseIsoMillis(value: String?): Long =
        value?.let { raw -> runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull() }
            ?: System.currentTimeMillis()

    private fun JSONObject?.firstMediaImage(key: String): String? {
        val value = this?.optJSONArray(key)?.optString(0).orEmpty()
        return UrlPolicy.canonicalMediaImage(value)
    }

    private fun JSONObject.requiredString(key: String): String =
        optString(key).takeIf(String::isNotBlank)
            ?: throw TraktException.InvalidResponse("Trakt response is missing $key.")

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private fun MediaType.traktPlural(): String =
        if (this == MediaType.MOVIE) "movies" else "shows"

    private data class Credentials(
        val clientId: String,
        val clientSecret: String,
        val redirectUri: String,
    )

    sealed class TraktException(message: String) : IOException(message) {
        data object MissingCredentials :
            TraktException("Add a Trakt client ID and secret in Settings.")

        data object NotConnected : TraktException("Connect a Trakt account first.")
        data object RejectedAuthorization :
            TraktException("Trakt rejected this authorization. Connect again.")

        data object RateLimited : TraktException("Trakt rate limit reached. Try again shortly.")
        data class RefreshFailed(val status: Int) :
            TraktException("Could not refresh Trakt authorization (HTTP $status).")

        data class Http(val status: Int) :
            TraktException("Trakt request failed (HTTP $status).")

        data class InvalidResponse(val detail: String) : TraktException(detail)
        data class Network(val detail: String) : TraktException(detail)
    }

    companion object {
        private const val BASE_URL = "https://api.trakt.tv"
        private const val RESULT_LIMIT = 18
        private const val PLAYBACK_LIMIT = 18
        private const val HISTORY_PAGE_SIZE = 100
        private const val HISTORY_MAX_PAGES = 10

        /** Genre ids shared by both TMDB taxonomies. */
        private val SHARED_GENRE_IDS = mapOf(
            "animation" to 16,
            "anime" to 16,
            "comedy" to 35,
            "crime" to 80,
            "documentary" to 99,
            "drama" to 18,
            "family" to 10751,
            "mystery" to 9648,
            "suspense" to 9648,
            "western" to 37,
        )

        private val MOVIE_GENRE_IDS = SHARED_GENRE_IDS + mapOf(
            "action" to 28,
            "adventure" to 12,
            "fantasy" to 14,
            "history" to 36,
            "holiday" to 10751,
            "horror" to 27,
            "music" to 10402,
            "musical" to 10402,
            "romance" to 10749,
            "science-fiction" to 878,
            "superhero" to 28,
            "thriller" to 53,
            "war" to 10752,
        )

        private val TV_GENRE_IDS = SHARED_GENRE_IDS + mapOf(
            "action" to 10759,
            "adventure" to 10759,
            "superhero" to 10759,
            "fantasy" to 10765,
            "science-fiction" to 10765,
            "horror" to 9648,
            "thriller" to 9648,
            "children" to 10762,
            "news" to 10763,
            "reality" to 10764,
            "soap" to 10766,
            "talk-show" to 10767,
            "game-show" to 10764,
            "war" to 10768,
            "history" to 10768,
            "romance" to 18,
            "music" to 10402,
            "musical" to 10402,
        )
        private const val REFRESH_SKEW_SECONDS = 5 * 60L
        private val IMDB_ID = Regex("""tt\d{5,12}""")
    }
}
