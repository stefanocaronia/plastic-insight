package com.teamcomplex.plasticinsight.rider

import com.teamcomplex.plasticinsight.core.PlasticDiagnostic
import com.teamcomplex.plasticinsight.core.PlasticDiagnosticOrigin
import com.teamcomplex.plasticinsight.core.PlasticDiagnosticOutcome
import com.teamcomplex.plasticinsight.core.PlasticOperation
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class PlasticLogTest {
    @Test
    fun `only failures, timeouts and truncated output are warnings`() {
        val warnings = PlasticDiagnosticOutcome.entries.filter { outcome -> diagnostic(outcome).isWarning() }

        assertEquals(
            listOf(
                PlasticDiagnosticOutcome.FAILED,
                PlasticDiagnosticOutcome.TIMED_OUT,
                PlasticDiagnosticOutcome.TRUNCATED,
            ),
            warnings,
        )
    }

    @Test
    fun `a process failure is logged with its exit code, duration and byte counts`() {
        val failure = PlasticDiagnostic(
            operation = PlasticOperation.STATUS,
            origin = PlasticDiagnosticOrigin.PROCESS,
            outcome = PlasticDiagnosticOutcome.FAILED,
            duration = Duration.ofMillis(120),
            exitCode = 3,
            standardOutputBytesRead = 0,
            standardErrorBytesRead = 85,
        )

        assertEquals(
            "Plastic STATUS FAILED (PROCESS): exit code 3, 120 ms, 0 stdout bytes, 85 stderr bytes",
            failure.logLine(),
        )
    }

    @Test
    fun `output that could not be read is logged as a parsing failure`() {
        val failure = PlasticDiagnostic(
            operation = PlasticOperation.STATUS,
            origin = PlasticDiagnosticOrigin.PARSING,
            outcome = PlasticDiagnosticOutcome.FAILED,
            duration = Duration.ofMillis(753),
            exitCode = 0,
            standardOutputBytesRead = 41150,
            standardErrorBytesRead = 0,
        )

        assertEquals(
            "Plastic STATUS FAILED (PARSING): exit code 0, 753 ms, 41150 stdout bytes, 0 stderr bytes",
            failure.logLine(),
        )
    }

    @Test
    fun `a diagnostic without a process prints no exit code and zero bytes`() {
        val cacheHit = PlasticDiagnostic(
            operation = PlasticOperation.BASE_CONTENT,
            origin = PlasticDiagnosticOrigin.CACHE,
            outcome = PlasticDiagnosticOutcome.SUCCESS,
        )

        assertEquals(
            "Plastic BASE_CONTENT SUCCESS (CACHE): exit code none, 0 ms, 0 stdout bytes, 0 stderr bytes",
            cacheHit.logLine(),
        )
    }

    @Test
    fun `the duration is printed in whole milliseconds`() {
        val slow = diagnostic(PlasticDiagnosticOutcome.SUCCESS, Duration.ofMillis(1900))
        val fast = diagnostic(PlasticDiagnosticOutcome.SUCCESS, Duration.ofNanos(400_000))

        assertEquals(
            "Plastic STATUS SUCCESS (PROCESS): exit code none, 1900 ms, 0 stdout bytes, 0 stderr bytes",
            slow.logLine(),
        )
        assertEquals(
            "Plastic STATUS SUCCESS (PROCESS): exit code none, 0 ms, 0 stdout bytes, 0 stderr bytes",
            fast.logLine(),
        )
    }

    private fun diagnostic(
        outcome: PlasticDiagnosticOutcome,
        duration: Duration = Duration.ZERO,
    ): PlasticDiagnostic =
        PlasticDiagnostic(
            operation = PlasticOperation.STATUS,
            origin = PlasticDiagnosticOrigin.PROCESS,
            outcome = outcome,
            duration = duration,
        )
}
