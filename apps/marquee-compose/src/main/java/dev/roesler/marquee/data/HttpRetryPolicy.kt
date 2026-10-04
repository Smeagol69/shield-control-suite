package dev.roesler.marquee.data

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

/** Bounded retry policy shared by the small HTTPS clients. Mutating requests are never retried. */
internal object HttpRetryPolicy {
    private const val MAX_RETRIES = 2
    private const val BASE_DELAY_MILLIS = 350L
    private const val MAX_RETRY_AFTER_SECONDS = 4

    fun shouldRetry(method: String, status: Int, retriesCompleted: Int): Boolean =
        canRetry(method, retriesCompleted) &&
            (status == 429 || status in 500..599)

    fun shouldRetry(method: String, failure: IOException, retriesCompleted: Int): Boolean =
        canRetry(method, retriesCompleted) &&
            (failure is SocketTimeoutException ||
                failure is SocketException ||
                failure is EOFException)

    fun delayMillis(retriesCompleted: Int, retryAfterSeconds: Int?): Long =
        retryAfterSeconds
            ?.coerceIn(1, MAX_RETRY_AFTER_SECONDS)
            ?.times(1_000L)
            ?: (BASE_DELAY_MILLIS shl retriesCompleted.coerceIn(0, MAX_RETRIES - 1))

    private fun canRetry(method: String, retriesCompleted: Int): Boolean =
        method.equals("GET", ignoreCase = true) && retriesCompleted < MAX_RETRIES
}
