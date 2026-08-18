package com.teamcomplex.plasticinsight.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdkPlasticProcessRunnerTest {
    private lateinit var temporaryDirectory: Path
    private val runner = JdkPlasticProcessRunner()

    @BeforeTest
    fun createTemporaryDirectory() {
        temporaryDirectory = Files.createTempDirectory("plastic-process-runner-test-")
    }

    @AfterTest
    fun deleteTemporaryDirectory() {
        Files.walk(temporaryDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> Files.deleteIfExists(path) }
        }
    }

    @Test
    fun `pre-cancelled invocation never starts a process`() {
        val cancellation = PlasticCancellationSource().also { it.cancel() }
        val invocation = PlasticInvocation(
            executable = "missing-executable-that-must-not-be-started",
            arguments = emptyList(),
            workingDirectory = temporaryDirectory,
            timeout = Duration.ofSeconds(1),
        )

        val result = runner.run(invocation, cancellation.token)

        assertTrue(result.cancelled)
        assertFalse(result.timedOut)
        assertFalse(result.succeeded)
        assertNull(result.exitCode)
    }

    @Test
    fun `captures concurrent streams while retaining only their limits`() {
        val byteCount = 128 * 1024
        val result = runner.run(
            probeInvocation(
                arguments = listOf("output", byteCount.toString()),
                standardOutputLimitBytes = 1024,
                standardErrorLimitBytes = 2048,
            ),
        )

        assertEquals(0, result.exitCode)
        assertEquals(byteCount.toLong(), result.standardOutputBytesRead)
        assertEquals(byteCount.toLong(), result.standardErrorBytesRead)
        assertEquals(1024, result.standardOutput.size)
        assertEquals(2048, result.standardError.size)
        assertTrue(result.standardOutputTruncated)
        assertTrue(result.standardErrorTruncated)
        assertFalse(result.succeeded)
    }

    @Test
    fun `timeout is distinct from cancellation and returns promptly`() {
        val result = runner.run(
            probeInvocation(
                arguments = listOf("sleep"),
                timeout = Duration.ofMillis(150),
            ),
        )

        assertTrue(result.timedOut)
        assertFalse(result.cancelled)
        assertFalse(result.succeeded)
        assertNull(result.exitCode)
        assertTrue(result.duration < Duration.ofSeconds(5))
    }

    @Test
    fun `cancellation terminates the process and its descendants`() {
        val readyFile = temporaryDirectory.resolve("cancelled-child.pid")
        val cancellation = PlasticCancellationSource()
        val canceller = Thread.ofPlatform().start {
            waitForFile(readyFile)
            cancellation.cancel()
        }

        val result = runner.run(spawnChildInvocation(readyFile), cancellation.token)
        canceller.join(5_000L)

        assertFalse(canceller.isAlive)
        assertTrue(result.cancelled)
        assertFalse(result.timedOut)
        assertNull(result.exitCode)
        assertProcessStopped(readProcessId(readyFile))
    }

    @Test
    fun `thread interruption cancels and cleans up while preserving interrupt status`() {
        val readyFile = temporaryDirectory.resolve("interrupted-child.pid")
        val result = AtomicReference<PlasticProcessResult>()
        val failure = AtomicReference<Throwable>()
        val interruptPreserved = AtomicBoolean()
        val worker = Thread.ofPlatform().start {
            try {
                result.set(runner.run(spawnChildInvocation(readyFile)))
                interruptPreserved.set(Thread.currentThread().isInterrupted)
            } catch (throwable: Throwable) {
                failure.set(throwable)
            }
        }

        try {
            waitForFile(readyFile)
            worker.interrupt()
            worker.join(5_000L)
        } finally {
            if (worker.isAlive) {
                worker.interrupt()
                worker.join(5_000L)
            }
        }

        assertFalse(worker.isAlive)
        assertNull(failure.get())
        assertTrue(assertNotNull(result.get()).cancelled)
        assertTrue(interruptPreserved.get())
        assertProcessStopped(readProcessId(readyFile))
    }

    private fun probeInvocation(
        arguments: List<String>,
        timeout: Duration = Duration.ofSeconds(10),
        standardOutputLimitBytes: Int = 64 * 1024,
        standardErrorLimitBytes: Int = 64 * 1024,
    ): PlasticInvocation =
        PlasticInvocation(
            executable = javaExecutable,
            arguments = listOf("-cp", probeClassPath, PlasticProcessProbe::class.java.name) + arguments,
            workingDirectory = temporaryDirectory,
            timeout = timeout,
            standardOutputLimitBytes = standardOutputLimitBytes,
            standardErrorLimitBytes = standardErrorLimitBytes,
        )

    private fun spawnChildInvocation(readyFile: Path): PlasticInvocation =
        probeInvocation(
            arguments = listOf(
                "spawn-child",
                javaExecutable,
                probeClassPath,
                readyFile.toString(),
            ),
            timeout = Duration.ofSeconds(20),
        )

    private fun waitForFile(path: Path) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(10L)
        }
        check(Files.exists(path)) { "The process probe did not create '$path'." }
    }

    private fun readProcessId(path: Path): Long = Files.readString(path).trim().toLong()

    private fun assertProcessStopped(processId: Long) {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) {
            Thread.sleep(10L)
        }
        assertFalse(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false))
    }

    private val javaExecutable: String
        get() {
            val executableName = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
            return Path.of(System.getProperty("java.home"), "bin", executableName).toString()
        }

    private val probeClassPath: String
        get() = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "test").toString()
}
