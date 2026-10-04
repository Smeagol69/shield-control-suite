package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryStoreTest {
    @Test
    fun `new searches move to the front without case duplicates`() {
        assertEquals(
            listOf("Alien", "Godzilla"),
            updateSearchHistory(listOf("Godzilla", "alien"), "  Alien  "),
        )
    }

    @Test
    fun `whitespace is normalized and history is bounded`() {
        assertEquals(
            listOf("Godzilla King", "A", "B"),
            updateSearchHistory(listOf("A", "B", "C"), " Godzilla   King ", limit = 3),
        )
    }

    @Test
    fun `one character input is not remembered`() {
        assertEquals(listOf("Dune"), updateSearchHistory(listOf("Dune"), "d"))
    }

    @Test
    fun `very long input is safely bounded`() {
        val result = updateSearchHistory(emptyList(), "x".repeat(500))

        assertEquals(SearchHistoryStore.MAX_QUERY_LENGTH, result.single().length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `history limit must be positive`() {
        updateSearchHistory(emptyList(), "Dune", limit = 0)
    }
}
