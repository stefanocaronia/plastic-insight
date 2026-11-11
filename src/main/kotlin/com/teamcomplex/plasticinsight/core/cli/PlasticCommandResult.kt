package com.teamcomplex.plasticinsight.core.cli

import com.teamcomplex.plasticinsight.core.process.PlasticInvocation
import java.time.Duration

/** What one `cm` command returned; the standard output is text or raw bytes. */
data class PlasticCommandResult<T>(
    val invocation: PlasticInvocation,
    val exitCode: Int?,
    val standardOutput: T,
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

typealias PlasticTextResult = PlasticCommandResult<String>

typealias PlasticBinaryResult = PlasticCommandResult<ByteArray>
