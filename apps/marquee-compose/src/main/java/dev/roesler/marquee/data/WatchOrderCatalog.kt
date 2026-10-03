package dev.roesler.marquee.data

/** A movie that belongs to a hand-curated story order rather than simple release order. */
data class WatchOrderEntry(
    val tmdbId: Int,
    val title: String,
    val year: String,
    val imdbId: String,
) {
    val key: String
        get() = "${MediaType.MOVIE.apiName}:$tmdbId"

    fun asMediaItem(): MediaItem = MediaItem(
        id = tmdbId,
        type = MediaType.MOVIE,
        title = title,
        year = year,
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        rating = 0.0,
        imdbId = imdbId,
    )
}

/** The first unwatched title after a completed movie in a curated story sequence. */
data class WatchOrderSuggestion(
    val sequenceName: String,
    val entry: WatchOrderEntry,
    /** One-based position of [entry] in the sequence. */
    val position: Int,
    val total: Int,
)

private data class CuratedWatchOrder(
    val name: String,
    val entries: List<WatchOrderEntry>,
)

/**
 * Story-order overrides for universes that TMDB cannot represent as one release-order collection.
 *
 * IDs are TMDB identities, so translated titles and punctuation differences cannot break a match.
 * IMDb/title fallbacks keep imported or older playback records useful when their TMDB identity is
 * incomplete. Add new universes here without touching playback or UI code.
 */
object WatchOrderCatalog {
    private val orders = listOf(
        CuratedWatchOrder(
            name = "MonsterVerse",
            entries = listOf(
                // Narrative chronology requested by the viewer, not theatrical release order.
                WatchOrderEntry(293167, "Kong: Skull Island", "2017", "tt3731562"),
                WatchOrderEntry(124905, "Godzilla", "2014", "tt0831387"),
                WatchOrderEntry(
                    373571,
                    "Godzilla: King of the Monsters",
                    "2019",
                    "tt3741700",
                ),
                WatchOrderEntry(399566, "Godzilla vs. Kong", "2021", "tt5034838"),
                WatchOrderEntry(
                    823464,
                    "Godzilla x Kong: The New Empire",
                    "2024",
                    "tt14539740",
                ),
            ),
        ),
    )

    /**
     * Returns the next unseen movie after [item]. Already-watched entries are skipped, but only
     * forward: completing the last film never loops back to the start of a universe.
     */
    fun nextUnwatchedAfter(
        item: MediaItem,
        watchedKeys: Set<String> = emptySet(),
    ): WatchOrderSuggestion? {
        if (item.type != MediaType.MOVIE) return null
        orders.forEach { order ->
            val currentIndex = order.entries.indexOfFirst { it.matches(item) }
            if (currentIndex < 0) return@forEach
            val nextIndex = (currentIndex + 1 until order.entries.size)
                .firstOrNull { order.entries[it].key !in watchedKeys }
                ?: return null
            return WatchOrderSuggestion(
                sequenceName = order.name,
                entry = order.entries[nextIndex],
                position = nextIndex + 1,
                total = order.entries.size,
            )
        }
        return null
    }

    private fun WatchOrderEntry.matches(item: MediaItem): Boolean =
        item.id == tmdbId ||
            item.imdbId?.equals(imdbId, ignoreCase = true) == true ||
            (
                item.title.normalizedTitle() == title.normalizedTitle() &&
                    (item.year.isBlank() || item.year == year)
                )

    private fun String.normalizedTitle(): String = lowercase()
        .filter(Char::isLetterOrDigit)
}
