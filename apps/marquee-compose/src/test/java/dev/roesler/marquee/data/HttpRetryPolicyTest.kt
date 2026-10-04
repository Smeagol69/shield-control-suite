package dev.roesler.marquee.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRetryPolicyTest {
    @Test
    fun `retries only safe transient get failures`() {
        assertTrue(HttpRetryPolicy.shouldRetry("GET", 429, 0))
        assertTrue(HttpRetryPolicy.shouldRetry("GET", 503, 1))
        assertFalse(HttpRetryPolicy.shouldRetry("GET", 404, 0))
        assertFalse(HttpRetryPolicy.shouldRetry("POST", 503, 0))
        assertFalse(HttpRetryPolicy.shouldRetry("GET", 503, 2))
    }

    @Test
    fun `uses bounded server delay or short exponential fallback`() {
        assertEquals(350L, HttpRetryPolicy.delayMillis(0, null))
        assertEquals(700L, HttpRetryPolicy.delayMillis(1, null))
        assertEquals(2_000L, HttpRetryPolicy.delayMillis(0, 2))
        assertEquals(4_000L, HttpRetryPolicy.delayMillis(0, 30))
    }
}
