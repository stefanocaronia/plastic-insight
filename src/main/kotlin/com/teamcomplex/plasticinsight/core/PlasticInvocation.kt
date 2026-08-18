package com.teamcomplex.plasticinsight.core

import java.nio.file.Path
import java.time.Duration

/** A shell-free invocation of the Plastic CLI. */
data class PlasticInvocation(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: Path,
    val timeout: Duration,
    val standardOutputLimitBytes: Int = DEFAULT_STANDARD_OUTPUT_LIMIT_BYTES,
    val standardErrorLimitBytes: Int = DEFAULT_STANDARD_ERROR_LIMIT_BYTES,
) {
    init {
        require(executable.isNotBlank()) { "The Plastic executable must not be blank." }
        require(!timeout.isNegative && !timeout.isZero) { "The command timeout must be positive." }
        require(standardOutputLimitBytes > 0) { "The stdout capture limit must be positive." }
        require(standardErrorLimitBytes > 0) { "The stderr capture limit must be positive." }
        require(arguments.none { it.indexOf('\u0000') >= 0 }) { "Plastic arguments must not contain NUL characters." }
    }

    fun commandLine(): List<String> = buildList(arguments.size + 1) {
        add(executable)
        addAll(arguments)
    }

    private companion object {
        const val DEFAULT_STANDARD_OUTPUT_LIMIT_BYTES = 8 * 1024 * 1024
        const val DEFAULT_STANDARD_ERROR_LIMIT_BYTES = 256 * 1024
    }
}
