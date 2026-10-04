package dev.roesler.marquee.data

/**
 * Greedy maximal-marginal-relevance pass for an already relevant recommendation list.
 *
 * The first result always stays first. Later slots trade a small amount of source rank for genre,
 * format, and era variety, preventing one franchise or one narrow genre cluster from consuming an
 * entire personalized shelf. This is deterministic and does not need another network request.
 */
internal fun diversifyRecommendations(
    items: List<MediaItem>,
    limit: Int,
    diversityStrength: Double = DEFAULT_DIVERSITY_STRENGTH,
): List<MediaItem> {
    require(limit >= 0) { "limit must not be negative" }
    require(diversityStrength in 0.0..1.0) { "diversityStrength must be between zero and one" }
    if (limit == 0) return emptyList()

    val candidates = items.distinctBy(MediaItem::key)
    if (candidates.size <= 1 || diversityStrength == 0.0) return candidates.take(limit)

    val sourceRanks = candidates.withIndex().associate { it.value.key to it.index }
    val remaining = candidates.toMutableList()
    val selected = mutableListOf(remaining.removeAt(0))
    while (remaining.isNotEmpty() && selected.size < limit) {
        val best = remaining.maxByOrNull { candidate ->
            val rank = sourceRanks.getValue(candidate.key)
            val relevance = 1.0 / (1.0 + rank * RANK_DECAY)
            val redundancy = selected.maxOf { chosen -> similarity(candidate, chosen) }
            relevance - diversityStrength * redundancy - rank * STABLE_TIE_BREAK
        } ?: break
        selected += best
        remaining.remove(best)
    }
    return selected
}

private fun similarity(first: MediaItem, second: MediaItem): Double {
    val firstGenres = first.genreIds.toSet()
    val secondGenres = second.genreIds.toSet()
    val genreSimilarity = if (firstGenres.isEmpty() || secondGenres.isEmpty()) {
        0.0
    } else {
        firstGenres.intersect(secondGenres).size.toDouble() /
            firstGenres.union(secondGenres).size.toDouble()
    }
    val sameFormat = if (first.type == second.type) FORMAT_SIMILARITY else 0.0
    val firstDecade = first.year.toIntOrNull()?.div(10)
    val secondDecade = second.year.toIntOrNull()?.div(10)
    val sameEra = if (firstDecade != null && firstDecade == secondDecade) {
        ERA_SIMILARITY
    } else {
        0.0
    }
    return (genreSimilarity * GENRE_SIMILARITY_WEIGHT + sameFormat + sameEra)
        .coerceIn(0.0, 1.0)
}

private const val DEFAULT_DIVERSITY_STRENGTH = 0.38
private const val RANK_DECAY = 0.08
private const val GENRE_SIMILARITY_WEIGHT = 0.78
private const val FORMAT_SIMILARITY = 0.12
private const val ERA_SIMILARITY = 0.10
private const val STABLE_TIE_BREAK = 1e-9
