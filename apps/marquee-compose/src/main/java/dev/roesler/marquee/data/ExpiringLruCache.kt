package dev.roesler.marquee.data

internal class ExpiringLruCache<K, V>(
    private val maxEntries: Int,
    private val ttlMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
    }

    private data class Entry<V>(val value: V, val storedAtMillis: Long)

    private val entries = object : LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (now() - entry.storedAtMillis >= ttlMillis) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = Entry(value, now())
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
