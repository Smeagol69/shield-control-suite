package dev.roesler.marquee.data

/** One ranked recommendation list and the importance of the behaviour that produced it. */
data class WeightedRecommendationList(
    val items: List<MediaItem>,
    val weight: Double = 1.0,
)

/** A recommendation merged across independent seeds, with agreement exposed for UI context. */
data class FusedRecommendation(
    val item: MediaItem,
    val score: Double,
    val sourceCount: Int,
)

/**
 * Reciprocal-rank fusion for recommendation lists from several recently watched titles.
 *
 * Raw TMDB similarity scores are not calibrated across endpoints, so adding those values would
 * make one unusually scored response dominate the rest. Reciprocal rank uses only each list's
 * ordering. A candidate suggested by several seeds naturally beats a one-off suggestion, while
 * [WeightedRecommendationList.weight] lets the newest viewing carry more short-term context.
 */
fun reciprocalRankFusion(
    lists: List<WeightedRecommendationList>,
    rankConstant: Double = DEFAULT_RANK_CONSTANT,
): List<FusedRecommendation> {
    require(rankConstant > 0.0) { "rankConstant must be positive" }
    data class Accumulator(
        var item: MediaItem,
        var score: Double = 0.0,
        var sourceCount: Int = 0,
        val firstSeen: Int,
    )

    val fused = LinkedHashMap<String, Accumulator>()
    var encounter = 0
    lists.forEach { list ->
        if (list.weight <= 0.0) return@forEach
        list.items.distinctBy(MediaItem::key).forEachIndexed { index, item ->
            val accumulator = fused.getOrPut(item.key) {
                Accumulator(item = item, firstSeen = encounter++)
            }
            // Keep the richer copy when one endpoint omitted artwork.
            if (accumulator.item.posterUrl == null && item.posterUrl != null) {
                accumulator.item = item
            }
            accumulator.score += list.weight / (rankConstant + index + 1.0)
            accumulator.sourceCount += 1
        }
    }
    return fused.values
        .sortedWith(
            compareByDescending<Accumulator> { it.score }
                .thenBy { it.firstSeen },
        )
        .map { FusedRecommendation(it.item, it.score, it.sourceCount) }
}

private const val DEFAULT_RANK_CONSTANT = 12.0
