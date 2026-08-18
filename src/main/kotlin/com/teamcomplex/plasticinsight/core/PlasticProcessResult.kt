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
    val cancelled: Boolean = false,
) {
    val succeeded: Boolean
        get() = !timedOut && !cancelled && !standardOutputTruncated && !standardErrorTruncated && exitCode == 0

    init {
        require(!(timedOut && cancelled)) { "A Plastic process cannot be both timed out and cancelled." }
    }

    companion object {
        fun cancelled(duration: Duration = Duration.ZERO): PlasticProcessResult =
            PlasticProcessResult(
                exitCode = null,
                standardOutput = byteArrayOf(),
                standardError = byteArrayOf(),
                duration = duration,
                timedOut = false,
                cancelled = true,
            )
    }
}
