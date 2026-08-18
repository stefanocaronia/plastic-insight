package com.teamcomplex.plasticinsight.core

sealed interface PlasticResult<out T> {
    val diagnostic: PlasticDiagnostic

    data class Success<T>(
        val value: T,
        override val diagnostic: PlasticDiagnostic,
    ) : PlasticResult<T>

    data class Failure(
        val reason: PlasticFailure,
        override val diagnostic: PlasticDiagnostic,
    ) : PlasticResult<Nothing>
}

sealed interface PlasticFailure {
    data object ExecutableNotFound : PlasticFailure

    data class InvalidExecutableConfiguration(
        val source: PlasticExecutableSource,
    ) : PlasticFailure

    data object InvalidRuntimeConfiguration : PlasticFailure

    data object LaunchFailed : PlasticFailure

    data object ExecutionFailed : PlasticFailure

    data object TimedOut : PlasticFailure

    data object Cancelled : PlasticFailure

    data class OutputLimitExceeded(
        val standardOutput: Boolean,
        val standardError: Boolean,
    ) : PlasticFailure

    data class CommandFailed(
        val exitCode: Int?,
    ) : PlasticFailure

    data object MalformedOutput : PlasticFailure

    data object RevisionUnavailable : PlasticFailure

    data object AmbiguousRevision : PlasticFailure

    data object Disposed : PlasticFailure
}
