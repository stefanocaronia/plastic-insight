package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vcs.VcsException
import com.teamcomplex.plasticinsight.core.PlasticFailure
import com.teamcomplex.plasticinsight.core.PlasticGateway
import com.teamcomplex.plasticinsight.core.PlasticHistoryPage
import com.teamcomplex.plasticinsight.core.PlasticHistoryRequest
import com.teamcomplex.plasticinsight.core.PlasticResult
import com.teamcomplex.plasticinsight.core.PlasticWorkspaceLookup
import com.teamcomplex.plasticinsight.core.history.PlasticHistoryRevision
import com.teamcomplex.plasticinsight.core.process.PlasticCancellation
import com.teamcomplex.plasticinsight.core.workspace.PlasticWorkspace
import java.nio.file.Path

/** Owns the project's gateway and turns its typed failures into Rider exceptions. */
internal class PlasticGatewaySession(
    private val createGateway: () -> PlasticResult<PlasticGateway>,
    private val isProjectDisposed: () -> Boolean,
    private val isDispatchThread: () -> Boolean,
) {
    private val gatewayLock = Any()
    private var gatewayResult: PlasticResult<PlasticGateway>? = null
    private var stopped = false

    fun loadStatus(
        scope: Path,
        cancellation: PlasticCancellation,
        includePrivateFiles: Boolean = false,
    ): PlasticStatusSnapshot? {
        requireBackgroundThread()
        val normalizedScope = scope.toAbsolutePath().normalize()
        val gateway = gateway()
        val lookup = unwrap(gateway.discoverWorkspace(normalizedScope, cancellation), "workspace discovery")

        val workspace = when (lookup) {
            is PlasticWorkspaceLookup.Found -> lookup.workspace
            PlasticWorkspaceLookup.NotFound -> return null
        }

        if (PlasticRootChecker.isAdministrativePath(workspace.root, normalizedScope)) {
            return null
        }

        val status = unwrap(
            gateway.status(workspace, normalizedScope, cancellation, includePrivateFiles),
            "status",
        )
        return PlasticStatusSnapshot(workspace, status)
    }

    fun loadBaseContent(
        snapshot: PlasticStatusSnapshot,
        basePath: Path,
        cancellation: PlasticCancellation,
    ): ByteArray {
        requireBackgroundThread()
        return unwrap(
            gateway().baseContent(
                workspace = snapshot.workspace,
                status = snapshot.status,
                basePath = basePath,
                cancellation = cancellation,
            ),
            "base content",
        )
    }

    fun loadFileHistory(
        filePath: Path,
        cancellation: PlasticCancellation,
        limit: Int = PlasticHistoryRequest.DEFAULT_LIMIT,
    ): PlasticHistoryPage {
        requireBackgroundThread()
        val normalizedPath = filePath.toAbsolutePath().normalize()
        val gateway = gateway()
        val workspace = workspaceForPath(gateway, normalizedPath, cancellation)

        if (PlasticRootChecker.isAdministrativePath(workspace.root, normalizedPath)) {
            throw VcsException("Plastic history is unavailable for workspace metadata.")
        }

        return unwrap(
            gateway.fileHistory(
                workspace = workspace,
                filePath = normalizedPath,
                request = PlasticHistoryRequest(limit),
                cancellation = cancellation,
            ),
            "file history",
        )
    }

    fun loadRevisionContent(
        revision: PlasticHistoryRevision,
        cancellation: PlasticCancellation,
    ): ByteArray {
        requireBackgroundThread()
        return unwrap(gateway().revisionContent(revision, cancellation), "historical content")
    }

    fun addPaths(
        workspaceRoot: Path,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
    ) {
        requireBackgroundThread()
        val gateway = gateway()
        val workspace = workspace(gateway, workspaceRoot, cancellation)
        unwrap(gateway.add(workspace, paths, cancellation), "add")
    }

    fun undoPaths(
        workspaceRoot: Path,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
    ) {
        requireBackgroundThread()
        val gateway = gateway()
        val workspace = workspace(gateway, workspaceRoot, cancellation)
        unwrap(gateway.undo(workspace, paths, cancellation), "rollback")
    }

    fun close() {
        val gateway = synchronized(gatewayLock) {
            if (stopped) {
                null
            } else {
                stopped = true
                (gatewayResult as? PlasticResult.Success)?.value
            }
        }
        gateway?.close()
    }

    private fun requireBackgroundThread() {
        if (isDispatchThread()) {
            throw VcsException("Plastic commands cannot run on Rider's event-dispatch thread.")
        }
    }

    private fun gateway(): PlasticGateway {
        val result = synchronized(gatewayLock) {
            if (stopped || isProjectDisposed()) {
                throw ProcessCanceledException()
            }

            gatewayResult ?: createGateway().also { gatewayResult = it }
        }
        return unwrap(result, "initialization")
    }

    private fun workspace(
        gateway: PlasticGateway,
        root: Path,
        cancellation: PlasticCancellation,
    ): PlasticWorkspace {
        val normalizedRoot = root.toAbsolutePath().normalize()

        return when (
            val lookup = unwrap(
                gateway.discoverWorkspace(normalizedRoot, cancellation),
                "workspace discovery",
            )
        ) {
            is PlasticWorkspaceLookup.Found -> lookup.workspace.also { workspace ->
                if (workspace.root.normalize() != normalizedRoot) {
                    throw VcsException("Plastic returned a different workspace root.")
                }
            }

            PlasticWorkspaceLookup.NotFound -> throw VcsException("The selected path is not in a Plastic workspace.")
        }
    }

    private fun workspaceForPath(
        gateway: PlasticGateway,
        path: Path,
        cancellation: PlasticCancellation,
    ): PlasticWorkspace =
        when (
            val lookup = unwrap(
                gateway.discoverWorkspace(path, cancellation),
                "workspace discovery",
            )
        ) {
            is PlasticWorkspaceLookup.Found -> lookup.workspace
            PlasticWorkspaceLookup.NotFound -> throw VcsException("The selected path is not in a Plastic workspace.")
        }

    private fun <T> unwrap(
        result: PlasticResult<T>,
        operation: String,
    ): T =
        when (result) {
            is PlasticResult.Success -> result.value
            is PlasticResult.Failure -> throw result.reason.toException(operation)
        }

    private fun PlasticFailure.toException(operation: String): Exception =
        when (this) {
            PlasticFailure.Cancelled,
            PlasticFailure.Disposed,
            -> ProcessCanceledException()

            PlasticFailure.ExecutableNotFound ->
                VcsException("Plastic $operation failed because cm.exe was not found.")

            is PlasticFailure.InvalidExecutableConfiguration ->
                VcsException("Plastic $operation failed because the configured cm.exe path is invalid.")

            PlasticFailure.InvalidRuntimeConfiguration ->
                VcsException("Plastic $operation failed because its runtime configuration is invalid.")

            PlasticFailure.TimedOut -> VcsException("Plastic $operation timed out.")

            is PlasticFailure.OutputLimitExceeded -> VcsException("Plastic $operation exceeded its output limit.")

            is PlasticFailure.CommandFailed -> VcsException("Plastic $operation failed with exit code ${exitCode ?: "unknown"}.")

            PlasticFailure.RevisionUnavailable -> VcsException("The requested Plastic revision is unavailable.")

            PlasticFailure.AmbiguousRevision -> VcsException("Plastic returned more than one matching revision.")

            PlasticFailure.MalformedOutput -> VcsException("Plastic $operation returned malformed output.")

            PlasticFailure.LaunchFailed -> VcsException("Plastic $operation could not start cm.exe.")

            PlasticFailure.ExecutionFailed -> VcsException("Plastic $operation could not complete.")
        }
}
