package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.diagnostic.Logger
import com.teamcomplex.plasticinsight.core.PlasticDiagnostic
import com.teamcomplex.plasticinsight.core.PlasticDiagnosticOutcome
import com.teamcomplex.plasticinsight.core.PlasticDiagnosticSink

/** The plugin's only logger: command diagnostics and adapter failures go to Rider's log. */
internal object PlasticLog {
    private val logger: Logger = Logger.getInstance("#com.teamcomplex.plasticinsight")

    val diagnosticSink: PlasticDiagnosticSink = PlasticDiagnosticSink { diagnostic -> write(diagnostic) }

    fun debug(message: String?) {
        logger.debug(message)
    }

    private fun write(diagnostic: PlasticDiagnostic) {
        if (diagnostic.isWarning()) {
            logger.warn(diagnostic.logLine())
        } else if (logger.isDebugEnabled) {
            logger.debug(diagnostic.logLine())
        }
    }
}

/** Failures, timeouts and truncated output are warnings; everything else is debug detail. */
internal fun PlasticDiagnostic.isWarning(): Boolean =
    when (outcome) {
        PlasticDiagnosticOutcome.FAILED,
        PlasticDiagnosticOutcome.TIMED_OUT,
        PlasticDiagnosticOutcome.TRUNCATED,
        -> true

        PlasticDiagnosticOutcome.SUCCESS,
        PlasticDiagnosticOutcome.NOT_FOUND,
        PlasticDiagnosticOutcome.CANCELLED,
        -> false
    }

/** No paths, arguments or content: `Plastic STATUS FAILED (PROCESS): exit code 3, 120 ms, 0 stdout bytes, 85 stderr bytes`. */
internal fun PlasticDiagnostic.logLine(): String =
    "Plastic $operation $outcome ($origin): " +
        "exit code ${exitCode ?: "none"}, " +
        "${duration.toMillis()} ms, " +
        "$standardOutputBytesRead stdout bytes, " +
        "$standardErrorBytesRead stderr bytes"
