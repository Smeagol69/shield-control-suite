package dev.roesler.marquee.data

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * An online logistic model of what this viewer enjoys, learned from their own behaviour.
 *
 * Where [TasteProfile] applies fixed hand-chosen weights (genre counts for half, era for an
 * eighth, and so on), this learns the weights themselves. Every rating, finish, abandon, and
 * rewatch is one labelled example; the model nudges each feature it saw in the direction that
 * would have predicted that outcome better, so the importance of genre versus era versus crowd
 * rating is discovered per viewer rather than assumed.
 *
 * Steps are scaled per feature in the AdaGrad style — a feature's accumulated gradient acts as
 * its own learning-rate divisor — which matters here because evidence is wildly uneven: a viewer
 * may have three hundred observations touching `g:18` and two touching `g:99`, and a single
 * global step size would either crawl on the former or thrash on the latter. The accumulators
 * double as a familiarity measure, which is what [noveltyOf] reads to decide where the model is
 * still guessing and exploration is worth spending a slot on.
 */
data class TasteModel(
    val weights: Map<String, Double> = emptyMap(),
    val accumulators: Map<String, Double> = emptyMap(),
    val evidence: Double = 0.0,
    val observations: Int = 0,
    val coverage: Int = 0,
    val trainedAtEpochMillis: Long = 0L,
) {
    /** How far the learned model may be trusted, in `[0, 1)`. */
    val confidence: Double
        get() = evidence / (evidence + CONFIDENCE_HALF_EVIDENCE)

    val trained: Boolean
        get() = observations >= MIN_OBSERVATIONS && weights.isNotEmpty()

    /** Probability this viewer wants more of a title, in `(0, 1)`. */
    fun probabilityOf(item: MediaItem): Double =
        sigmoid(TasteFeatures.dot(TasteFeatures.of(item), weights))

    /**
     * Predicted appetite centred on zero, so it composes with the rest of the ranking on the
     * same `[-1, 1]` scale the hand-tuned profile already uses.
     */
    fun scoreOf(item: MediaItem, watched: Boolean = false): Double {
        val centred = 2.0 * probabilityOf(item) - 1.0
        return if (watched) centred - REWATCH_PENALTY else centred
    }

    /**
     * How little the model knows about a title's features, in `[0, 1]`. A title made entirely of
     * things the viewer has never engaged with scores near one; a familiar combination near zero.
     */
    fun noveltyOf(item: MediaItem): Double {
        val features = TasteFeatures.of(item)
        var mass = 0.0
        var familiar = 0.0
        for ((key, value) in features) {
            if (key == TasteFeatures.BIAS) continue
            val magnitude = kotlin.math.abs(value)
            if (magnitude <= 0.0) continue
            val seen = accumulators[key] ?: 0.0
            mass += magnitude
            familiar += magnitude * (seen / (seen + FAMILIARITY_HALF))
        }
        if (mass <= 0.0) return 1.0
        return (1.0 - familiar / mass).coerceIn(0.0, 1.0)
    }

    /**
     * Trains on the full signal set, returning the updated model.
     *
     * The whole history is replayed for a few passes rather than updated once per event: it is
     * cheap at this scale, it makes the result independent of the order events happened to
     * arrive in, and it lets a freshly imported history take effect immediately instead of after
     * the next few hundred plays. A fixed shuffle seed keeps the outcome reproducible.
     */
    fun trainedOn(signals: List<TasteSignal>, now: Long = System.currentTimeMillis()): TasteModel {
        if (signals.isEmpty()) return TasteModel()
        val effective = effectiveWeights(signals, now)
        val vectors = signals.map { TasteFeatures.of(it.item) }
        val weights = HashMap(this.weights)
        val accumulators = HashMap(this.accumulators)
        val order = signals.indices.sortedBy { index ->
            dailyJitter(signals[index].item.key, SHUFFLE_SEED)
        }

        repeat(EPOCHS) {
            order.forEach { index ->
                val exampleWeight = effective[index]
                if (exampleWeight <= 0.0) return@forEach
                val features = vectors[index]
                val prediction = sigmoid(TasteFeatures.dot(features, weights))
                val error = prediction - signals[index].label
                for ((key, value) in features) {
                    if (value == 0.0) continue
                    val current = weights[key] ?: 0.0
                    val gradient = exampleWeight * error * value + L2 * current
                    val accumulated = (accumulators[key] ?: 0.0) + gradient * gradient
                    accumulators[key] = accumulated
                    weights[key] = current - LEARNING_RATE * gradient / (sqrt(accumulated) + EPSILON)
                }
            }
        }

        return TasteModel(
            weights = weights,
            accumulators = accumulators,
            evidence = evidenceOf(signals, now),
            observations = signals.size,
            coverage = signalCoverage(signals),
            trainedAtEpochMillis = now,
        )
    }

    /**
     * Orders a shelf: learned appetite, a little curiosity, and a nudge against sameness.
     *
     * `preserveSourceOrder` keeps a full point of range for the position the source chose, which
     * is what stops "Top rated" from quietly becoming a second copy of the recommendations row —
     * the shelf still has to mean what its title says. Pools assembled by Marquee itself carry no
     * such meaning and are ranked on appetite alone.
     */
    fun rank(
        items: List<MediaItem>,
        watchedKeys: Set<String> = emptySet(),
        profile: TasteProfile? = null,
        preserveSourceOrder: Boolean = true,
        dayIndex: Long = System.currentTimeMillis() / MILLIS_PER_DAY,
    ): List<MediaItem> {
        if (items.size < 2 || !trained) return items
        val span = items.size.toDouble()
        val influence = TASTE_INFLUENCE * confidence
        val scored = items.mapIndexed { index, item ->
            val watched = item.key in watchedKeys
            val learned = scoreOf(item, watched)
            // The hand-tuned profile still votes while the model is thin, and fades out as
            // evidence accumulates — a cold start should not be a random start.
            val prior = profile?.affinityOf(item, watched) ?: 0.0
            val blended = confidence * learned + (1.0 - confidence) * prior
            val curiosity = EXPLORATION * noveltyOf(item) * dailyJitter(item.key, dayIndex)
            val position = if (preserveSourceOrder) 1.0 - index / span else 0.0
            ScoredTitle(
                item = item,
                score = position + influence * blended + curiosity,
                features = TasteFeatures.of(item),
            )
        }.sortedByDescending(ScoredTitle::score)
        return diversify(scored)
    }

    /**
     * Greedy maximal-marginal-relevance pass: each slot goes to the best remaining title after
     * subtracting how much it resembles what is already on the shelf. Without it a trained model
     * happily returns eight near-identical films, which reads as a narrower app rather than a
     * smarter one.
     */
    private fun diversify(scored: List<ScoredTitle>): List<MediaItem> {
        if (scored.size <= 2) return scored.map(ScoredTitle::item)
        val remaining = ArrayList(scored)
        val chosen = ArrayList<ScoredTitle>(scored.size)
        chosen += remaining.removeAt(0)
        while (remaining.isNotEmpty()) {
            var bestIndex = 0
            var bestValue = Double.NEGATIVE_INFINITY
            for (index in remaining.indices) {
                val candidate = remaining[index]
                var maxSimilarity = 0.0
                val window = chosen.takeLast(DIVERSITY_WINDOW)
                for (picked in window) {
                    val similarity = TasteFeatures.similarity(candidate.features, picked.features)
                    if (similarity > maxSimilarity) maxSimilarity = similarity
                }
                val value = candidate.score - DIVERSITY_PENALTY * maxSimilarity
                if (value > bestValue) {
                    bestValue = value
                    bestIndex = index
                }
            }
            chosen += remaining.removeAt(bestIndex)
        }
        return chosen.map(ScoredTitle::item)
    }

    /** Features the model has learned to favour, strongest first, for explaining a row. */
    /**
     * The learned profile expressed as a catalog query rather than a scoring function.
     *
     * Re-ranking can only reorder what an endpoint already returned; if the viewer's taste is
     * seventies science fiction, "Popular movies" contains almost none of it and no reordering
     * will conjure any. Turning the weights into a *query* lets discovery go and look for the
     * thing instead of waiting for it to show up.
     *
     * Negative weights are as useful as positive ones here: a genre the model has learned to
     * dislike is excluded at the source, which is far cheaper than fetching it and filtering it
     * out afterwards, and leaves more of the page for things worth seeing.
     */
    fun tasteQuery(limit: Int = QUERY_GENRE_LIMIT): TasteQuery {
        val positives = weights.entries
            .asSequence()
            .filter { it.key.startsWith("g:") && it.value > QUERY_MIN_WEIGHT }
            .sortedByDescending { it.value }
            .mapNotNull { it.key.removePrefix("g:").toIntOrNull() }
            .take(limit)
            .toList()
        val negatives = weights.entries
            .asSequence()
            .filter { it.key.startsWith("g:") && it.value < -QUERY_MIN_WEIGHT }
            .sortedBy { it.value }
            .mapNotNull { it.key.removePrefix("g:").toIntOrNull() }
            .take(limit)
            .toList()
            .filterNot { it in positives }
        // Only claim an era preference when the model actually leans on one, or a single decade
        // bucket would quietly narrow the whole catalog to a ten-year window.
        //
        // Magnitude cannot answer that question here: AdaGrad's first update to a feature moves
        // it by roughly the learning rate whatever the gradient was (the step is LR·g/sqrt(g²)),
        // so a decade seen exactly once already carries a respectable-looking weight. What
        // separates a real era preference from a scattered one is *concentration* - whether the
        // top decade holds most of the positive era mass - which is also scale-free.
        val positiveDecades = weights.entries
            .filter { it.key.startsWith("d:") && it.value > QUERY_MIN_WEIGHT }
        val topDecade = positiveDecades.maxByOrNull { it.value }
        val eraMass = positiveDecades.sumOf { it.value }
        val decade = topDecade?.key?.removePrefix("d:")?.toIntOrNull()
        val leansOnEra = topDecade != null &&
            eraMass > 0.0 &&
            topDecade.value / eraMass >= QUERY_ERA_SHARE
        return TasteQuery(
            genreIds = positives,
            excludedGenreIds = negatives,
            decade = decade.takeIf { leansOnEra },
            preferHighlyRated = (weights[TasteFeatures.QUALITY] ?: 0.0) > QUERY_MIN_WEIGHT,
        )
    }

    fun topFeatures(prefix: String, limit: Int): List<String> =
        weights.entries
            .asSequence()
            .filter { it.key.startsWith(prefix) && it.value > 0.0 }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key.removePrefix(prefix) }
            .toList()

    fun toJson(): JSONObject {
        val weightJson = JSONObject()
        weights.forEach { (key, value) -> weightJson.put(key, value) }
        val accumulatorJson = JSONObject()
        accumulators.forEach { (key, value) -> accumulatorJson.put(key, value) }
        return JSONObject()
            .put(FIELD_VERSION, MODEL_VERSION)
            .put(FIELD_WEIGHTS, weightJson)
            .put(FIELD_ACCUMULATORS, accumulatorJson)
            .put(FIELD_EVIDENCE, evidence)
            .put(FIELD_OBSERVATIONS, observations)
            .put(FIELD_COVERAGE, coverage)
            .put(FIELD_TRAINED_AT, trainedAtEpochMillis)
    }

    /** A learned profile rendered as catalog-query terms. */
    data class TasteQuery(
        val genreIds: List<Int>,
        val excludedGenreIds: List<Int>,
        val decade: Int?,
        val preferHighlyRated: Boolean,
    ) {
        val isUsable: Boolean
            get() = genreIds.isNotEmpty()
    }

    private data class ScoredTitle(
        val item: MediaItem,
        val score: Double,
        val features: FeatureVector,
    )

    companion object {
        /** Bump when the feature space changes so stale weights are retrained, not reused. */
        const val MODEL_VERSION = 1

        /** Observations needed before the learned model is allowed to reorder anything. */
        const val MIN_OBSERVATIONS = 5

        /** Decayed evidence at which [confidence] reaches one half. */
        const val CONFIDENCE_HALF_EVIDENCE = 12.0

        const val TASTE_INFLUENCE = 1.0

        private const val EPOCHS = 6
        private const val LEARNING_RATE = 0.35
        private const val L2 = 0.002
        private const val EPSILON = 1e-8
        private const val REWATCH_PENALTY = 0.35
        private const val EXPLORATION = 0.07
        private const val DIVERSITY_PENALTY = 0.22
        private const val DIVERSITY_WINDOW = 3
        private const val FAMILIARITY_HALF = 1.5
        private const val QUERY_GENRE_LIMIT = 3
        private const val QUERY_MIN_WEIGHT = 0.05
        /** Share of the positive era mass the top decade must hold to count as a preference. */
        private const val QUERY_ERA_SHARE = 0.5
        private const val SHUFFLE_SEED = 7L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L

        private const val FIELD_VERSION = "version"
        private const val FIELD_WEIGHTS = "weights"
        private const val FIELD_ACCUMULATORS = "accumulators"
        private const val FIELD_EVIDENCE = "evidence"
        private const val FIELD_OBSERVATIONS = "observations"
        private const val FIELD_COVERAGE = "coverage"
        private const val FIELD_TRAINED_AT = "trained_at"

        internal fun sigmoid(value: Double): Double = when {
            value >= 0.0 -> 1.0 / (1.0 + exp(-value))
            else -> {
                val z = exp(value)
                z / (1.0 + z)
            }
        }

        fun fromJson(json: JSONObject?): TasteModel {
            if (json == null) return TasteModel()
            if (json.optInt(FIELD_VERSION, 0) != MODEL_VERSION) return TasteModel()
            return TasteModel(
                weights = json.optJSONObject(FIELD_WEIGHTS).toDoubleMap(),
                accumulators = json.optJSONObject(FIELD_ACCUMULATORS).toDoubleMap(),
                evidence = json.optDouble(FIELD_EVIDENCE, 0.0),
                observations = json.optInt(FIELD_OBSERVATIONS, 0),
                coverage = json.optInt(FIELD_COVERAGE, 0),
                trainedAtEpochMillis = json.optLong(FIELD_TRAINED_AT, 0L),
            )
        }

        private fun JSONObject?.toDoubleMap(): Map<String, Double> {
            if (this == null) return emptyMap()
            val values = HashMap<String, Double>(length())
            keys().forEach { key ->
                val value = optDouble(key, Double.NaN)
                if (!value.isNaN()) values[key] = value
            }
            return values
        }
    }
}
