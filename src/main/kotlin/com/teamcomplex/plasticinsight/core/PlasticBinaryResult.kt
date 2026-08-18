package com.teamcomplex.plasticinsight.core

import java.time.Duration

data class PlasticBinaryResult(
    val invocation: PlasticInvocation,
    val exitCode: Int?,
    val standardOutput: ByteArray,
    val standardError: String,
    val duration: Duration,
    val timedOut: Boolean,
    val standardOutputTruncated: Boolean,
    val standardErrorTruncated: Boolean,
    val standardOutputBytesRead: Long,
    val standardErrorBytesRead: Long,
) {
    val succeeded: Boolean
        get() = !timedOut && !standardOutputTruncated && !standardErrorTruncated && exitCode == 0
}
