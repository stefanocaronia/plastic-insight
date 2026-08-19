package com.teamcomplex.plasticinsight.core

import java.nio.file.Path
import java.time.Duration

/** Builds shell-free, one-shot Plastic CLI invocations without touching the filesystem. */
class PlasticCommandBuilder(
    private val executable: String = "cm",
    private val timeout: Duration = Duration.ofSeconds(15),
    private val textOutputLimitBytes: Int = DEFAULT_TEXT_OUTPUT_LIMIT_BYTES,
    private val binaryOutputLimitBytes: Int = DEFAULT_BINARY_OUTPUT_LIMIT_BYTES,
    private val errorOutputLimitBytes: Int = DEFAULT_ERROR_OUTPUT_LIMIT_BYTES,
) {
    init {
        require(textOutputLimitBytes > 0) { "The text output limit must be positive." }
        require(binaryOutputLimitBytes > 0) { "The binary output limit must be positive." }
        require(errorOutputLimitBytes > 0) { "The error output limit must be positive." }
    }

    fun workspaceDiscovery(
        workingDirectory: Path,
        targetPath: Path,
    ): PlasticInvocation {
        require(workingDirectory.isAbsolute) { "The Plastic working directory must be absolute." }
        require(targetPath.isAbsolute) { "The workspace discovery target must be absolute." }

        return PlasticInvocation(
            executable = executable,
            arguments = listOf(
                "getworkspacefrompath",
                targetPath.normalize().toString(),
                "--extended",
                "--format=$WORKSPACE_DISCOVERY_FORMAT",
            ),
            workingDirectory = workingDirectory.normalize(),
            timeout = timeout,
            standardOutputLimitBytes = DISCOVERY_OUTPUT_LIMIT_BYTES,
            standardErrorLimitBytes = errorOutputLimitBytes,
        )
    }

    fun constrainedStatus(
        workspaceRoot: Path,
        scope: Path,
        includePrivateFiles: Boolean = false,
    ): PlasticInvocation {
        require(workspaceRoot.isAbsolute) { "The Plastic workspace root must be absolute." }
        require(scope.isAbsolute) { "The Plastic status scope must be absolute." }

        val normalizedRoot = workspaceRoot.normalize()
        val normalizedScope = scope.normalize()
        require(normalizedScope != normalizedRoot && normalizedScope.startsWith(normalizedRoot)) {
            "The Plastic status scope must be strictly inside the workspace root."
        }

        return statusInvocation(
            workspaceRoot = normalizedRoot,
            scope = normalizedScope,
            outputLimitBytes = textOutputLimitBytes,
            includePrivateFiles = includePrivateFiles,
        )
    }

    /** Filtered, output-bounded root status for Rider's broad refresh. */
    fun workspaceStatus(
        workspaceRoot: Path,
        includePrivateFiles: Boolean = false,
    ): PlasticInvocation {
        require(workspaceRoot.isAbsolute) { "The Plastic workspace root must be absolute." }
        val normalizedRoot = workspaceRoot.normalize()
        return statusInvocation(
            workspaceRoot = normalizedRoot,
            scope = normalizedRoot,
            outputLimitBytes = minOf(textOutputLimitBytes, WORKSPACE_STATUS_OUTPUT_LIMIT_BYTES),
            includePrivateFiles = includePrivateFiles,
        )
    }

    private fun statusInvocation(
        workspaceRoot: Path,
        scope: Path,
        outputLimitBytes: Int,
        includePrivateFiles: Boolean,
    ): PlasticInvocation =
        PlasticInvocation(
            executable = executable,
            arguments = buildList {
                add("status")
                add(scope.toString())
                add("--machinereadable")
                add("--includeRevId")
                add("--iscochanged")
                add("--controlledchanged")
                add("--changed")
                add("--localdeleted")
                add("--localmoved")
                if (includePrivateFiles) {
                    add("--private")
                    add("--ignored")
                    add("--cutignored")
                }
                add("--fieldseparator=$UNIT_SEPARATOR")
                add("--startlineseparator=$RECORD_SEPARATOR")
                add("--endlineseparator=$GROUP_SEPARATOR")
            },
            workingDirectory = workspaceRoot,
            timeout = timeout,
            standardOutputLimitBytes = outputLimitBytes,
            standardErrorLimitBytes = errorOutputLimitBytes,
        )

    fun workspaceBaseline(
        workspaceRoot: Path,
        basePath: Path,
        workspaceChangeset: Long,
    ): PlasticInvocation {
        val (normalizedRoot, normalizedPath) = normalizedWorkspacePath(
            workspaceRoot = workspaceRoot,
            path = basePath,
            pathDescription = "workspace baseline path",
        )
        require(workspaceChangeset >= 0) { "The workspace changeset must not be negative." }

        return PlasticInvocation(
            executable = executable,
            arguments = listOf(
                "cat",
                "$normalizedPath#cs:$workspaceChangeset",
                "--raw",
            ),
            workingDirectory = normalizedRoot,
            timeout = timeout,
            standardOutputLimitBytes = binaryOutputLimitBytes,
            standardErrorLimitBytes = errorOutputLimitBytes,
        )
    }

    fun fileHistory(
        workspaceRoot: Path,
        filePath: Path,
        limit: Int,
    ): PlasticInvocation {
        val (normalizedRoot, normalizedPath) = normalizedWorkspacePath(
            workspaceRoot = workspaceRoot,
            path = filePath,
            pathDescription = "history path",
        )
        require(limit in 1..MAX_HISTORY_LIMIT) {
            "The history limit must be between 1 and $MAX_HISTORY_LIMIT."
        }

        return PlasticInvocation(
            executable = executable,
            arguments = listOf(
                "history",
                normalizedPath.toString(),
                "--xml",
                "--encoding=utf-8",
                "--moveddeleted",
                "--limit=$limit",
            ),
            workingDirectory = normalizedRoot,
            timeout = timeout,
            standardOutputLimitBytes = textOutputLimitBytes,
            standardErrorLimitBytes = errorOutputLimitBytes,
        )
    }

    /** The execution directory must be outside every Plastic workspace. */
    fun historicalPathResolution(
        executionDirectory: Path,
        itemId: Long,
        changeset: Long,
        repository: String,
        server: String,
    ): PlasticInvocation {
        require(executionDirectory.isAbsolute) { "The historical lookup directory must be absolute." }
        require(itemId in 1..Int.MAX_VALUE.toLong()) { "The Plastic item ID must fit the server query range." }
        require(changeset >= 0) { "The historical changeset must not be negative." }
        val repositorySpec = validatedRepositorySpec(repository, server)
        val query = "where itemid=$itemId and changeset=$changeset on repository '$repositorySpec'"

        return PlasticInvocation(
            executable = executable,
            arguments = listOf(
                "find",
                "revision",
                query,
                "--xml",
                "--encoding=utf-8",
                "--nototal",
            ),
            workingDirectory = executionDirectory.normalize(),
            timeout = timeout,
            standardOutputLimitBytes = textOutputLimitBytes,
            standardErrorLimitBytes = errorOutputLimitBytes,
        )
    }

    /** The execution directory must be outside every Plastic workspace. */
    fun historicalContent(
        executionDirectory: Path,
        historicalPath: String,
        changeset: Long,
        repository: String,
        server: String,
    ): PlasticInvocation {
        require(executionDirectory.isAbsolute) { "The historical content directory must be absolute." }
        require(historicalPath.isNotBlank()) { "The historical server path must not be blank." }
        require(historicalPath.none { it == '\u0000' || it == '\r' || it == '\n' || it == '#' }) {
            "The historical server path contains an unsupported character."
        }
        require(changeset >= 0) { "The historical changeset must not be negative." }
        val repositorySpec = validatedRepositorySpec(repository, server)
        val revisionSpec = "serverpath:$historicalPath#cs:$changeset@$repositorySpec"

        return PlasticInvocation(
            executable = executable,
            arguments = listOf("cat", revisionSpec, "--raw"),
            workingDirectory = executionDirectory.normalize(),
            timeout = timeout,
            standardOutputLimitBytes = binaryOutputLimitBytes,
            standardErrorLimitBytes = errorOutputLimitBytes,
        )
    }

    private fun normalizedWorkspacePath(
        workspaceRoot: Path,
        path: Path,
        pathDescription: String,
    ): Pair<Path, Path> {
        require(workspaceRoot.isAbsolute) { "The Plastic workspace root must be absolute." }
        require(path.isAbsolute) { "The Plastic $pathDescription must be absolute." }

        val normalizedRoot = workspaceRoot.normalize()
        val normalizedPath = path.normalize()
        require(normalizedPath.startsWith(normalizedRoot)) {
            "The Plastic $pathDescription must be inside the workspace root."
        }
        return normalizedRoot to normalizedPath
    }

    private fun validatedRepositorySpec(repository: String, server: String): String {
        require(repository.isNotBlank()) { "The Plastic repository must not be blank." }
        require(server.isNotBlank()) { "The Plastic server must not be blank." }
        require((repository + server).none { it == '\u0000' || it == '\r' || it == '\n' || it == '\'' }) {
            "The Plastic repository or server contains an unsupported character."
        }
        return "$repository@$server"
    }

    private companion object {
        const val MAX_HISTORY_LIMIT = 1000
        const val DISCOVERY_OUTPUT_LIMIT_BYTES = 64 * 1024
        const val WORKSPACE_STATUS_OUTPUT_LIMIT_BYTES = 1024 * 1024
        const val DEFAULT_TEXT_OUTPUT_LIMIT_BYTES = 8 * 1024 * 1024
        const val DEFAULT_BINARY_OUTPUT_LIMIT_BYTES = 32 * 1024 * 1024
        const val DEFAULT_ERROR_OUTPUT_LIMIT_BYTES = 256 * 1024
        const val UNIT_SEPARATOR = '\u001F'
        const val RECORD_SEPARATOR = '\u001E'
        const val GROUP_SEPARATOR = '\u001D'
        const val WORKSPACE_DISCOVERY_FORMAT =
            "{wkname}$UNIT_SEPARATOR{wkpath}$UNIT_SEPARATOR{machine}$UNIT_SEPARATOR{guid}$UNIT_SEPARATOR{type}$UNIT_SEPARATOR{dynamic}"
    }
}
