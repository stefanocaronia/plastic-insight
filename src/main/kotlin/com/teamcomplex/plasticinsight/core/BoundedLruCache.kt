package com.teamcomplex.plasticinsight.core

/** A thread-safe LRU bounded by both entry count and caller-defined weight. */
internal class BoundedLruCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val maxWeight: Long,
    private val weigh: (K, V) -> Long,
) {
    private val entries = LinkedHashMap<K, Entry<V>>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var currentWeight = 0L

    init {
        require(maxEntries > 0) { "The cache entry limit must be positive." }
        require(maxWeight > 0) { "The cache weight limit must be positive." }
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]?.value

    fun put(
        key: K,
        value: V,
    ): Boolean {
        val weight = weigh(key, value)
        require(weight >= 0) { "A cache entry weight must not be negative." }

        synchronized(this) {
            removeEntry(key)
            if (weight > maxWeight) return false

            while (entries.isNotEmpty() &&
                (entries.size >= maxEntries || currentWeight > maxWeight - weight)
            ) {
                removeEldestEntry()
            }

            entries[key] = Entry(value, weight)
            currentWeight += weight
            return true
        }
    }

    @Synchronized
    fun invalidate(key: K): Boolean = removeEntry(key) != null

    @Synchronized
    fun clear() {
        entries.clear()
        currentWeight = 0
    }

    internal val entryCount: Int
        @Synchronized get() = entries.size

    internal val retainedWeight: Long
        @Synchronized get() = currentWeight

    private fun removeEldestEntry() {
        val iterator = entries.entries.iterator()
        val eldest = iterator.next()
        currentWeight -= eldest.value.weight
        iterator.remove()
    }

    private fun removeEntry(key: K): Entry<V>? =
        entries.remove(key)?.also { removed -> currentWeight -= removed.weight }

    private data class Entry<V>(
        val value: V,
        val weight: Long,
    )

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
