package com.teamcomplex.plasticinsight.core

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedOutputCaptureTest {
    @Test
    fun `retains complete output when it fits the limit`() {
        val source = "Plastic Insight".toByteArray()

        val capture = captureBoundedOutput(ByteArrayInputStream(source), source.size)

        assertContentEquals(source, capture.bytes)
        assertEquals(source.size.toLong(), capture.totalBytesRead)
        assertFalse(capture.truncated)
    }

    @Test
    fun `drains overflow while retaining only the configured prefix`() {
        val source = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        val limit = 1024

        val capture = captureBoundedOutput(ByteArrayInputStream(source), limit)

        assertContentEquals(source.copyOf(limit), capture.bytes)
        assertEquals(source.size.toLong(), capture.totalBytesRead)
        assertTrue(capture.truncated)
    }
}
