package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResolverLinksTest {
    @Test
    fun movieUsesExactCinemetaIdAndAutoplayHint() {
        val link = ResolverLinks.stremio(item(MediaType.MOVIE, "tt0133093"))

        assertEquals(
            "stremio:///detail/movie/tt0133093/tt0133093?autoPlay=true",
            link,
        )
    }

    @Test
    fun seriesOpensExactTitleWithoutGuessingAnEpisode() {
        val link = ResolverLinks.stremio(item(MediaType.TV, "tt0944947"))

        assertEquals("stremio:///detail/series/tt0944947", link)
        assertFalse(link.contains("autoPlay"))
    }

    @Test
    fun missingOrInvalidImdbIdFallsBackToEncodedSearch() {
        val link = ResolverLinks.stremio(
            item(MediaType.MOVIE, "invalid").copy(title = "Dune: Part Two", year = "2024"),
        )

        assertEquals(
            "stremio:///search?search=Dune%3A%20Part%20Two%202024",
            link,
        )
    }

    private fun item(type: MediaType, imdbId: String?) = MediaItem(
        id = 1,
        type = type,
        title = "Title",
        year = "2024",
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        rating = 0.0,
        imdbId = imdbId,
    )
}
