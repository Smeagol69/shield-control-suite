package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Not interested" is the only negative the model can get without the viewer watching anything. */
class SuppressionSignalTest {
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
        genreIds = listOf(27),
    )

    @Test
    fun aWaveOffBecomesANegativeSignal() {
        val signals = buildTasteSignals(
            verdicts = emptyList(),
            watched = emptyList(),
            suppressed = listOf(title(1)),
            now = now,
        )
        assertEquals(1, signals.size)
        assertEquals(SignalKind.NOT_INTERESTED, signals.first().kind)
        assertEquals(0.0, signals.first().label, 0.0)
    }

    @Test
    fun itOutweighsAnAbandonButNotAnInformedDislike() {
        val waveOff = buildTasteSignals(
            verdicts = emptyList(),
            watched = emptyList(),
            suppressed = listOf(title(1)),
            now = now,
        ).first()
        val abandon = buildTasteSignals(
            verdicts = emptyList(),
            watched = listOf(
                WatchedTitle(
                    item = title(2),
                    source = WatchSource.LOCAL_PLAYBACK,
                    firstWatchedAtEpochMillis = now,
                    lastWatchedAtEpochMillis = now,
                    playCount = 1,
                    completed = false,
                    progressPercent = 5.0,
                ),
            ),
            now = now,
        ).first()
        val dislike = buildTasteSignals(
            verdicts = listOf(TitleVerdict(title(3), Verdict.DISLIKED, now)),
            watched = emptyList(),
            now = now,
        ).first()

        assertTrue("wave-off should beat an abandon", waveOff.weight > abandon.weight)
        assertTrue("a watched dislike is better evidence", dislike.weight > waveOff.weight)
    }

    @Test
    fun anExplicitVerdictStillWins() {
        val item = title(1)
        val signals = buildTasteSignals(
            verdicts = listOf(TitleVerdict(item, Verdict.LIKED, now)),
            watched = emptyList(),
            suppressed = listOf(item),
            now = now,
        )
        assertEquals(1, signals.size)
        assertEquals(SignalKind.RATED_LIKE, signals.first().kind)
    }

    @Test
    fun waveOffsTeachTheModelToAvoidThatShape() {
        val liked = (1..6).map {
            TasteSignal(
                MediaItem(
                    id = it, type = MediaType.MOVIE, title = "L$it", year = "2020",
                    posterUrl = null, backdropUrl = null, overview = "", rating = 7.0,
                    genreIds = listOf(35),
                ),
                1.0, 1.0, now, SignalKind.RATED_LIKE,
            )
        }
        val waved = (1..6).map {
            TasteSignal(title(100 + it), 0.0, 0.6, now, SignalKind.NOT_INTERESTED)
        }
        val model = TasteModel().trainedOn(liked + waved, now)

        val comedy = MediaItem(
            id = 900, type = MediaType.MOVIE, title = "C", year = "2020",
            posterUrl = null, backdropUrl = null, overview = "", rating = 7.0,
            genreIds = listOf(35),
        )
        assertTrue(
            "the waved-away shape should score below the liked one",
            model.probabilityOf(comedy) > model.probabilityOf(title(901)),
        )
    }
}
