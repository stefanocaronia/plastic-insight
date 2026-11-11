package com.teamcomplex.plasticinsight.core.cli

import com.teamcomplex.plasticinsight.core.process.BoundedOutputCapture
import com.teamcomplex.plasticinsight.core.process.PlasticCancellation
import com.teamcomplex.plasticinsight.core.process.PlasticInvocation
import com.teamcomplex.plasticinsight.core.process.PlasticProcessRunner
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration

/** Thin Plastic command facade. Parsing stays in dedicated collaborators as commands are added. */
class PlasticCli(
    private val runner: PlasticProcessRunner,
    private val executable: String = "cm",
    private val timeout: Duration = Duration.ofSeconds(15),
    private val outputCharset: Charset = StandardCharsets.UTF_8,
) {
    private val commandBuilder = PlasticCommandBuilder(executable, timeout)

    fun version(
        workingDirectory: Path,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticTextResult =
        execute(
            PlasticInvocation(
                executable = executable,
                arguments = listOf("version"),
                workingDirectory = workingDirectory,
                timeout = timeout,
            ),
            cancellation,
        )

    fun constrainedStatus(
        workspaceRoot: Path,
        scope: Path,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
        includePrivateFiles: Boolean = false,
    ): PlasticTextResult = execute(commandBuilder.constrainedStatus(workspaceRoot, scope, includePrivateFiles), cancellation)

    fun workspaceStatus(
        workspaceRoot: Path,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
        includePrivateFiles: Boolean = false,
    ): PlasticTextResult = execute(commandBuilder.workspaceStatus(workspaceRoot, includePrivateFiles), cancellation)

    fun workspaceFromPath(
        workingDirectory: Path,
        targetPath: Path,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticTextResult = execute(commandBuilder.workspaceDiscovery(workingDirectory, targetPath), cancellation)

    fun workspaceBaseline(
        workspaceRoot: Path,
        basePath: Path,
        workspaceChangeset: Long,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticBinaryResult =
        executeContent(cancellation) { outputFile ->
            commandBuilder.workspaceBaseline(workspaceRoot, basePath, workspaceChangeset, outputFile)
        }

    fun add(
        workspaceRoot: Path,
        paths: Collection<Path>,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticTextResult = execute(commandBuilder.add(workspaceRoot, paths), cancellation)

    fun undo(
        workspaceRoot: Path,
        paths: Collection<Path>,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticTextResult = execute(commandBuilder.undo(workspaceRoot, paths), cancellation)

    fun fileHistory(
        workspaceRoot: Path,
        filePath: Path,
        limit: Int,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticBinaryResult = executeBinary(commandBuilder.fileHistory(workspaceRoot, filePath, limit), cancellation)

    fun fileHistoryForServerSpec(
        workspaceRoot: Path,
        itemSpec: String,
        limit: Int,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticBinaryResult = executeBinary(commandBuilder.fileHistoryForServerSpec(workspaceRoot, itemSpec, limit), cancellation)

    fun historicalPathResolution(
        executionDirectory: Path,
        itemId: Long,
        changeset: Long,
        repository: String,
        server: String,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticBinaryResult =
        executeBinary(
            commandBuilder.historicalPathResolution(
                executionDirectory = executionDirectory,
                itemId = itemId,
                changeset = changeset,
                repository = repository,
                server = server,
            ),
            cancellation,
        )

    fun historicalContent(
        executionDirectory: Path,
        historicalPath: String,
        changeset: Long,
        repository: String,
        server: String,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticBinaryResult =
        executeContent(cancellation) { outputFile ->
            commandBuilder.historicalContent(
                executionDirectory = executionDirectory,
                historicalPath = historicalPath,
                changeset = changeset,
                repository = repository,
                server = server,
                outputFile = outputFile,
            )
        }

    fun execute(
        workingDirectory: Path,
        vararg arguments: String,
    ): PlasticTextResult =
        execute(
            PlasticInvocation(
                executable = executable,
                arguments = arguments.toList(),
                workingDirectory = workingDirectory,
                timeout = timeout,
            ),
            PlasticCancellation.NONE,
        )

    private fun execute(
        invocation: PlasticInvocation,
        cancellation: PlasticCancellation,
    ): PlasticTextResult {
        val result = runner.run(invocation, cancellation)

        return PlasticTextResult(
            invocation = invocation,
            exitCode = result.exitCode,
            standardOutput = result.standardOutput.toString(outputCharset),
            standardError = result.standardError.toString(outputCharset),
            duration = result.duration,
            timedOut = result.timedOut,
            standardOutputTruncated = result.standardOutputTruncated,
            standardErrorTruncated = result.standardErrorTruncated,
            standardOutputBytesRead = result.standardOutputBytesRead,
            standardErrorBytesRead = result.standardErrorBytesRead,
            cancelled = result.cancelled,
        )
    }

    /** Returns the bytes `cm` wrote to a temporary file in place of its stdout, which is not byte-exact. */
    private fun executeContent(
        cancellation: PlasticCancellation,
        buildInvocation: (Path) -> PlasticInvocation,
    ): PlasticBinaryResult =
        withTemporaryContentFile { outputFile ->
            val invocation = buildInvocation(outputFile)
            val result = runner.run(invocation, cancellation)

            val content = if (result.exitCode == 0) {
                readBoundedContentFile(outputFile, invocation.standardOutputLimitBytes)
            } else {
                NO_CONTENT
            }

            PlasticBinaryResult(
                invocation = invocation,
                exitCode = result.exitCode,
                standardOutput = content.bytes,
                standardError = result.standardError.toString(outputCharset),
                duration = result.duration,
                timedOut = result.timedOut,
                standardOutputTruncated = result.standardOutputTruncated || content.truncated,
                standardErrorTruncated = result.standardErrorTruncated,
                standardOutputBytesRead = content.totalBytesRead,
                standardErrorBytesRead = result.standardErrorBytesRead,
                cancelled = result.cancelled,
            )
        }

    private fun executeBinary(
        invocation: PlasticInvocation,
        cancellation: PlasticCancellation,
    ): PlasticBinaryResult {
        val result = runner.run(invocation, cancellation)

        return PlasticBinaryResult(
            invocation = invocation,
            exitCode = result.exitCode,
            standardOutput = result.standardOutput,
            standardError = result.standardError.toString(outputCharset),
            duration = result.duration,
            timedOut = result.timedOut,
            standardOutputTruncated = result.standardOutputTruncated,
            standardErrorTruncated = result.standardErrorTruncated,
            standardOutputBytesRead = result.standardOutputBytesRead,
            standardErrorBytesRead = result.standardErrorBytesRead,
            cancelled = result.cancelled,
        )
    }

    private companion object {
        val NO_CONTENT = BoundedOutputCapture(byteArrayOf(), 0L, truncated = false)
    }
}
