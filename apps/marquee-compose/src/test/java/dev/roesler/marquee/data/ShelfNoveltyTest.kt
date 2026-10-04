package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShelfNoveltyTest {
    @Test
    fun `semantic rows stay intact and reserve their titles`() {
        val saved = row("Watchlist", listOf(1, 2), reorderable = false)
        val trending = row("Trending", listOf(1, 2, 3, 4), reorderable = true)

        val result = improveShelfNovelty(listOf(saved, trending), minimumItems = 2)

        assertEquals(listOf(1, 2), result[0].items.map(MediaItem::id))
        assertEquals(listOf(3, 4), result[1].items.map(MediaItem::id))
    }

    @Test
    fun `generic shelves prefer unseen titles`() {
        val first = row("First", listOf(1, 2, 3), reorderable = true)
        val second = row("Second", listOf(1, 2, 4, 5), reorderable = true)

        val result = improveShelfNovelty(listOf(first, second), minimumItems = 2)

        assertEquals(listOf(4, 5), result[1].items.map(MediaItem::id))
    }

    @Test
    fun `repeats only fill a shelf to its minimum`() {
        val first = row("First", listOf(1, 2, 3), reorderable = true)
        val second = row("Second", listOf(1, 4, 2), reorderable = true)

        val result = improveShelfNovelty(listOf(first, second), minimumItems = 3)

        assertEquals(listOf(4, 1, 2), result[1].items.map(MediaItem::id))
    }

    @Test
    fun `minimum must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            improveShelfNovelty(emptyList(), minimumItems = 0)
        }
    }

    private fun row(name: String, ids: List<Int>, reorderable: Boolean) = MediaRow(
        title = name,
        items = ids.map(::title),
        reorderable = reorderable,
    )

    private fun title(id: Int) = MediaItem(
        id = id,
        type = MediaType.MOVIE,
        title = "Title $id",
        year = "2020",
        posterUrl = "https://image.tmdb.org/$id.jpg",
        backdropUrl = null,
        overview = "",
        rating = 7.0,
    )
}
