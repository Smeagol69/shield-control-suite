package dev.roesler.marquee.data

internal data class PageResult<T>(
    val items: List<T>,
    val totalPages: Int,
)

internal fun <T, K> collectUniquePages(
    targetSize: Int,
    maxPages: Int,
    keyOf: (T) -> K,
    fetchPage: (Int) -> PageResult<T>,
): List<T> {
    require(targetSize > 0) { "targetSize must be positive" }
    require(maxPages > 0) { "maxPages must be positive" }

    val collected = linkedMapOf<K, T>()
    var page = 1
    var lastPage = maxPages

    while (page <= lastPage && collected.size < targetSize) {
        val result = fetchPage(page)
        lastPage = minOf(result.totalPages.coerceAtLeast(page), maxPages)
        result.items.forEach { item -> collected.putIfAbsent(keyOf(item), item) }
        if (result.items.isEmpty()) break
        page += 1
    }

    return collected.values.take(targetSize)
}
