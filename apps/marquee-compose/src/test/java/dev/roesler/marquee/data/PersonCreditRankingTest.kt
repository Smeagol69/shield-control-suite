package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersonCreditRankingTest {
    @Test
    fun `recognizable well supported work beats a perfect one vote title`() {
        val obscure = credit(title(1, rating = 10.0), popularity = 0.1, votes = 1)
        val signature = credit(title(2, rating = 8.0), popularity = 80.0, votes = 2_000)

        assertEquals(2, rankPersonCredits(listOf(obscure, signature), 10).first().id)
    }

    @Test
    fun `cast and crew duplicates merge their role labels`() {
        val item = title(1)
        val result = rankPersonCredits(
            listOf(
                credit(item, role = "as Alex"),
                credit(item, role = "Director", priority = 1.2),
            ),
            10,
        )

        assertEquals(1, result.size)
        assertEquals("as Alex · Director", result.single().contextLabel)
    }

    @Test
    fun `posterless credits are dropped and result count is bounded`() {
        val posterless = credit(title(1).copy(posterUrl = null))
        val visible = credit(title(2))

        assertEquals(listOf(2), rankPersonCredits(listOf(posterless, visible), 1).map(MediaItem::id))
    }

    @Test
    fun `limit must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            rankPersonCredits(emptyList(), 0)
        }
    }

    private fun credit(
        item: MediaItem,
        popularity: Double = 10.0,
        votes: Int = 500,
        role: String? = null,
        priority: Double = 0.45,
    ) = PersonCreditCandidate(item, popularity, votes, role, priority)

    private fun title(id: Int, rating: Double = 7.0) = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        year = "2020",
        posterUrl = "https://image.tmdb.org/$id.jpg",
        backdropUrl = null,
        overview = "",
        rating = rating,
    )
}
