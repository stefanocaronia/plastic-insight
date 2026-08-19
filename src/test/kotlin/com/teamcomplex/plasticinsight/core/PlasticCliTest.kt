package com.teamcomplex.plasticinsight.core

import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlasticCliTest {
    @Test
    fun `version uses an argument-safe one-shot invocation`() {
        val runner = RecordingRunner(
            PlasticProcessResult(
                exitCode = 0,
                standardOutput = "11.0.16.10350\n".toByteArray(),
                standardError = byteArrayOf(),
                duration = Duration.ofMillis(12),
                timedOut = false,
            ),
        )
        val workingDirectory = Path.of("workspace with spaces")
        val cli = PlasticCli(
            runner = runner,
            executable = "C:\\Program Files\\PlasticSCM5\\client\\cm.exe",
        )

        val result = cli.version(workingDirectory)

        assertEquals(
            listOf("C:\\Program Files\\PlasticSCM5\\client\\cm.exe", "version"),
            runner.lastInvocation?.commandLine(),
        )
        assertEquals(workingDirectory, runner.lastInvocation?.workingDirectory)
        assertEquals("11.0.16.10350\n", result.standardOutput)
        assertTrue(result.succeeded)
    }

    @Test
    fun `constrained status executes the dedicated command builder invocation`() {
        val runner = RecordingRunner(success())
        val cli = PlasticCli(runner)
        val workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val scope = workspaceRoot.resolve("Source").resolve("file with spaces.kt")

        cli.constrainedStatus(workspaceRoot, scope)

        assertEquals(
            listOf(
                "cm",
                "status",
                scope.toString(),
                "--machinereadable",
                "--includeRevId",
                "--iscochanged",
                "--controlledchanged",
                "--changed",
                "--localdeleted",
                "--localmoved",
                "--fieldseparator=\u001F",
                "--startlineseparator=\u001E",
                "--endlineseparator=\u001D",
            ),
            runner.lastInvocation?.commandLine(),
        )
        assertEquals(workspaceRoot, runner.lastInvocation?.workingDirectory)
    }

    @Test
    fun `workspace status executes the dedicated filtered root invocation`() {
        val runner = RecordingRunner(success())
        val cli = PlasticCli(runner)
        val workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()

        cli.workspaceStatus(workspaceRoot)

        assertEquals("status", runner.lastInvocation?.arguments?.first())
        assertEquals(workspaceRoot.toString(), runner.lastInvocation?.arguments?.get(1))
        assertEquals(1024 * 1024, runner.lastInvocation?.standardOutputLimitBytes)
        assertTrue("--private" !in requireNotNull(runner.lastInvocation).arguments)

        cli.workspaceStatus(workspaceRoot, includePrivateFiles = true)

        assertTrue("--private" in requireNotNull(runner.lastInvocation).arguments)
        assertTrue("--ignored" in requireNotNull(runner.lastInvocation).arguments)
        assertTrue("--cutignored" in requireNotNull(runner.lastInvocation).arguments)
    }

    @Test
    fun `constrained status forwards the exact private-file option`() {
        val runner = RecordingRunner(success())
        val cli = PlasticCli(runner)
        val workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()

        cli.constrainedStatus(
            workspaceRoot = workspaceRoot,
            scope = workspaceRoot.resolve("new.txt"),
            includePrivateFiles = true,
        )

        assertTrue("--private" in requireNotNull(runner.lastInvocation).arguments)
        assertTrue("--ignored" in requireNotNull(runner.lastInvocation).arguments)
        assertTrue("--cutignored" in requireNotNull(runner.lastInvocation).arguments)
    }

    @Test
    fun `workspace discovery executes the dedicated command builder invocation`() {
        val runner = RecordingRunner(success())
        val cli = PlasticCli(runner, executable = "cm.exe")
        val workingDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val targetPath = workingDirectory.resolve("workspace with spaces").resolve("Ω")

        cli.workspaceFromPath(workingDirectory, targetPath)

        assertEquals(
            listOf(
                "cm.exe",
                "getworkspacefrompath",
                targetPath.toString(),
                "--extended",
                "--format={wkname}\u001F{wkpath}\u001F{machine}\u001F{guid}\u001F{type}\u001F{dynamic}",
            ),
            runner.lastInvocation?.commandLine(),
        )
        assertEquals(workingDirectory, runner.lastInvocation?.workingDirectory)
    }

    @Test
    fun `workspace baseline preserves raw bytes without text conversion`() {
        val rawBytes = byteArrayOf(0, 1, 2, 0x7f, 0x80.toByte(), 0xff.toByte())
        val runner = RecordingRunner(
            success(
                standardOutput = rawBytes,
                standardOutputBytesRead = rawBytes.size.toLong(),
            ),
        )
        val cli = PlasticCli(runner, executable = "cm.exe")
        val workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val basePath = workspaceRoot.resolve("file with spaces.bin")

        val result = cli.workspaceBaseline(workspaceRoot, basePath, workspaceChangeset = 7)

        assertContentEquals(rawBytes, result.standardOutput)
        assertTrue(result.succeeded)
        assertEquals(
            listOf("cm.exe", "cat", "$basePath#cs:7", "--raw"),
            runner.lastInvocation?.commandLine(),
        )
    }

    @Test
    fun `add and undo execute dedicated exact workspace commands`() {
        val runner = RecordingRunner(success())
        val cli = PlasticCli(runner, executable = "cm.exe")
        val workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val file = workspaceRoot.resolve("file with spaces.cs")

        cli.add(workspaceRoot, listOf(file))
        assertEquals(
            listOf("cm.exe", "add", "--noinfo", file.toString()),
            runner.lastInvocation?.commandLine(),
        )

        cli.undo(workspaceRoot, listOf(file))
        assertEquals(
            listOf("cm.exe", "undo", "--silent", file.toString()),
            runner.lastInvocation?.commandLine(),
        )
    }

    @Test
    fun `file history executes the bounded XML command`() {
        val runner = RecordingRunner(success())
        val cli = PlasticCli(runner, executable = "cm.exe")
        val workspaceRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        val filePath = workspaceRoot.resolve("file with spaces.txt")

        cli.fileHistory(workspaceRoot, filePath, limit = 25)

        assertEquals(
            listOf(
                "cm.exe",
                "history",
                filePath.toString(),
                "--xml",
                "--encoding=utf-8",
                "--moveddeleted",
                "--limit=25",
            ),
            runner.lastInvocation?.commandLine(),
        )
    }

    @Test
    fun `historical content preserves raw revision bytes`() {
        val rawBytes = byteArrayOf(0, 0x80.toByte(), 0xff.toByte())
        val runner = RecordingRunner(success(standardOutput = rawBytes))
        val cli = PlasticCli(runner, executable = "cm.exe")
        val executionDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()

        val result = cli.historicalContent(
            executionDirectory = executionDirectory,
            historicalPath = "/Source/Ω.bin",
            changeset = 7,
            repository = "repo",
            server = "server",
        )

        assertContentEquals(rawBytes, result.standardOutput)
        assertTrue(result.succeeded)
        assertEquals(
            listOf("cm.exe", "cat", "serverpath:/Source/Ω.bin#cs:7@repo@server", "--raw"),
            runner.lastInvocation?.commandLine(),
        )
    }

    private class RecordingRunner(
        private val result: PlasticProcessResult,
    ) : PlasticProcessRunner {
        var lastInvocation: PlasticInvocation? = null

        override fun run(invocation: PlasticInvocation): PlasticProcessResult {
            lastInvocation = invocation
            return result
        }
    }

    private companion object {
        fun success(
            standardOutput: ByteArray = byteArrayOf(),
            standardOutputBytesRead: Long = standardOutput.size.toLong(),
        ) = PlasticProcessResult(
            exitCode = 0,
            standardOutput = standardOutput,
            standardError = byteArrayOf(),
            duration = Duration.ZERO,
            timedOut = false,
            standardOutputBytesRead = standardOutputBytesRead,
        )
    }
}
