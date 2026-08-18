package com.teamcomplex.plasticinsight.core

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

    fun version(workingDirectory: Path): PlasticTextResult =
        execute(workingDirectory, "version")

    fun constrainedStatus(
        workspaceRoot: Path,
        scope: Path,
    ): PlasticTextResult =
        execute(commandBuilder.constrainedStatus(workspaceRoot, scope))

    fun workspaceFromPath(
        workingDirectory: Path,
        targetPath: Path,
    ): PlasticTextResult =
        execute(commandBuilder.workspaceDiscovery(workingDirectory, targetPath))

    fun workspaceBaseline(
        workspaceRoot: Path,
        basePath: Path,
        workspaceChangeset: Long,
    ): PlasticBinaryResult =
        executeBinary(commandBuilder.workspaceBaseline(workspaceRoot, basePath, workspaceChangeset))

    fun fileHistory(
        workspaceRoot: Path,
        filePath: Path,
        limit: Int,
    ): PlasticTextResult =
        execute(commandBuilder.fileHistory(workspaceRoot, filePath, limit))

    fun historicalPathResolution(
        executionDirectory: Path,
        itemId: Long,
        changeset: Long,
        repository: String,
        server: String,
    ): PlasticTextResult =
        execute(
            commandBuilder.historicalPathResolution(
                executionDirectory = executionDirectory,
                itemId = itemId,
                changeset = changeset,
                repository = repository,
                server = server,
            ),
        )

    fun historicalContent(
        executionDirectory: Path,
        historicalPath: String,
        changeset: Long,
        repository: String,
        server: String,
    ): PlasticBinaryResult =
        executeBinary(
            commandBuilder.historicalContent(
                executionDirectory = executionDirectory,
                historicalPath = historicalPath,
                changeset = changeset,
                repository = repository,
                server = server,
            ),
        )

    fun execute(workingDirectory: Path, vararg arguments: String): PlasticTextResult =
        execute(
            PlasticInvocation(
                executable = executable,
                arguments = arguments.toList(),
                workingDirectory = workingDirectory,
                timeout = timeout,
            ),
        )

    private fun execute(invocation: PlasticInvocation): PlasticTextResult {
        val result = runner.run(invocation)

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
        )
    }

    private fun executeBinary(invocation: PlasticInvocation): PlasticBinaryResult {
        val result = runner.run(invocation)

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
        )
    }
}
