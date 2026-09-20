package dev.roesler.marquee.data

import java.time.LocalDate

/** Which axis of the catalog the Browse screen is currently cutting along. */
enum class BrowseAxis(val label: String) {
    DECADE("Decade"),
    MOOD("Mood"),
    LENGTH("Length"),
}

/**
 * One selectable chip, carrying the query it stands for.
 *
 * Keeping the parameters next to the label means a facet is defined once rather than as a label
 * here and a query somewhere else that can drift away from it.
 */
data class BrowseFacet(
    val id: String,
    val label: String,
    val axis: BrowseAxis,
    /** Extra query terms; type-specific date fields are filled in by [parametersFor]. */
    val parameters: Map<String, String> = emptyMap(),
    /** Inclusive decade start, when this facet is an era. */
    val decade: Int? = null,
    /** Promise to show under each poster, where the API cannot confirm it per title. */
    val contextLabel: String? = null,
) {
    /**
     * Query terms for one media type.
     *
     * Era bounds have to be written per type because TMDB names the field differently for films
     * and series, and films must use `primary_release_date` rather than `release_date`: the
     * client injects a region, and on /discover/movie region is not inert - it decides which
     * country's release dates `release_date.*` resolves against, so the same query would return
     * different films in different regions. The primary date is global and stable.
     */
    fun parametersFor(type: MediaType): Map<String, String> {
        val query = LinkedHashMap(parameters)
        decade?.let { start ->
            val (from, to) = if (type == MediaType.MOVIE) {
                "primary_release_date.gte" to "primary_release_date.lte"
            } else {
                "first_air_date.gte" to "first_air_date.lte"
            }
            query[from] = "$start-01-01"
            query[to] = if (start >= LocalDate.now().year - 9) {
                LocalDate.now().toString()
            } else {
                "${start + 9}-12-31"
            }
        }
        return query
    }
}

object BrowseFacets {
    /**
     * Eras are sorted by vote count, not popularity.
     *
     * `popularity.desc` returns whichever 1994 film happens to be trending on a streamer this
     * week; `vote_count.desc` returns what the decade is actually remembered for, which is the
     * only reason to browse by decade at all.
     */
    private const val ERA_SORT = "vote_count.desc"
    private const val ERA_VOTE_FLOOR = "100"

    /**
     * Keyword ids are stable TMDB identifiers. A wrong one yields a silently empty shelf rather
     * than an error, so these are limited to ids that have been verified rather than guessed.
     */
    private const val KEYWORD_TIME_TRAVEL = "4379"
    private const val KEYWORD_TRUE_STORY = "9672"
    private const val KEYWORD_COMING_OF_AGE = "10683"
    private const val KEYWORD_SUPERHERO = "9715"
    private const val KEYWORD_ANIME = "210024"
    private const val KEYWORD_CHRISTMAS = "207317"

    private fun mood(id: String, label: String, extra: Map<String, String>) = BrowseFacet(
        id = id,
        label = label,
        axis = BrowseAxis.MOOD,
        parameters = extra + mapOf("sort_by" to "vote_count.desc", "vote_count.gte" to "150"),
    )

    fun decades(today: LocalDate = LocalDate.now()): List<BrowseFacet> {
        val current = (today.year / 10) * 10
        return (0 until DECADE_COUNT).map { step ->
            val start = current - step * 10
            BrowseFacet(
                id = "decade-$start",
                label = "${start}s",
                axis = BrowseAxis.DECADE,
                parameters = mapOf("sort_by" to ERA_SORT, "vote_count.gte" to ERA_VOTE_FLOOR),
                decade = start,
            )
        }
    }

    val moods: List<BrowseFacet> = listOf(
        mood("mood-time-travel", "Time travel", mapOf("with_keywords" to KEYWORD_TIME_TRAVEL)),
        mood("mood-true-story", "Based on truth", mapOf("with_keywords" to KEYWORD_TRUE_STORY)),
        mood("mood-coming-of-age", "Coming of age", mapOf("with_keywords" to KEYWORD_COMING_OF_AGE)),
        mood("mood-superhero", "Superhero", mapOf("with_keywords" to KEYWORD_SUPERHERO)),
        // Genre 16 alone is dominated by Western family animation; the keyword is what actually
        // means "anime" to a viewer asking for it.
        mood("mood-anime", "Anime", mapOf("with_keywords" to KEYWORD_ANIME, "with_genres" to "16")),
        mood("mood-christmas", "Festive", mapOf("with_keywords" to KEYWORD_CHRISTMAS)),
    )

    /**
     * Length facets.
     *
     * The lower bound is load-bearing: without it "under 100 minutes" fills with shorts, concert
     * films and stand-up specials. Discover responses carry no runtime field, so the promise is
     * written into the caption rather than re-verified per poster, which would cost one detail
     * request each.
     */
    val lengths: List<BrowseFacet> = listOf(
        BrowseFacet(
            id = "length-short",
            label = "Under 100 min",
            axis = BrowseAxis.LENGTH,
            parameters = mapOf(
                "with_runtime.gte" to "60",
                "with_runtime.lte" to "100",
                "sort_by" to "vote_count.desc",
                "vote_count.gte" to "100",
            ),
            contextLabel = "Under 100 min",
        ),
        BrowseFacet(
            id = "length-epic",
            label = "Over 2 hours",
            axis = BrowseAxis.LENGTH,
            parameters = mapOf(
                "with_runtime.gte" to "120",
                "sort_by" to "vote_count.desc",
                "vote_count.gte" to "100",
            ),
            contextLabel = "Over 2 hours",
        ),
    )

    fun all(today: LocalDate = LocalDate.now()): List<BrowseFacet> =
        decades(today) + moods + lengths

    fun forAxis(axis: BrowseAxis, today: LocalDate = LocalDate.now()): List<BrowseFacet> =
        when (axis) {
            BrowseAxis.DECADE -> decades(today)
            BrowseAxis.MOOD -> moods
            BrowseAxis.LENGTH -> lengths
        }

    /**
     * Which decade to open on.
     *
     * The learner has been computing an era preference since the day it was added and has never
     * had anywhere to say it. Reading that weight here means the screen opens on the viewer's own
     * decade with nothing to configure, and falls back to the present when it has no opinion.
     */
    fun defaultDecade(model: TasteModel, today: LocalDate = LocalDate.now()): BrowseFacet {
        val available = decades(today)
        val preferred = model.weights.entries
            .asSequence()
            .filter { it.key.startsWith("d:") && it.value > 0.0 }
            .maxByOrNull { it.value }
            ?.key
            ?.removePrefix("d:")
            ?.toIntOrNull()
        return available.firstOrNull { it.decade == preferred } ?: available.first()
    }

    private const val DECADE_COUNT = 7
}
