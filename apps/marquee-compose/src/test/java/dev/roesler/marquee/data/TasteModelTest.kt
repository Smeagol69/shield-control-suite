package dev.roesler.marquee.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteModelTest {
    private val now = 1_700_000_000_000L

    private fun title(
        id: Int,
        type: MediaType = MediaType.MOVIE,
        genres: List<Int> = listOf(ACTION),
        year: String = "2020",
        rating: Double = 7.0,
    ) = MediaItem(
        id = id,
        type = type,
        title = "Title $id",
        year = year,
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        rating = rating,
        genreIds = genres,
    )

    private fun signal(item: MediaItem, label: Double, kind: SignalKind) = TasteSignal(
        item = item,
        label = label,
        weight = 1.0,
        observedAtEpochMillis = now,
        kind = kind,
    )

    @Test
    fun untrainedModelLeavesOrderAlone() {
        val items = listOf(title(1), title(2, genres = listOf(HORROR)))
        val ranked = TasteModel().rank(items, dayIndex = 0L)
        assertEquals(items.map { it.key }, ranked.map { it.key })
    }

    @Test
    fun learnsGenrePreferenceFromLabelledSignals() {
        val signals = buildList {
            repeat(4) { index ->
                add(signal(title(index, genres = listOf(ACTION)), 1.0, SignalKind.RATED_LIKE))
                add(signal(title(100 + index, genres = listOf(HORROR)), 0.0, SignalKind.RATED_DISLIKE))
            }
        }
        val model = TasteModel().trainedOn(signals, now)

        assertTrue("model should have trained", model.trained)
        val likedGenre = model.probabilityOf(title(900, genres = listOf(ACTION)))
        val dislikedGenre = model.probabilityOf(title(901, genres = listOf(HORROR)))
        assertTrue(
            "action ($likedGenre) should outrank horror ($dislikedGenre)",
            likedGenre > dislikedGenre,
        )
    }

    @Test
    fun learnsGenreCombinationsBeyondTheSumOfTheirParts() {
        // Comedy alone: disliked. Sci-fi alone: disliked. The pairing: loved. A purely additive
        // model cannot represent this; the pair feature is what makes it learnable.
        val signals = buildList {
            repeat(5) { index ->
                add(signal(title(index, genres = listOf(COMEDY)), 0.0, SignalKind.RATED_DISLIKE))
                add(signal(title(50 + index, genres = listOf(SCIFI)), 0.0, SignalKind.RATED_DISLIKE))
                add(
                    signal(
                        title(100 + index, genres = listOf(COMEDY, SCIFI)),
                        1.0,
                        SignalKind.RATED_LIKE,
                    ),
                )
            }
        }
        val model = TasteModel().trainedOn(signals, now)

        val combined = model.probabilityOf(title(900, genres = listOf(COMEDY, SCIFI)))
        val comedyOnly = model.probabilityOf(title(901, genres = listOf(COMEDY)))
        assertTrue(
            "sci-fi comedy ($combined) should beat plain comedy ($comedyOnly)",
            combined > comedyOnly,
        )
    }

    @Test
    fun watchedTitlesRankBelowUnseenEquivalents() {
        val signals = List(6) { signal(title(it, genres = listOf(ACTION)), 1.0, SignalKind.COMPLETED) }
        val model = TasteModel().trainedOn(signals, now)
        val candidate = title(900, genres = listOf(ACTION))

        assertTrue(model.scoreOf(candidate, watched = true) < model.scoreOf(candidate, watched = false))
    }

    @Test
    fun noveltyFallsAsEvidenceAccumulates() {
        val signals = List(8) { signal(title(it, genres = listOf(ACTION)), 1.0, SignalKind.RATED_LIKE) }
        val model = TasteModel().trainedOn(signals, now)

        val familiar = model.noveltyOf(title(900, genres = listOf(ACTION)))
        val unfamiliar = model.noveltyOf(title(901, genres = listOf(DOCUMENTARY)))
        assertTrue("unfamiliar ($unfamiliar) should exceed familiar ($familiar)", unfamiliar > familiar)
    }

    @Test
    fun confidenceGrowsWithEvidence() {
        val thin = TasteModel().trainedOn(
            List(5) { signal(title(it), 1.0, SignalKind.RATED_LIKE) },
            now,
        )
        val thick = TasteModel().trainedOn(
            List(60) { signal(title(it), 1.0, SignalKind.RATED_LIKE) },
            now,
        )
        assertTrue(thick.confidence > thin.confidence)
        assertTrue(thick.confidence < 1.0)
    }

    @Test
    fun rankingKeepsEveryTitleItWasGiven() {
        val signals = List(8) { signal(title(it, genres = listOf(ACTION)), 1.0, SignalKind.RATED_LIKE) }
        val model = TasteModel().trainedOn(signals, now)
        val items = (200..219).map {
            title(it, genres = listOf(if (it % 2 == 0) ACTION else HORROR))
        }
        val ranked = model.rank(items, dayIndex = 1L)

        assertEquals(items.size, ranked.size)
        assertEquals(items.map { it.key }.toSet(), ranked.map { it.key }.toSet())
    }

    @Test
    fun diversityBreaksUpRunsOfIdenticalGenres() {
        val signals = List(8) { signal(title(it, genres = listOf(ACTION)), 1.0, SignalKind.RATED_LIKE) }
        val model = TasteModel().trainedOn(signals, now)
        // Ten identical-genre titles followed by ten of another; a pure score sort would emit
        // all of the first group before any of the second.
        val items = (0..9).map { title(300 + it, genres = listOf(ACTION)) } +
            (0..9).map { title(400 + it, genres = listOf(DOCUMENTARY)) }
        val ranked = model.rank(items, preserveSourceOrder = false, dayIndex = 2L)

        val firstFive = ranked.take(5).map { it.genreIds.first() }
        assertTrue(
            "expected a mix in the opening slots but got $firstFive",
            firstFive.toSet().size > 1,
        )
    }

    @Test
    fun survivesAJsonRoundTrip() {
        val model = TasteModel().trainedOn(
            List(10) { signal(title(it, genres = listOf(ACTION)), 1.0, SignalKind.RATED_LIKE) },
            now,
        )
        val restored = TasteModel.fromJson(JSONObject(model.toJson().toString()))

        assertEquals(model.observations, restored.observations)
        assertEquals(model.coverage, restored.coverage)
        assertEquals(model.evidence, restored.evidence, 1e-9)
        assertEquals(model.weights.size, restored.weights.size)
        assertEquals(
            model.probabilityOf(title(900, genres = listOf(ACTION))),
            restored.probabilityOf(title(900, genres = listOf(ACTION))),
            1e-9,
        )
    }

    @Test
    fun rejectsWeightsFromAnOlderFeatureSpace() {
        val stale = JSONObject().put("version", TasteModel.MODEL_VERSION - 1)
        assertFalse(TasteModel.fromJson(stale).trained)
        assertFalse(TasteModel.fromJson(null).trained)
    }

    private companion object {
        const val ACTION = 28
        const val COMEDY = 35
        const val HORROR = 27
        const val SCIFI = 878
        const val DOCUMENTARY = 99
    }
}
