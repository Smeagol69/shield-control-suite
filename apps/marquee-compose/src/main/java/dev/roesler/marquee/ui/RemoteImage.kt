package dev.roesler.marquee.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import dev.roesler.marquee.data.UrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

@Composable
fun RemoteImage(
    url: String?,
    description: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap = produceState<Bitmap?>(initialValue = ImageMemoryCache.get(url), url) {
        value = ImageMemoryCache.get(url) ?: withContext(Dispatchers.IO) {
            ImageMemoryCache.load(url)
        }
    }

    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(Color(0xFF25282F), Color(0xFF111319)),
            ),
        ),
    ) {
        bitmap.value?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

private object ImageMemoryCache {
    private const val MAX_CACHE_KB = 48 * 1024
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 10_000

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun get(url: String?): Bitmap? = url?.let(cache::get)

    fun load(url: String?): Bitmap? {
        val safeUrl = url ?: return null
        if (!isAllowed(safeUrl)) return null
        cache.get(safeUrl)?.let { return it }
        val connection = runCatching {
            URI.create(safeUrl).toURL().openConnection() as HttpURLConnection
        }.getOrNull() ?: return null

        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Marquee/2.0 AndroidTV")
            if (connection.responseCode !in 200..299) return null
            BitmapFactory.decodeStream(connection.inputStream)?.also { bitmap ->
                cache.put(safeUrl, bitmap)
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun isAllowed(url: String?): Boolean = UrlPolicy.isTmdbImage(url)
}
