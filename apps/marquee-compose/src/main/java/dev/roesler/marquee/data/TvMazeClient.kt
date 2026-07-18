package dev.roesler.marquee.data

import org.json.JSONArray
import java.io.IOException
import java.time.LocalDate

class TvMazeClient {
    fun streamingToday(): List<ScheduledShow> {
        val date = LocalDate.now().toString()
        var response = request("$BASE_URL/schedule/web?date=$date")
        if (response.status == 429) {
            Thread.sleep((response.retryAfterSeconds ?: 2).coerceIn(1, 5) * 1_000L)
            response = request("$BASE_URL/schedule/web?date=$date")
        }
        if (response.status !in 200..299) throw TvMazeException.Http(response.status)

        val entries = runCatching { JSONArray(response.body) }
            .getOrElse { throw TvMazeException.InvalidResponse }
        val seenShows = hashSetOf<Int>()
        val candidates = buildList {
            for (index in 0 until entries.length()) {
                val episode = entries.optJSONObject(index) ?: continue
                val show = episode.optJSONObject("_embedded")?.optJSONObject("show")
                    ?: episode.optJSONObject("show")
                    ?: continue
                val showId = show.optInt("id")
                val title = show.optString("name")
                if (showId <= 0 || title.isBlank() || !seenShows.add(showId)) continue

                val season = episode.optInt("season").takeIf { it > 0 }
                val number = episode.optInt("number").takeIf { it > 0 }
                val service = show.optJSONObject("webChannel")?.optString("name")
                    ?.takeIf(String::isNotBlank)
                    ?: show.optJSONObject("network")?.optString("name")?.takeIf(String::isNotBlank)
                add(
                    Candidate(
                        schedule = ScheduledShow(
                            title = title,
                            year = show.optString("premiered").take(4),
                            imdbId = show.optJSONObject("externals")
                                ?.optString("imdb")
                                ?.takeIf(String::isNotBlank),
                            service = service,
                            episodeLabel = when {
                                season != null && number != null -> "S${season}E$number"
                                episode.optString("name").isNotBlank() -> episode.optString("name")
                                else -> null
                            },
                        ),
                        serviceRank = service?.let(::serviceRank) ?: SERVICE_FALLBACK_RANK,
                        english = show.optString("language").equals("English", ignoreCase = true),
                        weight = show.optInt("weight"),
                    ),
                )
            }
        }
        return candidates
            .sortedWith(
                compareBy<Candidate> { it.serviceRank }
                    .thenByDescending(Candidate::english)
                    .thenByDescending(Candidate::weight),
            )
            .map(Candidate::schedule)
            .take(RESULT_LIMIT)
    }

    private fun serviceRank(service: String): Int {
        val normalized = service.lowercase()
        return PREFERRED_SERVICES.indexOfFirst { it in normalized }
            .takeIf { it >= 0 }
            ?: SERVICE_FALLBACK_RANK
    }

    private data class Candidate(
        val schedule: ScheduledShow,
        val serviceRank: Int,
        val english: Boolean,
        val weight: Int,
    )

    private fun request(url: String): JsonHttpResponse =
        try {
            JsonHttp.request(url)
        } catch (error: IOException) {
            throw TvMazeException.Network(error.message ?: "TVmaze network request failed.")
        }

    sealed class TvMazeException(message: String) : IOException(message) {
        data object InvalidResponse : TvMazeException("TVmaze returned invalid schedule data.")
        data class Http(val status: Int) :
            TvMazeException("TVmaze schedule request failed (HTTP $status).")

        data class Network(val detail: String) : TvMazeException(detail)
    }

    companion object {
        private const val BASE_URL = "https://api.tvmaze.com"
        private const val RESULT_LIMIT = 12
        private const val SERVICE_FALLBACK_RANK = 1_000
        private val PREFERRED_SERVICES = listOf(
            "netflix",
            "disney",
            "hulu",
            "max",
            "amazon",
            "prime video",
            "apple tv",
            "paramount",
            "peacock",
            "showtime",
            "starz",
            "crunchyroll",
        )
    }
}
