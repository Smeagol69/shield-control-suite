package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchOrderCatalogTest {
    @Test
    fun `monsterverse follows narrative chronology from skull island`() {
        assertNext(293167, 124905, "Godzilla", 2)
        assertNext(124905, 373571, "Godzilla: King of the Monsters", 3)
        assertNext(373571, 399566, "Godzilla vs. Kong", 4)
        assertNext(399566, 823464, "Godzilla x Kong: The New Empire", 5)
    }

    @Test
    fun `already watched entries are skipped without looping backward`() {
        val next = WatchOrderCatalog.nextUnwatchedAfter(
            movie(293167, "Kong: Skull Island", "2017"),
            watchedKeys = setOf("movie:124905", "movie:373571"),
        )

        assertEquals(399566, next?.entry?.tmdbId)
        assertEquals(4, next?.position)
    }

    @Test
    fun `last monsterverse movie has no successor`() {
        assertNull(
            WatchOrderCatalog.nextUnwatchedAfter(
                movie(823464, "Godzilla x Kong: The New Empire", "2024"),
            ),
        )
    }

    @Test
    fun `title fallback tolerates punctuation and missing tmdb identity`() {
        val next = WatchOrderCatalog.nextUnwatchedAfter(
            movie(0, "Kong Skull Island", "2017"),
        )

        assertEquals(124905, next?.entry?.tmdbId)
    }

    @Test
    fun `television and unrelated movies do not match a movie sequence`() {
        assertNull(
            WatchOrderCatalog.nextUnwatchedAfter(
                movie(293167, "Kong: Skull Island", "2017").copy(type = MediaType.TV),
            ),
        )
        assertNull(WatchOrderCatalog.nextUnwatchedAfter(movie(550, "Fight Club", "1999")))
    }

    private fun assertNext(
        currentId: Int,
        expectedId: Int,
        expectedTitle: String,
        expectedPosition: Int,
    ) {
        val next = WatchOrderCatalog.nextUnwatchedAfter(movie(currentId, "ignored", ""))

        assertEquals("MonsterVerse", next?.sequenceName)
        assertEquals(expectedId, next?.entry?.tmdbId)
        assertEquals(expectedTitle, next?.entry?.title)
        assertEquals(expectedPosition, next?.position)
        assertEquals(5, next?.total)
    }

    private fun movie(id: Int, title: String, year: String): MediaItem = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = title,
        year = year,
        posterUrl = null,
        backdropUrl = null,
        overview = "",
        rating = 0.0,
    )
}
