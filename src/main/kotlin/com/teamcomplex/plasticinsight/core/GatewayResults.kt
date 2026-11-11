package com.teamcomplex.plasticinsight.core

import com.teamcomplex.plasticinsight.core.cli.PlasticBinaryResult
import com.teamcomplex.plasticinsight.core.cli.PlasticTextResult
import java.time.Duration

/** Builds every result the gateway returns and records its diagnostic. */
internal class GatewayResults(
    private val diagnosticSink: PlasticDiagnosticSink,
    private val isDisposed: () -> Boolean,
) {
    fun <T> success(
        operation: PlasticOperation,
        origin: PlasticDiagnosticOrigin,
        value: T,
        outcome: PlasticDiagnosticOutcome = PlasticDiagnosticOutcome.SUCCESS,
    ): PlasticResult.Success<T> {
        val diagnostic = PlasticDiagnostic(operation, origin, outcome)
        diagnosticSink.recordSafely(diagnostic)
        return PlasticResult.Success(value, diagnostic)
    }

    fun <T> processSuccess(
        operation: PlasticOperation,
        state: CommandState,
        value: T,
    ): PlasticResult.Success<T> {
        val diagnostic = state.diagnostic(operation, PlasticDiagnosticOutcome.SUCCESS)
        diagnosticSink.recordSafely(diagnostic)
        return PlasticResult.Success(value, diagnostic)
    }

    fun recordIntermediateCacheHit(operation: PlasticOperation) {
        diagnosticSink.recordSafely(
            PlasticDiagnostic(
                operation = operation,
                origin = PlasticDiagnosticOrigin.CACHE,
                outcome = PlasticDiagnosticOutcome.SUCCESS,
            ),
        )
    }

    fun processFailure(
        operation: PlasticOperation,
        state: CommandState,
    ): PlasticResult.Failure? =
        when {
            state.cancelled -> cancellationFailure(operation, state)

            state.timedOut -> processFailure(operation, state, PlasticFailure.TimedOut)

            state.standardOutputTruncated || state.standardErrorTruncated ->
                processFailure(
                    operation,
                    state,
                    PlasticFailure.OutputLimitExceeded(
                        standardOutput = state.standardOutputTruncated,
                        standardError = state.standardErrorTruncated,
                    ),
                )

            state.exitCode != 0 -> processFailure(operation, state, PlasticFailure.CommandFailed(state.exitCode))

            else -> null
        }

    fun processFailure(
        operation: PlasticOperation,
        state: CommandState,
        reason: PlasticFailure,
    ): PlasticResult.Failure {
        val outcome = when (reason) {
            PlasticFailure.TimedOut -> PlasticDiagnosticOutcome.TIMED_OUT

            PlasticFailure.Cancelled,
            PlasticFailure.Disposed,
            -> PlasticDiagnosticOutcome.CANCELLED

            is PlasticFailure.OutputLimitExceeded -> PlasticDiagnosticOutcome.TRUNCATED

            else -> PlasticDiagnosticOutcome.FAILED
        }

        return failure(operation, state, outcome, reason)
    }

    fun cancellationFailure(
        operation: PlasticOperation,
        state: CommandState,
    ): PlasticResult.Failure =
        processFailure(
            operation = operation,
            state = state,
            reason = if (isDisposed()) PlasticFailure.Disposed else PlasticFailure.Cancelled,
        )

    fun parseFailure(
        operation: PlasticOperation,
        state: CommandState,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            state = state,
            outcome = PlasticDiagnosticOutcome.FAILED,
            reason = PlasticFailure.MalformedOutput,
        )

    fun precheckFailure(
        operation: PlasticOperation,
        reason: PlasticFailure,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            origin = PlasticDiagnosticOrigin.PRECHECK,
            outcome = if (reason == PlasticFailure.Disposed || reason == PlasticFailure.Cancelled) {
                PlasticDiagnosticOutcome.CANCELLED
            } else {
                PlasticDiagnosticOutcome.FAILED
            },
            reason = reason,
        )

    fun launchFailure(
        operation: PlasticOperation,
        startedAt: Long,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            origin = PlasticDiagnosticOrigin.PROCESS,
            outcome = PlasticDiagnosticOutcome.FAILED,
            reason = PlasticFailure.LaunchFailed,
            duration = elapsedSince(startedAt),
        )

    fun executionFailure(
        operation: PlasticOperation,
        startedAt: Long,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            origin = PlasticDiagnosticOrigin.PROCESS,
            outcome = PlasticDiagnosticOutcome.FAILED,
            reason = PlasticFailure.ExecutionFailed,
            duration = elapsedSince(startedAt),
        )

    private fun failure(
        operation: PlasticOperation,
        state: CommandState,
        outcome: PlasticDiagnosticOutcome,
        reason: PlasticFailure,
    ): PlasticResult.Failure {
        val diagnostic = state.diagnostic(operation, outcome)
        diagnosticSink.recordSafely(diagnostic)
        return PlasticResult.Failure(reason, diagnostic)
    }

    private fun failure(
        operation: PlasticOperation,
        origin: PlasticDiagnosticOrigin,
        outcome: PlasticDiagnosticOutcome,
        reason: PlasticFailure,
        duration: Duration = Duration.ZERO,
    ): PlasticResult.Failure {
        val diagnostic = PlasticDiagnostic(operation, origin, outcome, duration)
        diagnosticSink.recordSafely(diagnostic)
        return PlasticResult.Failure(reason, diagnostic)
    }

    private fun elapsedSince(startedAt: Long): Duration = Duration.ofNanos((System.nanoTime() - startedAt).coerceAtLeast(0L))
}

internal data class CommandState(
    val exitCode: Int?,
    val duration: Duration,
    val timedOut: Boolean,
    val cancelled: Boolean,
    val standardOutputTruncated: Boolean,
    val standardErrorTruncated: Boolean,
    val standardOutputBytesRead: Long,
    val standardErrorBytesRead: Long,
) {
    fun diagnostic(
        operation: PlasticOperation,
        outcome: PlasticDiagnosticOutcome,
    ): PlasticDiagnostic =
        PlasticDiagnostic(
            operation = operation,
            origin = PlasticDiagnosticOrigin.PROCESS,
            outcome = outcome,
            duration = duration,
            exitCode = exitCode,
            standardOutputBytesRead = standardOutputBytesRead,
            standardErrorBytesRead = standardErrorBytesRead,
        )
}

internal fun PlasticTextResult.state(): CommandState =
    CommandState(
        exitCode,
        duration,
        timedOut,
        cancelled,
        standardOutputTruncated,
        standardErrorTruncated,
        standardOutputBytesRead,
        standardErrorBytesRead,
    )

internal fun PlasticBinaryResult.state(): CommandState =
    CommandState(
        exitCode,
        duration,
        timedOut,
        cancelled,
        standardOutputTruncated,
        standardErrorTruncated,
        standardOutputBytesRead,
        standardErrorBytesRead,
    )
