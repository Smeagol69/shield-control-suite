package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogFilterTest {
    private val movie = media(1, MediaType.MOVIE)
    private val series = media(2, MediaType.TV)

    @Test
    fun allReturnsTheOriginalRows() {
        val rows = listOf(MediaRow("Mixed", listOf(movie, series)))

        assertSame(rows, filterCatalogRows(rows, CatalogFilter.ALL))
    }

    @Test
    fun filtersMixedRowsAndDropsEmptyRows() {
        val rows = listOf(
            MediaRow("Mixed", listOf(movie, series), subtitle = "Personalized"),
            MediaRow("Series only", listOf(series)),
        )

        val movies = filterCatalogRows(rows, CatalogFilter.MOVIES)

        assertEquals(1, movies.size)
        assertEquals("Mixed", movies.single().title)
        assertEquals("Personalized", movies.single().subtitle)
        assertEquals(listOf(movie), movies.single().items)
    }

    @Test
    fun acceptsOnlyTheRequestedMediaType() {
        assertEquals(listOf(series), filterCatalogRows(
            listOf(MediaRow("Mixed", listOf(movie, series))),
            CatalogFilter.SERIES,
        ).single().items)
    }

    private fun media(id: Int, type: MediaType): MediaItem =
        MediaItem(
            id = id,
            type = type,
            title = "Title $id",
            year = "2026",
            posterUrl = "https://image.tmdb.org/t/p/w500/$id.jpg",
            backdropUrl = null,
            overview = "",
            rating = 8.0,
        )
}
