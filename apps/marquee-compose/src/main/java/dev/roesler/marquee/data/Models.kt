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
)

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

data class MediaRow(
    val title: String,
    val items: List<MediaItem>,
)

data class MarqueeSettings(
    val tmdbCredential: String = "",
    val region: String = "US",
    val preferredResolverPackage: String = "com.stremio.one",
)
