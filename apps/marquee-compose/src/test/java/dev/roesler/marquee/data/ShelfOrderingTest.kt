package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ranking inside a shelf cannot help a shelf the viewer never scrolls to, so the browse band is
 * ordered too. Rows whose position carries meaning must not move regardless.
 */
class ShelfOrderingTest {
    private val now = 1_700_000_000_000L

    private fun title(id: Int, genres: List<Int>) = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        year = "2020",
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        rating = 7.0,
        genreIds = genres,
    )

    /** A model that has learned to like horror and dislike romance. */
    private fun horrorFan(): TasteModel {
        val signals = buildList {
            repeat(6) { index ->
                add(
                    TasteSignal(
                        title(index, listOf(HORROR)), 1.0, 1.0, now, SignalKind.RATED_LIKE,
                    ),
                )
                add(
                    TasteSignal(
                        title(500 + index, listOf(ROMANCE)), 0.0, 1.0, now, SignalKind.RATED_DISLIKE,
                    ),
                )
            }
        }
        return TasteModel().trainedOn(signals, now)
    }

    private fun row(title: String, genre: Int, reorderable: Boolean) = MediaRow(
        title = title,
        items = (1..6).map { title(9000 + genre * 100 + it, listOf(genre)) },
        reorderable = reorderable,
    )

    @Test
    fun theBestMatchingShelfRisesWithinTheBrowseBand() {
        val rows = listOf(
            row("Romance", ROMANCE, reorderable = true),
            row("Horror", HORROR, reorderable = true),
        )
        val ordered = horrorFan().orderShelves(rows)
        assertEquals("Horror", ordered.first().title)
    }

    @Test
    fun pinnedShelvesKeepTheirExactPositions() {
        val rows = listOf(
            row("My watchlist", ROMANCE, reorderable = false),
            row("Romance", ROMANCE, reorderable = true),
            row("Continue watching", ROMANCE, reorderable = false),
            row("Horror", HORROR, reorderable = true),
            row("Everything you've watched", ROMANCE, reorderable = false),
        )
        val ordered = horrorFan().orderShelves(rows)

        // The three pinned rows are exactly where they started.
        assertEquals("My watchlist", ordered[0].title)
        assertEquals("Continue watching", ordered[2].title)
        assertEquals("Everything you've watched", ordered[4].title)
        // The two movable ones swapped into the movable slots only.
        assertEquals("Horror", ordered[1].title)
        assertEquals("Romance", ordered[3].title)
    }

    @Test
    fun anUntrainedModelChangesNothing() {
        val rows = listOf(
            row("Romance", ROMANCE, reorderable = true),
            row("Horror", HORROR, reorderable = true),
        )
        assertEquals(rows.map { it.title }, TasteModel().orderShelves(rows).map { it.title })
    }

    @Test
    fun everyShelfSurvivesTheReorder() {
        val rows = listOf(
            row("A", HORROR, reorderable = true),
            row("B", ROMANCE, reorderable = false),
            row("C", ROMANCE, reorderable = true),
        )
        val ordered = horrorFan().orderShelves(rows)
        assertEquals(rows.size, ordered.size)
        assertEquals(rows.map { it.title }.toSet(), ordered.map { it.title }.toSet())
    }

    private companion object {
        const val HORROR = 27
        const val ROMANCE = 10749
    }
}
