package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchRankingTest {
    @Test
    fun `exact title moves ahead without disturbing equal matches`() {
        val results = listOf(
            title(1, "The Dune Story"),
            title(2, "Dune"),
            title(3, "Dune"),
        )

        assertEquals(
            listOf(2, 3, 1),
            rankTitleSearch("Dune", results).map(MediaItem::id),
        )
    }

    @Test
    fun `trailing year disambiguates remakes`() {
        val results = listOf(
            title(1, "Dune", "1984"),
            title(2, "Dune", "2021"),
        )

        assertEquals(2, rankTitleSearch("Dune (2021)", results).first().id)
        assertEquals(1, rankTitleSearch("Dune 1984", results).first().id)
    }

    @Test
    fun `punctuation and case do not prevent an exact match`() {
        val results = listOf(
            title(1, "Spider-Man: Homecoming"),
            title(2, "Spider-Man"),
        )

        assertEquals(2, rankTitleSearch("spider man", results).first().id)
    }

    @Test
    fun `duplicate identities are removed`() {
        val duplicate = title(1, "Alien")

        assertEquals(1, rankTitleSearch("Alien", listOf(duplicate, duplicate)).size)
    }

    private fun title(id: Int, name: String, year: String = "2020") = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = name,
        year = year,
        posterUrl = "https://image.tmdb.org/$id.jpg",
        backdropUrl = null,
        overview = "",
        rating = 7.0,
    )
}
