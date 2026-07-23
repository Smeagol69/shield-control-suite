package dev.roesler.marquee.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import dev.roesler.marquee.data.UrlPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

@Composable
fun RemoteImage(
    url: String?,
    description: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val appContext = LocalContext.current.applicationContext
    val safeUrl = remember(url) { UrlPolicy.canonicalMediaImage(url) }
    val bitmap = produceState<Bitmap?>(initialValue = ImageCache.get(safeUrl), safeUrl) {
        value = ImageCache.get(safeUrl) ?: withContext(Dispatchers.IO) {
            ImageCache.load(safeUrl, appContext.cacheDir)
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

private object ImageCache {
    private const val MAX_MEMORY_CACHE_KB = 48 * 1024
    private const val MAX_DISK_CACHE_BYTES = 96L * 1024L * 1024L
    private const val MAX_DISK_FILES = 300
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_DECODED_EDGE = 2_048
    private const val MAX_DECODED_PIXELS = 4_194_304L
    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val PRUNE_INTERVAL = 24
    private const val CACHE_DIRECTORY = "marquee-images"

    private val memory = object : LruCache<String, Bitmap>(MAX_MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val writes = AtomicInteger()
    private val diskLock = Any()

    fun get(url: String?): Bitmap? = url?.let(memory::get)

    fun load(url: String?, cacheRoot: File): Bitmap? {
        val safeUrl = url ?: return null
        memory.get(safeUrl)?.let { return it }

        val directory = File(cacheRoot, CACHE_DIRECTORY)
        val diskFile = File(directory, safeUrl.sha256())
        readDisk(diskFile)?.let { bitmap ->
            memory.put(safeUrl, bitmap)
            return bitmap
        }

        val connection = runCatching {
            URI.create(safeUrl).toURL().openConnection() as HttpURLConnection
        }.getOrNull() ?: return null

        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Marquee/2.3.0 AndroidTV")
            if (connection.responseCode !in 200..299) return null
            val length = connection.contentLengthLong
            if (length > MAX_IMAGE_BYTES) return null
            val bytes = connection.inputStream.use { it.readBounded(MAX_IMAGE_BYTES) }
                ?: return null
            decodeImage(bytes)?.also { bitmap ->
                memory.put(safeUrl, bitmap)
                writeDisk(directory, diskFile, bytes)
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readDisk(file: File): Bitmap? {
        if (!file.isFile || file.length() !in 1..MAX_IMAGE_BYTES.toLong()) return null
        val bitmap = runCatching { decodeImage(file.readBytes()) }.getOrNull()
        if (bitmap == null) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return bitmap
    }

    private fun decodeImage(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_DECODED_EDGE ||
            bounds.outHeight / sampleSize > MAX_DECODED_EDGE ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() /
            (sampleSize.toLong() * sampleSize.toLong()) > MAX_DECODED_PIXELS
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }

    private fun writeDisk(directory: File, destination: File, bytes: ByteArray) {
        synchronized(diskLock) {
            if (!directory.isDirectory && !directory.mkdirs()) return
            if (!destination.isFile) {
                val temporary = File.createTempFile("image-", ".tmp", directory)
                try {
                    temporary.outputStream().use { it.write(bytes) }
                    if (!temporary.renameTo(destination)) {
                        destination.outputStream().use { it.write(bytes) }
                    }
                } finally {
                    temporary.delete()
                }
            }
            destination.setLastModified(System.currentTimeMillis())
            if (writes.incrementAndGet() % PRUNE_INTERVAL == 0) prune(directory)
        }
    }

    private fun prune(directory: File) {
        val files = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy(File::lastModified)
            .orEmpty()
            .toMutableList()
        var totalBytes = files.sumOf(File::length)
        while (files.size > MAX_DISK_FILES || totalBytes > MAX_DISK_CACHE_BYTES) {
            val oldest = files.removeFirstOrNull() ?: break
            val length = oldest.length()
            if (oldest.delete()) totalBytes -= length
        }
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(available().coerceAtLeast(0), maxBytes))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
