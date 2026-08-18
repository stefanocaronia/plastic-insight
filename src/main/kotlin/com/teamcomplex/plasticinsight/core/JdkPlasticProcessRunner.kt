package com.teamcomplex.plasticinsight.core

import java.io.InputStream
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs one bounded process and owns its readers and full process tree. */
class JdkPlasticProcessRunner : PlasticProcessRunner {
    override fun run(invocation: PlasticInvocation): PlasticProcessResult =
        run(invocation, PlasticCancellation.NONE)

    override fun run(
        invocation: PlasticInvocation,
        cancellation: PlasticCancellation,
    ): PlasticProcessResult {
        val startedAt = System.nanoTime()
        if (cancellation.isCancellationRequested() || Thread.currentThread().isInterrupted) {
            return PlasticProcessResult.cancelled(elapsedSince(startedAt))
        }

        val streamExecutor = Executors.newVirtualThreadPerTaskExecutor()
        val process = try {
            ProcessBuilder(invocation.commandLine())
                .directory(invocation.workingDirectory.toFile())
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start()
        } catch (throwable: Throwable) {
            streamExecutor.shutdownNow()
            throw throwable
        }
        val interruptState = InterruptState()
        var standardOutputFuture: Future<BoundedOutputCapture>? = null
        var standardErrorFuture: Future<BoundedOutputCapture>? = null
        var outcome: ProcessOutcome? = null
        var capturedOutput = EMPTY_CAPTURE
        var capturedError = EMPTY_CAPTURE

        try {
            process.outputStream.close()
            standardOutputFuture = copyAsync(
                process.inputStream,
                invocation.standardOutputLimitBytes,
                streamExecutor,
            )
            standardErrorFuture = copyAsync(
                process.errorStream,
                invocation.standardErrorLimitBytes,
                streamExecutor,
            )

            outcome = waitForProcess(process, invocation.timeout, cancellation, interruptState)
            if (outcome != ProcessOutcome.COMPLETED) {
                terminateProcessTree(process, interruptState)
            }

            val captureDeadline = Deadline(STREAM_CAPTURE_TIMEOUT)
            capturedOutput = awaitCapture(standardOutputFuture, captureDeadline, interruptState)
            capturedError = awaitCapture(standardErrorFuture, captureDeadline, interruptState)
        } finally {
            if (process.isAlive) {
                terminateProcessTree(process, interruptState)
            }
            closeQuietly(process.outputStream)
            closeQuietly(process.inputStream)
            closeQuietly(process.errorStream)
            standardOutputFuture?.cancel(true)
            standardErrorFuture?.cancel(true)
            shutdownReaders(streamExecutor, interruptState)
            if (interruptState.observed) {
                Thread.currentThread().interrupt()
            }
        }

        val processOutcome = checkNotNull(outcome)
        val cancelled = processOutcome == ProcessOutcome.CANCELLED ||
            (processOutcome == ProcessOutcome.COMPLETED && interruptState.observed)
        val timedOut = processOutcome == ProcessOutcome.TIMED_OUT
        return PlasticProcessResult(
            exitCode = if (cancelled || timedOut) null else process.exitValue(),
            standardOutput = capturedOutput.bytes,
            standardError = capturedError.bytes,
            duration = elapsedSince(startedAt),
            timedOut = timedOut,
            standardOutputTruncated = capturedOutput.truncated,
            standardErrorTruncated = capturedError.truncated,
            standardOutputBytesRead = capturedOutput.totalBytesRead,
            standardErrorBytesRead = capturedError.totalBytesRead,
            cancelled = cancelled,
        )
    }

    private fun waitForProcess(
        process: Process,
        timeout: Duration,
        cancellation: PlasticCancellation,
        interruptState: InterruptState,
    ): ProcessOutcome {
        val deadline = Deadline(timeout)
        while (true) {
            if (cancellation.isCancellationRequested()) return ProcessOutcome.CANCELLED

            val remainingNanos = deadline.remainingNanos()
            if (remainingNanos <= 0L) return ProcessOutcome.TIMED_OUT

            try {
                if (process.waitFor(minOf(remainingNanos, POLL_INTERVAL_NANOS), TimeUnit.NANOSECONDS)) {
                    return ProcessOutcome.COMPLETED
                }
            } catch (_: InterruptedException) {
                interruptState.observed = true
                return ProcessOutcome.CANCELLED
            }
        }
    }

