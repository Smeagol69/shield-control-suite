package dev.roesler.marquee.data

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** A single like/dislike the viewer gave a title they had already seen. */
data class TitleVerdict(
    val item: MediaItem,
    val verdict: Verdict,
    val ratedAtEpochMillis: Long,
)

/**
 * Signed affinities derived from every like and dislike, used to re-rank discovery.
 *
 * Each verdict contributes `±0.5^(ageDays / HALF_LIFE_DAYS)`, so a rating loses half of its
 * influence every six months and old opinions fade instead of being deleted. Raw sums are
 * squashed into `(-1, 1)` by [soften] so twenty ratings of one genre cannot drown out the rest
 * of the formula, and [confidence] shrinks the whole personalized term toward zero until enough
 * ratings exist to justify it.
 */
data class TasteProfile(
    val genreWeights: Map<Int, Double> = emptyMap(),
    val typeWeights: Map<MediaType, Double> = emptyMap(),
    val verdicts: Map<String, Verdict> = emptyMap(),
    val likedWeight: Double = 0.0,
    val dislikedWeight: Double = 0.0,
    val preferredYear: Double? = null,
) {
    val likedKeys: Set<String>
        get() = verdicts.filterValues { it == Verdict.LIKED }.keys

    val ratingCount: Int
        get() = verdicts.size

    /** Decayed evidence behind the profile; fresh ratings count more than stale ones. */
    val ratedWeight: Double
        get() = likedWeight + dislikedWeight

    /**
     * How far to trust the personalized terms, in `[0, 1)`. Six fresh ratings reach 0.5, so a
     * brand-new profile leaves every catalog ordering exactly as the source returned it.
     */
    val confidence: Double
        get() = ratedWeight / (ratedWeight + CONFIDENCE_HALF_WEIGHT)

    val established: Boolean
        get() = ratingCount >= MIN_RATINGS_FOR_PERSONALIZATION

    fun verdictOf(item: MediaItem): Verdict? = verdicts[item.key]

    /** Genre ids the viewer likes most, strongest first, for labelling personalized rows. */
    fun topGenreIds(limit: Int = 3): List<Int> =
        genreWeights.entries
            .filter { it.value > 0.0 }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }

    /**
     * Taste-only affinity for a title, in roughly `[-1, 1]`, before any source ordering is
     * considered. Already-watched titles are pushed down so discovery keeps surfacing new
     * things without hiding a title the viewer may want to rewatch.
     */
    fun affinityOf(item: MediaItem, watched: Boolean = false): Double {
        val genre = item.genreIds
            .takeIf(List<Int>::isNotEmpty)
            ?.let { ids ->
                ids.sumOf { genreWeights[it] ?: 0.0 } / sqrt(ids.size.toDouble())
            }
            ?.coerceIn(-1.0, 1.0)
            ?: 0.0
        val type = typeWeights[item.type] ?: 0.0
        val quality = ((item.rating - NEUTRAL_RATING) / RATING_SPAN).coerceIn(-1.0, 1.0)
        val era = preferredYear
            ?.let { preferred ->
                item.year.toIntOrNull()?.let { year ->
                    (1.0 - abs(year - preferred) / ERA_SPAN).coerceIn(-1.0, 1.0)
                }
            }
            ?: 0.0

        val affinity = GENRE_WEIGHT * genre +
            TYPE_WEIGHT * type +
            QUALITY_WEIGHT * quality +
            ERA_WEIGHT * era
        return if (watched) affinity - REWATCH_PENALTY else affinity
    }

    /**
     * Re-ranks a catalog row while preserving what the source already knew.
     *
     * Source position keeps a full point of range and taste adds
     * `TASTE_INFLUENCE * confidence * affinity` on top, so the two compete on a known scale: an
     * unrated profile returns [items] untouched, and a well-trained one lifts a strong match by
     * roughly a third of the row rather than re-sorting it outright. That matters because the
     * row already means something — "popular on Netflix" should still look popular.
     */
    fun rank(items: List<MediaItem>, watchedKeys: Set<String> = emptySet()): List<MediaItem> {
        if (items.size < 2) return withoutDisliked(items)
        val candidates = withoutDisliked(items)
        if (!established) return candidates
        val influence = TASTE_INFLUENCE * confidence
        val span = candidates.size.toDouble()
        return candidates
            .mapIndexed { index, item ->
                val position = 1.0 - index / span
                item to (position + influence * affinityOf(item, item.key in watchedKeys))
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Orders a recommendation pool purely by taste. Used where the source order carries no
     * meaning of its own, such as a `Because you liked …` pool merged from several endpoints.
     */
    fun rankByAffinity(
        items: List<MediaItem>,
        watchedKeys: Set<String> = emptySet(),
    ): List<MediaItem> =
        withoutDisliked(items)
            .map { it to affinityOf(it, it.key in watchedKeys) }
            .sortedByDescending { it.second }
            .map { it.first }

    /** Drops titles the viewer explicitly disliked; they never belong in a suggestion. */
    fun withoutDisliked(items: List<MediaItem>): List<MediaItem> {
        if (dislikedWeight <= 0.0) return items
        return items.filter { verdicts[it.key] != Verdict.DISLIKED }
    }

    companion object {
        /** A rating keeps half of its influence after this many days. */
        const val HALF_LIFE_DAYS = 180.0

        /** Decayed rating weight at which [confidence] reaches one half. */
        const val CONFIDENCE_HALF_WEIGHT = 6.0

        /** Ratings needed before catalog rows are re-ordered at all. */
        const val MIN_RATINGS_FOR_PERSONALIZATION = 3

        /** Scale of the taste term against a position term that spans 1.0. */
        const val TASTE_INFLUENCE = 1.0

        private const val GENRE_WEIGHT = 0.50
        private const val TYPE_WEIGHT = 0.18
        private const val QUALITY_WEIGHT = 0.20
        private const val ERA_WEIGHT = 0.12
        private const val NEUTRAL_RATING = 6.5
        private const val RATING_SPAN = 2.5
        private const val ERA_SPAN = 25.0
        private const val REWATCH_PENALTY = 0.35
        private const val MILLIS_PER_DAY = 24.0 * 60.0 * 60.0 * 1_000.0

        /** Squashes an unbounded signed sum into `(-1, 1)` without a hard cut-off. */
        internal fun soften(raw: Double, saturation: Double): Double =
            raw / (abs(raw) + saturation)

        internal fun decayWeight(ratedAtEpochMillis: Long, now: Long): Double {
            val ageDays = ((now - ratedAtEpochMillis) / MILLIS_PER_DAY).coerceAtLeast(0.0)
            return 0.5.pow(ageDays / HALF_LIFE_DAYS)
        }
    }
}

/** Rebuilds the whole profile from stored verdicts. Cheap enough to run on every rating. */
fun buildTasteProfile(
    verdicts: Collection<TitleVerdict>,
    now: Long = System.currentTimeMillis(),
): TasteProfile {
    if (verdicts.isEmpty()) return TasteProfile()

    val rawGenres = mutableMapOf<Int, Double>()
    val rawTypes = mutableMapOf<MediaType, Double>()
    val recorded = LinkedHashMap<String, Verdict>(verdicts.size)
    var likedWeight = 0.0
    var dislikedWeight = 0.0
    var likedYearTotal = 0.0
    var likedYearWeight = 0.0

    verdicts.forEach { verdict ->
        val weight = TasteProfile.decayWeight(verdict.ratedAtEpochMillis, now)
        val signed = if (verdict.verdict == Verdict.LIKED) weight else -weight
        recorded[verdict.item.key] = verdict.verdict
        if (verdict.verdict == Verdict.LIKED) likedWeight += weight else dislikedWeight += weight

        val genreIds = verdict.item.genreIds
        if (genreIds.isNotEmpty()) {
            // Spread one verdict across its genres so a six-genre title does not outvote a
            // single-genre one; sqrt keeps a broad title meaningful without letting it dominate.
            val share = signed / sqrt(genreIds.size.toDouble())
            genreIds.forEach { id -> rawGenres[id] = (rawGenres[id] ?: 0.0) + share }
        }
        rawTypes[verdict.item.type] = (rawTypes[verdict.item.type] ?: 0.0) + signed

        if (verdict.verdict == Verdict.LIKED) {
            verdict.item.year.toIntOrNull()?.let { year ->
                likedYearTotal += year * weight
                likedYearWeight += weight
            }
        }
    }

    return TasteProfile(
        genreWeights = rawGenres.mapValues { (_, raw) ->
            TasteProfile.soften(raw, GENRE_SATURATION)
        },
        typeWeights = rawTypes.mapValues { (_, raw) ->
            TasteProfile.soften(raw, TYPE_SATURATION)
        },
        verdicts = recorded,
        likedWeight = likedWeight,
        dislikedWeight = dislikedWeight,
        preferredYear = (likedYearTotal / likedYearWeight).takeIf { likedYearWeight > 0.0 },
    )
}

private const val GENRE_SATURATION = 2.0
private const val TYPE_SATURATION = 4.0
