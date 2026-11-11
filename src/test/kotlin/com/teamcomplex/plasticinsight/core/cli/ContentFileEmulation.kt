package com.teamcomplex.plasticinsight.core.cli

import com.teamcomplex.plasticinsight.core.process.PlasticInvocation
import com.teamcomplex.plasticinsight.core.process.PlasticProcessResult
import java.nio.file.Files
import java.nio.file.Path

/** Stands in for `cm cat --file`: a successful fake result leaves its bytes in the requested file, not on stdout. */
internal fun PlasticProcessResult.deliveredAsContentFile(invocation: PlasticInvocation): PlasticProcessResult {
    val contentFile = invocation.contentFile()

    if (contentFile == null || exitCode != 0) {
        return this
    }

    Files.write(contentFile, standardOutput)
    return copy(standardOutput = byteArrayOf(), standardOutputBytesRead = 0)
}

internal fun PlasticInvocation.contentFile(): Path? =
    arguments
        .firstOrNull { argument -> argument.startsWith(CONTENT_FILE_OPTION) }
        ?.let { argument -> Path.of(argument.removePrefix(CONTENT_FILE_OPTION)) }

/** The command line without the per-call temporary file, which no test can predict. */
internal fun PlasticInvocation.commandLineWithoutContentFile(): List<String> =
    commandLine().filterNot { argument -> argument.startsWith(CONTENT_FILE_OPTION) }

private const val CONTENT_FILE_OPTION = "--file="
