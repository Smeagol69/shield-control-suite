package dev.roesler.marquee.data

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A title expressed as sparse features the learner can weigh independently.
 *
 * Keys are namespaced strings (`g:28`, `t:movie`, `d:2010`) so the model can hold an opinion
 * about any of them without a fixed schema — new genres, new decades, and new pairings simply
 * appear as the catalog produces them. Values are small and mostly normalized to keep the
 * gradient steps of one title comparable to another's.
 */
typealias FeatureVector = Map<String, Double>

object TasteFeatures {
    /**
     * Builds the feature vector for a title.
     *
     * Genre mass is spread by `1/sqrt(n)` so a six-genre title cannot shout down a focused one,
     * and genre *pairs* are emitted alongside the singles: liking sci-fi and liking comedy is a
     * different statement from liking sci-fi comedies, and a purely additive model can never
     * express the difference. Pairs are what let the profile capture "I want funny space films,
     * not grim ones" instead of averaging the two into mush.
     */
    fun of(item: MediaItem): FeatureVector {
        val features = HashMap<String, Double>(16)
        features[BIAS] = 1.0

        val genres = item.genreIds.distinct()
        if (genres.isNotEmpty()) {
            val share = 1.0 / sqrt(genres.size.toDouble())
            genres.forEach { id -> features["g:$id"] = share }
            if (genres.size in 2..MAX_GENRES_FOR_PAIRS) {
                val sorted = genres.sorted()
                val pairShare = 1.0 / genres.size.toDouble()
                for (i in sorted.indices) {
                    for (j in i + 1 until sorted.size) {
                        features["gp:${sorted[i]}_${sorted[j]}"] = pairShare
                    }
                }
            }
        }

        features["t:${item.type.apiName}"] = 1.0

        item.year.toIntOrNull()?.let { year ->
            val decade = (year / 10) * 10
            features["d:$decade"] = 1.0
            // A continuous recency term as well, so the model can learn "newer is better" once
            // instead of having to learn it separately for every decade bucket it has seen.
            features[RECENCY] = ((year - ERA_PIVOT) / ERA_SPAN).coerceIn(-1.5, 1.5)
        }

        // Crowd quality, centred so an average title contributes nothing and the weight the
        // model learns says how much *this viewer* actually cares what everyone else thinks.
        if (item.rating > 0.0) {
            features[QUALITY] = ((item.rating - NEUTRAL_RATING) / RATING_SPAN).coerceIn(-1.5, 1.5)
        }
        return features
    }

    /** Dot product of a sparse vector with a weight table; missing weights are zero. */
    fun dot(features: FeatureVector, weights: Map<String, Double>): Double {
        var sum = 0.0
        for ((key, value) in features) {
            val weight = weights[key] ?: continue
            sum += weight * value
        }
        return sum
    }

    /**
     * Cosine-style overlap between two titles, used to keep a shelf from becoming eight
     * variations of the same film. Only the categorical keys count — two titles are not
     * "similar" because both happen to be well rated.
     */
    fun similarity(first: FeatureVector, second: FeatureVector): Double {
        var dot = 0.0
        var firstNorm = 0.0
        var secondNorm = 0.0
        for ((key, value) in first) {
            if (!isCategorical(key)) continue
            firstNorm += value * value
            val other = second[key] ?: continue
            dot += value * other
        }
        for ((key, value) in second) {
            if (!isCategorical(key)) continue
            secondNorm += value * value
        }
        if (firstNorm <= 0.0 || secondNorm <= 0.0) return 0.0
        return (dot / sqrt(firstNorm * secondNorm)).coerceIn(0.0, 1.0)
    }

    private fun isCategorical(key: String): Boolean =
        key != BIAS && key != QUALITY && key != RECENCY

    const val BIAS = "bias"
    const val QUALITY = "q"
    const val RECENCY = "recency"

    private const val MAX_GENRES_FOR_PAIRS = 6
    private const val NEUTRAL_RATING = 6.5
    private const val RATING_SPAN = 2.5
    private const val ERA_PIVOT = 2010.0
    private const val ERA_SPAN = 25.0
}

/** Stable pseudo-random value in `[0, 1)` for a key, fixed for a given day. */
internal fun dailyJitter(key: String, dayIndex: Long): Double {
    var hash = key.hashCode().toLong() * 0x9E3779B97F4A7C15uL.toLong()
    hash = hash xor (dayIndex * 0xBF58476D1CE4E5B9uL.toLong())
    hash = hash xor (hash ushr 30)
    hash *= 0xBF58476D1CE4E5B9uL.toLong()
    hash = hash xor (hash ushr 27)
    hash *= 0x94D049BB133111EBuL.toLong()
    hash = hash xor (hash ushr 31)
    return abs(hash % 100_000L) / 100_000.0
}
