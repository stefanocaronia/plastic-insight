package com.teamcomplex.plasticinsight.core

import com.teamcomplex.plasticinsight.core.cli.PlasticCommandResult
import java.time.Duration
import kotlin.time.toJavaDuration

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
        result: PlasticCommandResult<*>,
        value: T,
    ): PlasticResult.Success<T> {
        val diagnostic = result.diagnostic(operation, PlasticDiagnosticOutcome.SUCCESS)
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
        result: PlasticCommandResult<*>,
    ): PlasticResult.Failure? =
        when {
            result.cancelled -> cancellationFailure(operation, result)

            result.timedOut -> processFailure(operation, result, PlasticFailure.TimedOut)

            result.standardOutputTruncated || result.standardErrorTruncated ->
                processFailure(
                    operation,
                    result,
                    PlasticFailure.OutputLimitExceeded(
                        standardOutput = result.standardOutputTruncated,
                        standardError = result.standardErrorTruncated,
                    ),
                )

            result.exitCode != 0 -> processFailure(operation, result, PlasticFailure.CommandFailed(result.exitCode))

            else -> null
        }

    fun processFailure(
        operation: PlasticOperation,
        result: PlasticCommandResult<*>,
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

        return failure(operation, result, outcome, reason)
    }

    fun cancellationFailure(
        operation: PlasticOperation,
        result: PlasticCommandResult<*>,
    ): PlasticResult.Failure =
        processFailure(
            operation = operation,
            result = result,
            reason = if (isDisposed()) PlasticFailure.Disposed else PlasticFailure.Cancelled,
        )

    fun parseFailure(
        operation: PlasticOperation,
        result: PlasticCommandResult<*>,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            result = result,
            outcome = PlasticDiagnosticOutcome.FAILED,
            reason = PlasticFailure.MalformedOutput,
            origin = PlasticDiagnosticOrigin.PARSING,
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
        elapsed: kotlin.time.Duration,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            origin = PlasticDiagnosticOrigin.PROCESS,
            outcome = PlasticDiagnosticOutcome.FAILED,
            reason = PlasticFailure.LaunchFailed,
            duration = elapsed.coerceAtLeast(kotlin.time.Duration.ZERO).toJavaDuration(),
        )

    fun executionFailure(
        operation: PlasticOperation,
        elapsed: kotlin.time.Duration,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            origin = PlasticDiagnosticOrigin.PROCESS,
            outcome = PlasticDiagnosticOutcome.FAILED,
            reason = PlasticFailure.ExecutionFailed,
            duration = elapsed.coerceAtLeast(kotlin.time.Duration.ZERO).toJavaDuration(),
        )

    private fun failure(
        operation: PlasticOperation,
        result: PlasticCommandResult<*>,
        outcome: PlasticDiagnosticOutcome,
        reason: PlasticFailure,
        origin: PlasticDiagnosticOrigin = PlasticDiagnosticOrigin.PROCESS,
    ): PlasticResult.Failure {
        val diagnostic = result.diagnostic(operation, outcome, origin)
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

    private fun PlasticCommandResult<*>.diagnostic(
        operation: PlasticOperation,
        outcome: PlasticDiagnosticOutcome,
        origin: PlasticDiagnosticOrigin = PlasticDiagnosticOrigin.PROCESS,
    ): PlasticDiagnostic =
        PlasticDiagnostic(
            operation = operation,
            origin = origin,
            outcome = outcome,
            duration = duration,
            exitCode = exitCode,
            standardOutputBytesRead = standardOutputBytesRead,
            standardErrorBytesRead = standardErrorBytesRead,
        )
}
