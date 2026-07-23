package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpiringLruCacheTest {
    @Test
    fun returnsValuesUntilTheyExpire() {
        var now = 1_000L
        val cache = ExpiringLruCache<String, String>(2, 500L) { now }

        cache.put("provider", "catalog")
        assertEquals("catalog", cache.get("provider"))

        now += 499L
        assertEquals("catalog", cache.get("provider"))

        now += 1L
        assertNull(cache.get("provider"))
    }

    @Test
    fun evictsTheLeastRecentlyUsedEntry() {
        val cache = ExpiringLruCache<String, Int>(2, 60_000L) { 1_000L }
        cache.put("prime", 1)
        cache.put("disney", 2)
        assertEquals(1, cache.get("prime"))

        cache.put("hulu", 3)

        assertNull(cache.get("disney"))
        assertEquals(1, cache.get("prime"))
        assertEquals(3, cache.get("hulu"))
    }

    @Test
    fun clearDropsEveryEntry() {
        val cache = ExpiringLruCache<String, Int>(2, 60_000L) { 1_000L }
        cache.put("one", 1)
        cache.put("two", 2)

        cache.clear()

        assertNull(cache.get("one"))
        assertNull(cache.get("two"))
    }
}
