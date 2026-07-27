package dev.roesler.marquee.data

enum class MediaType(val apiName: String) {
    MOVIE("movie"),
    TV("tv");

    companion object {
        fun from(value: String?, hasMovieTitle: Boolean = false): MediaType =
            when (value) {
                "movie" -> MOVIE
                "tv" -> TV
                else -> if (hasMovieTitle) MOVIE else TV
            }
    }
}

data class MediaItem(
    val id: Int,
    val type: MediaType,
    val title: String,
    val year: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String,
    val rating: Double,
    val imdbId: String? = null,
    val progressPercent: Double? = null,
    val contextLabel: String? = null,
    val genreIds: List<Int> = emptyList(),
)

/** Stable identity for a title across TMDB, Trakt, and every local store. */
val MediaItem.key: String
    get() = "${type.apiName}:$id"

/** How the viewer rated a title once they had seen it. */
enum class Verdict(val storageName: String) {
    LIKED("liked"),
    DISLIKED("disliked"),
    ;

    companion object {
        fun from(value: String?): Verdict? = entries.firstOrNull { it.storageName == value }
    }
}

data class Person(
    val id: Int,
    val name: String,
    val photoUrl: String?,
    val knownFor: String,
)

data class MediaDetails(
    val item: MediaItem,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val seasons: Int?,
    val cast: List<Person> = emptyList(),
)

data class WatchProvider(
    val id: Int,
    val name: String,
    val logoUrl: String?,
    val packageName: String?,
    val access: String,
)

data class WatchOptions(
    val providers: List<WatchProvider>,
    val webLink: String?,
)

data class CatalogProvider(
    val id: Int,
    val name: String,
    val logoUrl: String?,
    val packageName: String?,
    val displayPriority: Int,
)

enum class ProviderSort {
    POPULAR,
    NEWEST,
    TOP_RATED,
}

data class MediaRow(
    val title: String,
    val items: List<MediaItem>,
    val subtitle: String? = null,
    val action: MediaRowAction = MediaRowAction.DETAILS,
    /**
     * Whether taste re-ranking may reorder this shelf. False for rows whose order is the point:
     * a watchlist, a continue-watching shelf, or a pool that was already ranked by affinity.
     */
    val personalize: Boolean = true,
)

enum class MediaRowAction {
    DETAILS,
    CONTINUE_LOCAL,
}

data class MarqueeSettings(
    val tmdbCredential: String = "",
    val region: String = "US",
    val preferredResolverPackage: String = "com.stremio.one",
    val traktClientId: String = "",
    val traktClientSecret: String = "",
    val traktRedirectUri: String = DEFAULT_TRAKT_REDIRECT_URI,
    val personalizedRanking: Boolean = true,
    val ratingPrompts: Boolean = true,
)

data class ScheduledShow(
    val title: String,
    val year: String,
    val imdbId: String?,
    val service: String?,
    val episodeLabel: String?,
)

const val DEFAULT_TRAKT_REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"