    private fun copyAsync(
        input: InputStream,
        limitBytes: Int,
        executor: ExecutorService,
    ): Future<BoundedOutputCapture> =
        executor.submit<BoundedOutputCapture> {
            input.use { source -> captureBoundedOutput(source, limitBytes) }
        }

    private fun awaitCapture(
        future: Future<BoundedOutputCapture>,
        deadline: Deadline,
        interruptState: InterruptState,
    ): BoundedOutputCapture {
        while (true) {
            val remainingNanos = deadline.remainingNanos()
            if (remainingNanos <= 0L) {
                throw IllegalStateException("Plastic process stream readers did not finish within the bounded cleanup period.")
            }
            try {
                return future.get(minOf(remainingNanos, POLL_INTERVAL_NANOS), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                // Keep the wait cancellable and bounded by the shared deadline.
            } catch (_: InterruptedException) {
                interruptState.observed = true
            } catch (exception: ExecutionException) {
                throw IllegalStateException("Failed to capture Plastic process output.", exception.cause)
            }
        }
    }

    private fun terminateProcessTree(
        process: Process,
        interruptState: InterruptState,
    ) {
        val descendants = LinkedHashMap<Long, ProcessHandle>()
        collectDescendants(process, descendants)
        descendants.values.forEach { handle -> runCatching { handle.destroy() } }
        runCatching { process.destroy() }

        if (waitForTreeExit(process, descendants.values, GRACEFUL_TERMINATION_TIMEOUT, interruptState)) return

        collectDescendants(process, descendants)
        descendants.values.forEach { handle -> runCatching { handle.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
        waitForTreeExit(process, descendants.values, FORCEFUL_TERMINATION_TIMEOUT, interruptState)
    }

    private fun collectDescendants(
        process: Process,
        destination: MutableMap<Long, ProcessHandle>,
    ) {
        runCatching {
            process.descendants().use { handles ->
                handles.forEach { handle -> destination[handle.pid()] = handle }
            }
        }
    }

    private fun waitForTreeExit(
        process: Process,
        descendants: Collection<ProcessHandle>,
        timeout: Duration,
        interruptState: InterruptState,
    ): Boolean {
        val deadline = Deadline(timeout)
        while (process.isAlive || descendants.any(ProcessHandle::isAlive)) {
            val remainingNanos = deadline.remainingNanos()
            if (remainingNanos <= 0L) return false
            try {
                TimeUnit.NANOSECONDS.sleep(minOf(remainingNanos, TERMINATION_POLL_NANOS))
            } catch (_: InterruptedException) {
                interruptState.observed = true
            }
        }
        return true
    }

    private fun shutdownReaders(
        executor: ExecutorService,
        interruptState: InterruptState,
    ) {
        executor.shutdownNow()
        val deadline = Deadline(STREAM_EXECUTOR_SHUTDOWN_TIMEOUT)
        while (!executor.isTerminated) {
            val remainingNanos = deadline.remainingNanos()
            if (remainingNanos <= 0L) return
            try {
                if (executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) return
            } catch (_: InterruptedException) {
                interruptState.observed = true
            }
        }
    }

    private fun closeQuietly(stream: AutoCloseable) {
        runCatching { stream.close() }
    }

    private fun elapsedSince(startedAt: Long): Duration =
        Duration.ofNanos((System.nanoTime() - startedAt).coerceAtLeast(0L))

    private class Deadline(timeout: Duration) {
        private val startedAt = System.nanoTime()
        private val timeoutNanos = try {
            timeout.toNanos()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

        fun remainingNanos(): Long =
            (timeoutNanos - (System.nanoTime() - startedAt)).coerceAtLeast(0L)
    }

    private class InterruptState(
        var observed: Boolean = false,
    )

    private enum class ProcessOutcome {
        COMPLETED,
        TIMED_OUT,
        CANCELLED,
    }

    private companion object {
        val EMPTY_CAPTURE = BoundedOutputCapture(byteArrayOf(), 0L, truncated = false)
        val STREAM_CAPTURE_TIMEOUT: Duration = Duration.ofSeconds(2)
        val STREAM_EXECUTOR_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(1)
        val GRACEFUL_TERMINATION_TIMEOUT: Duration = Duration.ofMillis(250)
        val FORCEFUL_TERMINATION_TIMEOUT: Duration = Duration.ofSeconds(1)
        val POLL_INTERVAL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(50)
        val TERMINATION_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(10)
    }
}
