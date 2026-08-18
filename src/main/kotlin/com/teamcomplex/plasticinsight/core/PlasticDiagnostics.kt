package com.teamcomplex.plasticinsight.core

import java.time.Duration

enum class PlasticOperation {
    EXECUTABLE_RESOLUTION,
    WORKSPACE_DISCOVERY,
    STATUS,
    BASE_CONTENT,
    FILE_HISTORY,
    HISTORICAL_PATH,
    REVISION_CONTENT,
}

enum class PlasticDiagnosticOrigin {
    PRECHECK,
    PROCESS,
    CACHE,
}

enum class PlasticDiagnosticOutcome {
    SUCCESS,
    NOT_FOUND,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    TRUNCATED,
}

/** Privacy-safe command telemetry: no paths, arguments, stderr, or file content. */
data class PlasticDiagnostic(
    val operation: PlasticOperation,
    val origin: PlasticDiagnosticOrigin,
    val outcome: PlasticDiagnosticOutcome,
    val duration: Duration = Duration.ZERO,
    val exitCode: Int? = null,
    val standardOutputBytesRead: Long = 0,
    val standardErrorBytesRead: Long = 0,
) {
    init {
        require(!duration.isNegative) { "Diagnostic duration must not be negative." }
        require(standardOutputBytesRead >= 0) { "Diagnostic stdout size must not be negative." }
        require(standardErrorBytesRead >= 0) { "Diagnostic stderr size must not be negative." }
    }
}

fun interface PlasticDiagnosticSink {
    fun record(diagnostic: PlasticDiagnostic)

    companion object {
        val NONE = PlasticDiagnosticSink { }
    }
}

internal fun PlasticDiagnosticSink.recordSafely(diagnostic: PlasticDiagnostic) {
    try {
        record(diagnostic)
    } catch (_: RuntimeException) {
        // Diagnostics must never break a VCS operation.
    }
}
