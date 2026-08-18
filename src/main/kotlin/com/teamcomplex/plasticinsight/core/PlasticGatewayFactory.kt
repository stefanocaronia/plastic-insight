package com.teamcomplex.plasticinsight.core

import java.io.IOException
import java.nio.file.Path
import java.time.Duration

class PlasticGatewayFactory(
    private val executableResolver: PlasticExecutableResolver = PlasticExecutableResolver(),
    private val runnerFactory: () -> PlasticProcessRunner = ::JdkPlasticProcessRunner,
    private val workspaceLocator: PlasticWorkspaceLocator = PlasticWorkspaceLocator(),
) {
    fun create(
        settings: PlasticRuntimeSettings = PlasticRuntimeSettings(),
        cacheLimits: PlasticGatewayCacheLimits = PlasticGatewayCacheLimits(),
        diagnosticSink: PlasticDiagnosticSink = PlasticDiagnosticSink.NONE,
    ): PlasticResult<PlasticGateway> {
        val startedAt = System.nanoTime()
        val resolution = executableResolver.resolve(settings)
        val duration = elapsedSince(startedAt)

        return when (resolution) {
            is PlasticExecutableResolution.Resolved -> {
                val lookupDirectory = realDirectory(settings.historicalLookupDirectory)
                if (lookupDirectory == null || workspaceLocator.findRoot(lookupDirectory) != null) {
                    failure(
                        reason = PlasticFailure.InvalidRuntimeConfiguration,
                        duration = duration,
                        sink = diagnosticSink,
                    )
                } else {
                    val diagnostic = PlasticDiagnostic(
                        operation = PlasticOperation.EXECUTABLE_RESOLUTION,
                        origin = PlasticDiagnosticOrigin.PRECHECK,
                        outcome = PlasticDiagnosticOutcome.SUCCESS,
                        duration = duration,
                    )
                    diagnosticSink.recordSafely(diagnostic)
                    PlasticResult.Success(
                        value = DefaultPlasticGateway(
                            cli = PlasticCli(
                                runner = runnerFactory(),
                                executable = resolution.executable.toString(),
                                timeout = settings.commandTimeout,
                            ),
                            historicalLookupDirectory = lookupDirectory,
                            workspaceLocator = workspaceLocator,
                            cacheLimits = cacheLimits,
                            diagnosticSink = diagnosticSink,
                        ),
                        diagnostic = diagnostic,
                    )
                }
            }

            is PlasticExecutableResolution.InvalidOverride ->
                failure(
                    reason = PlasticFailure.InvalidExecutableConfiguration(resolution.source),
                    duration = duration,
                    sink = diagnosticSink,
                )

            PlasticExecutableResolution.NotFound ->
                failure(
                    reason = PlasticFailure.ExecutableNotFound,
                    duration = duration,
                    sink = diagnosticSink,
                )
        }
    }

    private fun failure(
        reason: PlasticFailure,
        duration: Duration,
        sink: PlasticDiagnosticSink,
    ): PlasticResult.Failure {
        val diagnostic = PlasticDiagnostic(
            operation = PlasticOperation.EXECUTABLE_RESOLUTION,
            origin = PlasticDiagnosticOrigin.PRECHECK,
            outcome = PlasticDiagnosticOutcome.FAILED,
            duration = duration,
        )
        sink.recordSafely(diagnostic)
        return PlasticResult.Failure(reason, diagnostic)
    }

    private fun elapsedSince(startedAt: Long): Duration =
        Duration.ofNanos((System.nanoTime() - startedAt).coerceAtLeast(0L))

    private fun realDirectory(path: Path): Path? =
        try {
            path.toRealPath().takeIf { java.nio.file.Files.isDirectory(it) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
}
