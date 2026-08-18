package com.teamcomplex.plasticinsight.core

import java.time.Duration

data class PlasticTextResult(
    val invocation: PlasticInvocation,
    val exitCode: Int?,
    val standardOutput: String,
    val standardError: String,
    val duration: Duration,
    val timedOut: Boolean,
    val standardOutputTruncated: Boolean,
    val standardErrorTruncated: Boolean,
    val standardOutputBytesRead: Long,
    val standardErrorBytesRead: Long,
    val cancelled: Boolean = false,
) {
    val succeeded: Boolean
        get() = !timedOut && !cancelled && !standardOutputTruncated && !standardErrorTruncated && exitCode == 0

    init {
        require(!(timedOut && cancelled)) { "A Plastic command cannot be both timed out and cancelled." }
    }
}
