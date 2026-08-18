package com.teamcomplex.plasticinsight.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedLruCacheTest {
    @Test
    fun `evicts the least recently used entry at the count limit`() {
        val cache = cache(maxEntries = 2, maxWeight = 100)
        cache.put("first", "1")
        cache.put("second", "22")
        assertEquals("1", cache["first"])

        cache.put("third", "333")

        assertEquals("1", cache["first"])
        assertNull(cache["second"])
        assertEquals("333", cache["third"])
        assertEquals(2, cache.entryCount)
        assertEquals(4, cache.retainedWeight)
    }

    @Test
    fun `evicts entries deterministically at the weight limit`() {
        val cache = cache(maxEntries = 4, maxWeight = 5)
        cache.put("first", "111")

        cache.put("second", "222")

        assertNull(cache["first"])
        assertEquals("222", cache["second"])
        assertEquals(3, cache.retainedWeight)
    }

    @Test
    fun `does not cache an oversized value or retain an older value for its key`() {
        val cache = cache(maxEntries = 2, maxWeight = 3)
        cache.put("key", "old")

        val inserted = cache.put("key", "oversized")

        assertFalse(inserted)
        assertNull(cache["key"])
        assertEquals(0, cache.entryCount)
        assertEquals(0, cache.retainedWeight)
    }

    @Test
    fun `replacement adjusts retained weight`() {
        val cache = cache(maxEntries = 2, maxWeight = 10)
        cache.put("key", "1")

        assertTrue(cache.put("key", "12345"))

        assertEquals("12345", cache["key"])
        assertEquals(1, cache.entryCount)
        assertEquals(5, cache.retainedWeight)
    }

    @Test
    fun `invalidate and clear release entries and weight`() {
        val cache = cache(maxEntries = 3, maxWeight = 10)
        cache.put("first", "1")
        cache.put("second", "22")

        assertTrue(cache.invalidate("first"))
        assertFalse(cache.invalidate("missing"))
        assertEquals(1, cache.entryCount)
        assertEquals(2, cache.retainedWeight)

        cache.clear()

        assertEquals(0, cache.entryCount)
        assertEquals(0, cache.retainedWeight)
        assertNull(cache["second"])
    }

    private fun cache(
        maxEntries: Int,
        maxWeight: Long,
    ): BoundedLruCache<String, String> =
        BoundedLruCache(
            maxEntries = maxEntries,
            maxWeight = maxWeight,
            weigh = { _, value -> value.length.toLong() },
        )
}
