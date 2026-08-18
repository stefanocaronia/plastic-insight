package com.teamcomplex.plasticinsight.core

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs a single bounded process and captures stdout and stderr independently. */
class JdkPlasticProcessRunner : PlasticProcessRunner {
    override fun run(invocation: PlasticInvocation): PlasticProcessResult {
        val startedAt = System.nanoTime()
        val process = ProcessBuilder(invocation.commandLine())
            .directory(invocation.workingDirectory.toFile())
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()

        process.outputStream.close()

        val streamExecutor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            val standardOutput = copyAsync(
                process.inputStream,
                invocation.standardOutputLimitBytes,
                streamExecutor,
            )
            val standardError = copyAsync(
                process.errorStream,
                invocation.standardErrorLimitBytes,
                streamExecutor,
            )
            val completed = process.waitFor(invocation.timeout.toMillis(), TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroy()
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }

            val capturedOutput = standardOutput.join()
            val capturedError = standardError.join()

            return PlasticProcessResult(
                exitCode = if (completed) process.exitValue() else null,
                standardOutput = capturedOutput.bytes,
                standardError = capturedError.bytes,
                duration = Duration.ofNanos(System.nanoTime() - startedAt),
                timedOut = !completed,
                standardOutputTruncated = capturedOutput.truncated,
                standardErrorTruncated = capturedError.truncated,
                standardOutputBytesRead = capturedOutput.totalBytesRead,
                standardErrorBytesRead = capturedError.totalBytesRead,
            )
        } finally {
            streamExecutor.close()
        }
    }

    private fun copyAsync(
        input: java.io.InputStream,
        limitBytes: Int,
        executor: Executor,
    ): CompletableFuture<BoundedOutputCapture> =
        CompletableFuture.supplyAsync(
            {
                input.use { source ->
                    captureBoundedOutput(source, limitBytes)
                }
            },
            executor,
        )
}
