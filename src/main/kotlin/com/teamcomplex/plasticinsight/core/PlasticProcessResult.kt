package com.teamcomplex.plasticinsight.core

import java.time.Duration

data class PlasticProcessResult(
    val exitCode: Int?,
    val standardOutput: ByteArray,
    val standardError: ByteArray,
    val duration: Duration,
    val timedOut: Boolean,
    val standardOutputTruncated: Boolean = false,
    val standardErrorTruncated: Boolean = false,
    val standardOutputBytesRead: Long = standardOutput.size.toLong(),
    val standardErrorBytesRead: Long = standardError.size.toLong(),
) {
    val succeeded: Boolean
        get() = !timedOut && !standardOutputTruncated && !standardErrorTruncated && exitCode == 0
}
