package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UniquePageCollectorTest {
    @Test
    fun collectsAcrossPagesDeduplicatesAndStopsAtTarget() {
        val requestedPages = mutableListOf<Int>()

        val result = collectUniquePages(
            targetSize = 5,
            maxPages = 4,
            keyOf = Int::toString,
        ) { page ->
            requestedPages += page
            PageResult(
                items = when (page) {
                    1 -> listOf(1, 2)
                    2 -> listOf(2, 3)
                    3 -> listOf(4, 5, 6)
                    else -> listOf(7)
                },
                totalPages = 10,
            )
        }

        assertEquals(listOf(1, 2, 3, 4, 5), result)
        assertEquals(listOf(1, 2, 3), requestedPages)
    }

    @Test
    fun respectsReportedTotalPagesAndMaximumPageCount() {
        val reportedPages = mutableListOf<Int>()
        collectUniquePages(20, 4, Int::toString) { page ->
            reportedPages += page
            PageResult(listOf(page), totalPages = 2)
        }
        assertEquals(listOf(1, 2), reportedPages)

        val cappedPages = mutableListOf<Int>()
        collectUniquePages(20, 3, Int::toString) { page ->
            cappedPages += page
            PageResult(listOf(page), totalPages = 100)
        }
        assertEquals(listOf(1, 2, 3), cappedPages)
    }

    @Test
    fun stopsAfterAnEmptyPageAndRejectsInvalidBounds() {
        val requestedPages = mutableListOf<Int>()
        val result = collectUniquePages(20, 4, Int::toString) { page ->
            requestedPages += page
            PageResult(emptyList(), totalPages = 4)
        }

        assertEquals(emptyList<Int>(), result)
        assertEquals(listOf(1), requestedPages)
        assertThrows(IllegalArgumentException::class.java) {
            collectUniquePages(0, 1, Int::toString) { PageResult(emptyList(), 1) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            collectUniquePages(1, 0, Int::toString) { PageResult(emptyList(), 1) }
        }
    }
}
