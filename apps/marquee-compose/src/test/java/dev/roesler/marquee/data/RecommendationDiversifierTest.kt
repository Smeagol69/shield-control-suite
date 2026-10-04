package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RecommendationDiversifierTest {
    @Test
    fun `keeps the best title first but introduces variety next`() {
        val ranked = listOf(
            title(1, listOf(28, 878)),
            title(2, listOf(28, 878)),
            title(3, listOf(28, 878)),
            title(4, listOf(18)),
        )

        val diversified = diversifyRecommendations(ranked, limit = 4)

        assertEquals(1, diversified.first().id)
        assertEquals(4, diversified[1].id)
    }

    @Test
    fun `zero strength preserves source order`() {
        val ranked = listOf(title(1, listOf(1)), title(2, listOf(1)), title(3, listOf(2)))

        assertEquals(
            listOf(1, 2, 3),
            diversifyRecommendations(ranked, 3, diversityStrength = 0.0).map(MediaItem::id),
        )
    }

    @Test
    fun `deduplicates and respects the requested limit`() {
        val first = title(1, listOf(1))
        val result = diversifyRecommendations(listOf(first, first, title(2, listOf(2))), 1)

        assertEquals(listOf(1), result.map(MediaItem::id))
        assertEquals(emptyList<MediaItem>(), diversifyRecommendations(listOf(first), 0))
    }

    @Test
    fun `rejects invalid bounds`() {
        assertThrows(IllegalArgumentException::class.java) {
            diversifyRecommendations(emptyList(), -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            diversifyRecommendations(emptyList(), 1, diversityStrength = 1.1)
        }
    }

    private fun title(id: Int, genres: List<Int>) = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        year = "2020",
        posterUrl = "https://image.tmdb.org/$id.jpg",
        backdropUrl = null,
        overview = "",
        rating = 7.0,
        genreIds = genres,
    )
}
