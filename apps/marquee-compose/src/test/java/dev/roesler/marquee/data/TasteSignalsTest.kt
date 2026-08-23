package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteSignalsTest {
    private val now = 1_700_000_000_000L

    private fun title(id: Int) = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        year = "2020",
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        rating = 7.0,
        genreIds = listOf(28),
    )

    private fun watched(
        id: Int,
        source: WatchSource = WatchSource.LOCAL_PLAYBACK,
        completed: Boolean = true,
        progress: Double? = 100.0,
        playCount: Int = 1,
    ) = WatchedTitle(
        item = title(id),
        source = source,
        firstWatchedAtEpochMillis = now,
        lastWatchedAtEpochMillis = now,
        playCount = playCount,
        completed = completed,
        progressPercent = progress,
    )

    @Test
    fun abandoningEarlyIsANegativeSignal() {
        val signals = buildTasteSignals(
            verdicts = emptyList(),
            watched = listOf(watched(1, completed = false, progress = 8.0)),
            now = now,
        )
        assertEquals(1, signals.size)
        assertEquals(SignalKind.ABANDONED, signals.first().kind)
        assertEquals(0.0, signals.first().label, 0.0)
    }

    @Test
    fun rewatchingOutweighsASingleFinish() {
        val signals = buildTasteSignals(
            verdicts = emptyList(),
            watched = listOf(watched(1, playCount = 3), watched(2, playCount = 1)),
            now = now,
        )
        val rewatch = signals.first { it.kind == SignalKind.REWATCHED }
        val finish = signals.first { it.kind == SignalKind.COMPLETED }
        assertTrue(rewatch.weight > finish.weight)
        assertEquals(1.0, rewatch.label, 0.0)
    }

    @Test
    fun importedHistoryCountsForLessThanALocalFinish() {
        val signals = buildTasteSignals(
            verdicts = emptyList(),
            watched = listOf(
                watched(1, source = WatchSource.TRAKT),
                watched(2, source = WatchSource.LOCAL_PLAYBACK),
            ),
            now = now,
        )
        val imported = signals.first { it.item.id == 1 }
        val local = signals.first { it.item.id == 2 }
        assertTrue(
            "imported (${imported.weight}) should be quieter than local (${local.weight})",
            imported.weight < local.weight,
        )
    }

    @Test
    fun anExplicitRatingSupersedesTheMatchingPlay() {
        val item = title(1)
        val signals = buildTasteSignals(
            verdicts = listOf(TitleVerdict(item, Verdict.DISLIKED, now)),
            watched = listOf(watched(1)),
            watchlist = listOf(item),
            now = now,
        )
        assertEquals(1, signals.size)
        assertEquals(SignalKind.RATED_DISLIKE, signals.first().kind)
    }

    @Test
    fun classBalancingLiftsTheOutnumberedSide() {
        // One dislike against fifty finishes: without balancing the negative would be noise.
        val signals = buildTasteSignals(
            verdicts = listOf(TitleVerdict(title(999), Verdict.DISLIKED, now)),
            watched = (1..50).map { watched(it) },
            now = now,
        )
        val effective = effectiveWeights(signals, now)
        val negativeIndex = signals.indexOfFirst { it.label < 0.5 }
        val rawNegative = signals[negativeIndex].weight
        assertTrue(
            "the lone negative should be amplified above its raw weight",
            effective[negativeIndex] > rawNegative,
        )
    }

    @Test
    fun staleSignalsDecayBelowFreshOnes() {
        val sixMonths = now - (TasteProfile.HALF_LIFE_DAYS * 24 * 60 * 60 * 1000).toLong()
        val signals = listOf(
            TasteSignal(title(1), 1.0, 1.0, now, SignalKind.RATED_LIKE),
            TasteSignal(title(2), 1.0, 1.0, sixMonths, SignalKind.RATED_LIKE),
        )
        val effective = effectiveWeights(signals, now)
        assertTrue(effective[0] > effective[1])
        // One half-life should cost roughly half the influence.
        assertEquals(0.5, effective[1] / effective[0], 0.05)
    }

    @Test
    fun coverageCountsDistinctTitles() {
        val signals = buildTasteSignals(
            verdicts = emptyList(),
            watched = listOf(watched(1), watched(2)),
            watchlist = listOf(title(2), title(3)),
            now = now,
        )
        assertEquals(3, signalCoverage(signals))
    }
}
