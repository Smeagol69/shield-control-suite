package dev.roesler.marquee.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

internal data class JsonHttpResponse(
    val status: Int,
    val body: String,
    val retryAfterSeconds: Int?,
)

internal object JsonHttp {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 14_000
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    fun request(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): JsonHttpResponse {
        val uri = runCatching { URI.create(url) }
            .getOrElse { throw IOException("Invalid service URL.") }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw IOException("Only HTTPS service URLs are allowed.")
        }

        val connection = uri.toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Marquee/2.6.0 AndroidTV")
            headers.forEach(connection::setRequestProperty)

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use {
                    it.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }

            val status = connection.responseCode
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                throw IOException("Service response exceeded the size limit.")
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            JsonHttpResponse(
                status = status,
                body = stream?.use { it.readUtf8Bounded(MAX_RESPONSE_BYTES) }.orEmpty(),
                retryAfterSeconds = connection.getHeaderField("Retry-After")?.toIntOrNull(),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readUtf8Bounded(maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(available().coerceAtLeast(0), maxBytes))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IOException("Service response exceeded the size limit.")
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }
}
