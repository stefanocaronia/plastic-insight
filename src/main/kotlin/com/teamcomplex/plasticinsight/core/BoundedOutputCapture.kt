package com.teamcomplex.plasticinsight.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class BoundedOutputCapture(
    val bytes: ByteArray,
    val totalBytesRead: Long,
    val truncated: Boolean,
)

internal fun captureBoundedOutput(
    input: InputStream,
    limitBytes: Int,
): BoundedOutputCapture {
    require(limitBytes > 0) { "The output capture limit must be positive." }

    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytesRead = 0L
    var retainedBytes = 0
    var truncated = false

    ByteArrayOutputStream(minOf(limitBytes, DEFAULT_BUFFER_SIZE)).use { destination ->
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break

            totalBytesRead += count
            val writable = minOf(count, limitBytes - retainedBytes)
            if (writable > 0) {
                destination.write(buffer, 0, writable)
                retainedBytes += writable
            }
            if (writable < count) truncated = true
        }

        return BoundedOutputCapture(
            bytes = destination.toByteArray(),
            totalBytesRead = totalBytesRead,
            truncated = truncated,
        )
    }
}
