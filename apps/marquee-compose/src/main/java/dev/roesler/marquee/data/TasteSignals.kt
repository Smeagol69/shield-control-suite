package dev.roesler.marquee.data

import kotlin.math.pow
import kotlin.math.sqrt

/** One labelled observation the model can learn from. */
data class TasteSignal(
    val item: MediaItem,
    /** 1.0 for "this viewer wants more of this", 0.0 for "less of this". */
    val label: Double,
    /** How much this observation should move the model, before class balancing. */
    val weight: Double,
    val observedAtEpochMillis: Long,
    val kind: SignalKind,
)

enum class SignalKind {
    RATED_LIKE,
    RATED_DISLIKE,
    REWATCHED,
    COMPLETED,
    ABANDONED,
    PARTIAL,
    WATCHLISTED,
}

/**
 * Turns everything the viewer has done into training data.
 *
 * Explicit ratings are the cleanest evidence and carry full weight. Behaviour is noisier but
 * there is far more of it — finishing a film says something real, abandoning one twenty minutes
 * in says something stronger, and choosing to watch a thing twice says the most of all. Weights
 * below encode that ordering; ages decay on the same half-life the rest of the profile uses so
 * last year's binge slowly stops speaking for this year's taste.
 *
 * Imported history is deliberately quieter than a local finish: Trakt tells us a title was
 * watched, not that it was enjoyed, and treating 600 imported plays as 600 enthusiastic votes
 * would drown out the handful of opinions the viewer actually expressed.
 */
fun buildTasteSignals(
    verdicts: Collection<TitleVerdict>,
    watched: Collection<WatchedTitle>,
    watchlist: Collection<MediaItem> = emptyList(),
    now: Long = System.currentTimeMillis(),
): List<TasteSignal> {
    val signals = ArrayList<TasteSignal>(verdicts.size + watched.size + watchlist.size)
    val rated = HashSet<String>(verdicts.size)

    verdicts.forEach { verdict ->
        rated += verdict.item.key
        val liked = verdict.verdict == Verdict.LIKED
        signals += TasteSignal(
            item = verdict.item,
            label = if (liked) 1.0 else 0.0,
            weight = RATING_WEIGHT,
            observedAtEpochMillis = verdict.ratedAtEpochMillis,
            kind = if (liked) SignalKind.RATED_LIKE else SignalKind.RATED_DISLIKE,
        )
    }

    watched.forEach { entry ->
        // An explicit rating already says everything this title has to say; counting the play
        // as well would let one title vote twice.
        if (entry.key in rated) return@forEach
        val imported = entry.source == WatchSource.TRAKT
        val progress = entry.progressPercent
        val signal = when {
            entry.playCount >= REWATCH_THRESHOLD ->
                SignalKind.REWATCHED to REWATCH_WEIGHT
            entry.completed ->
                SignalKind.COMPLETED to if (imported) IMPORTED_WEIGHT else COMPLETED_WEIGHT
            progress != null && progress < ABANDON_PERCENT ->
                SignalKind.ABANDONED to ABANDON_WEIGHT
            progress != null ->
                SignalKind.PARTIAL to PARTIAL_WEIGHT
            else -> return@forEach
        }
        signals += TasteSignal(
            item = entry.item,
            label = if (signal.first == SignalKind.ABANDONED) 0.0 else 1.0,
            weight = signal.second,
            observedAtEpochMillis = entry.lastWatchedAtEpochMillis,
            kind = signal.first,
        )
    }

    watchlist.forEach { item ->
        if (item.key in rated) return@forEach
        signals += TasteSignal(
            item = item,
            label = 1.0,
            weight = WATCHLIST_WEIGHT,
            observedAtEpochMillis = now,
            kind = SignalKind.WATCHLISTED,
        )
    }
    return signals
}

/**
 * Recency-decayed, class-balanced weight for a signal.
 *
 * Behavioural data skews overwhelmingly positive — people mostly watch things they expect to
 * like — and an unbalanced fit would simply learn "everything is fine". Scaling each class by
 * `(total / 2·classTotal)^0.5` pulls the two sides toward parity without the full correction,
 * which would let a dozen dislikes swing as hard as six hundred plays.
 */
fun effectiveWeights(
    signals: List<TasteSignal>,
    now: Long = System.currentTimeMillis(),
): List<Double> {
    if (signals.isEmpty()) return emptyList()
    val decayed = signals.map { signal ->
        signal.weight * TasteProfile.decayWeight(signal.observedAtEpochMillis, now)
    }
    var positive = 0.0
    var negative = 0.0
    signals.forEachIndexed { index, signal ->
        if (signal.label >= 0.5) positive += decayed[index] else negative += decayed[index]
    }
    val total = positive + negative
    if (total <= 0.0 || positive <= 0.0 || negative <= 0.0) return decayed
    val positiveScale = (total / (2.0 * positive)).pow(BALANCE_EXPONENT)
    val negativeScale = (total / (2.0 * negative)).pow(BALANCE_EXPONENT)
    return signals.mapIndexed { index, signal ->
        decayed[index] * if (signal.label >= 0.5) positiveScale else negativeScale
    }
}

/** Total decayed evidence, used to decide how far the model may be trusted. */
fun evidenceOf(signals: List<TasteSignal>, now: Long = System.currentTimeMillis()): Double =
    signals.sumOf { it.weight * TasteProfile.decayWeight(it.observedAtEpochMillis, now) }

/** Distinct-title count backing the model, for display. */
fun signalCoverage(signals: List<TasteSignal>): Int =
    signals.mapTo(HashSet(signals.size)) { it.item.key }.size

internal fun rootScale(value: Double): Double = sqrt(value.coerceAtLeast(0.0))

private const val RATING_WEIGHT = 1.0
private const val REWATCH_WEIGHT = 0.85
private const val COMPLETED_WEIGHT = 0.55
private const val IMPORTED_WEIGHT = 0.35
private const val ABANDON_WEIGHT = 0.45
private const val PARTIAL_WEIGHT = 0.20
private const val WATCHLIST_WEIGHT = 0.25
private const val REWATCH_THRESHOLD = 2
private const val ABANDON_PERCENT = 25.0
private const val BALANCE_EXPONENT = 0.5
