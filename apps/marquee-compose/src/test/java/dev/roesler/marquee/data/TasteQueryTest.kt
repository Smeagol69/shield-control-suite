package dev.roesler.marquee.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model projected into catalog-query terms.
 *
 * Re-ranking can only reorder what an endpoint returned, so a profile that sits outside the
 * popular shelves can never be served by ranking alone. These cover the projection that lets
 * discovery go looking instead.
 */
class TasteQueryTest {
    private val now = 1_700_000_000_000L

    private fun title(id: Int, genres: List<Int>, year: String = "2020") = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        year = year,
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        rating = 7.0,
        genreIds = genres,
    )

    private fun signal(item: MediaItem, label: Double) = TasteSignal(
        item = item,
        label = label,
        weight = 1.0,
        observedAtEpochMillis = now,
        kind = if (label >= 0.5) SignalKind.RATED_LIKE else SignalKind.RATED_DISLIKE,
    )

    private fun modelLiking(liked: Int, disliked: Int, likedYear: String = "2020"): TasteModel {
        val signals = buildList {
            repeat(6) { index ->
                add(signal(title(index, listOf(liked), likedYear), 1.0))
                add(signal(title(500 + index, listOf(disliked)), 0.0))
            }
        }
        return TasteModel().trainedOn(signals, now)
    }

    @Test
    fun anUntrainedModelYieldsNoQuery() {
        assertFalse(TasteModel().tasteQuery().isUsable)
    }

    @Test
    fun likedGenresBecomeQueryTerms() {
        val query = modelLiking(liked = HORROR, disliked = ROMANCE).tasteQuery()
        assertTrue("expected horror in ${query.genreIds}", HORROR in query.genreIds)
    }

    @Test
    fun dislikedGenresAreExcludedAtTheSource() {
        val query = modelLiking(liked = HORROR, disliked = ROMANCE).tasteQuery()
        assertTrue("expected romance excluded, got ${query.excludedGenreIds}", ROMANCE in query.excludedGenreIds)
        assertFalse("a genre cannot be both wanted and excluded", ROMANCE in query.genreIds)
    }

    @Test
    fun aGenreIsNeverBothRequestedAndExcluded() {
        val query = modelLiking(liked = HORROR, disliked = ROMANCE).tasteQuery()
        assertTrue(query.genreIds.intersect(query.excludedGenreIds.toSet()).isEmpty())
    }

    @Test
    fun eraIsOnlyClaimedWhenTheModelLeansOnIt() {
        // Every liked title shares one decade, so the era weight should carry real evidence;
        // with a spread of decades it must not narrow the catalog to an arbitrary window.
        val spread = buildList {
            listOf("1978", "1994", "2003", "2015", "2021", "2024").forEachIndexed { index, year ->
                add(signal(title(index, listOf(HORROR), year), 1.0))
                add(signal(title(500 + index, listOf(ROMANCE)), 0.0))
            }
        }
        val scattered = TasteModel().trainedOn(spread, now).tasteQuery()
        assertTrue(
            "a scattered era profile should not pin a decade, got ${scattered.decade}",
            scattered.decade == null,
        )
    }

    private companion object {
        const val HORROR = 27
        const val ROMANCE = 10749
    }
}
