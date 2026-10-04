package dev.roesler.marquee.data

import kotlin.math.ln1p

/** One cast or crew credit before duplicate movie/show appearances are folded together. */
internal data class PersonCreditCandidate(
    val item: MediaItem,
    val popularity: Double,
    val voteCount: Int,
    val roleLabel: String?,
    val rolePriority: Double,
)

private data class CreditAccumulator(
    var item: MediaItem,
    var popularity: Double,
    var voteCount: Int,
    var rolePriority: Double,
    val roles: LinkedHashSet<String>,
    val firstSeen: Int,
)

/**
 * Builds a useful filmography instead of sorting obscure one-vote titles above signature work.
 * Cast and crew duplicates are merged, role labels survive for the poster caption, and a bounded
 * vote-confidence term balances quality with support while popularity represents recognizability.
 */
internal fun rankPersonCredits(
    candidates: List<PersonCreditCandidate>,
    limit: Int,
): List<MediaItem> {
    require(limit > 0) { "limit must be positive" }
    val merged = linkedMapOf<String, CreditAccumulator>()
    candidates.forEachIndexed { index, candidate ->
        if (candidate.item.posterUrl == null) return@forEachIndexed
        val popularity = candidate.popularity.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
        val priority = candidate.rolePriority.takeIf(Double::isFinite) ?: 0.0
        val accumulator = merged.getOrPut(candidate.item.key) {
            CreditAccumulator(
                item = candidate.item,
                popularity = popularity,
                voteCount = candidate.voteCount.coerceAtLeast(0),
                rolePriority = priority,
                roles = linkedSetOf(),
                firstSeen = index,
            )
        }
        if (accumulator.item.backdropUrl == null && candidate.item.backdropUrl != null) {
            accumulator.item = candidate.item
        }
        accumulator.popularity = maxOf(accumulator.popularity, popularity)
        accumulator.voteCount = maxOf(accumulator.voteCount, candidate.voteCount)
        accumulator.rolePriority = maxOf(accumulator.rolePriority, priority)
        candidate.roleLabel?.trim()?.takeIf(String::isNotBlank)?.let(accumulator.roles::add)
    }

    return merged.values
        .sortedWith(
            compareByDescending<CreditAccumulator> { creditScore(it) }
                .thenBy { it.firstSeen },
        )
        .take(limit)
        .map { credit ->
            credit.item.copy(
                contextLabel = credit.roles.take(2).joinToString(" · ").takeIf(String::isNotBlank),
            )
        }
}

private fun creditScore(credit: CreditAccumulator): Double {
    val confidence = credit.voteCount.toDouble() / (credit.voteCount + VOTE_CONFIDENCE_PRIOR)
    val quality = credit.item.rating.coerceIn(0.0, 10.0) * confidence
    val recognition = ln1p(credit.popularity) * POPULARITY_WEIGHT
    return quality + recognition + credit.rolePriority
}

private const val VOTE_CONFIDENCE_PRIOR = 250.0
private const val POPULARITY_WEIGHT = 0.55
