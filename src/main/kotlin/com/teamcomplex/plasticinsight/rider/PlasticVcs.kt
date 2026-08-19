package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.VcsType
import com.intellij.openapi.vcs.actions.StandardVcsGroup
import com.intellij.openapi.vcs.changes.ChangeProvider
import com.intellij.openapi.vcs.checkin.CheckinEnvironment
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vcs.rollback.RollbackEnvironment
import com.teamcomplex.plasticinsight.core.PlasticCancellation
import com.teamcomplex.plasticinsight.core.PlasticFailure
import com.teamcomplex.plasticinsight.core.PlasticGateway
import com.teamcomplex.plasticinsight.core.PlasticGatewayFactory
import com.teamcomplex.plasticinsight.core.PlasticHistoryPage
import com.teamcomplex.plasticinsight.core.PlasticHistoryRequest
import com.teamcomplex.plasticinsight.core.PlasticHistoryRevision
import com.teamcomplex.plasticinsight.core.PlasticResult
import com.teamcomplex.plasticinsight.core.PlasticWorkspace
import com.teamcomplex.plasticinsight.core.PlasticWorkspaceLookup
import com.teamcomplex.plasticinsight.core.PlasticWorkspaceStatus
import java.nio.file.Path

/** Thin Rider boundary over the synchronous, Rider-independent gateway. */
class PlasticVcs(project: Project) : AbstractVcs(project, NAME) {
    private val gatewayLock = Any()
    private var gatewayResult: PlasticResult<PlasticGateway>? = null
    private var stopped = false
    private val changeProvider = PlasticChangeProvider(this)
    private val diffProvider = PlasticDiffProvider(this)
    private val historyProvider = PlasticHistoryProvider(this)

    override fun getDisplayName(): String = DISPLAY_NAME

    override fun getChangeProvider(): ChangeProvider = changeProvider

    override fun getDiffProvider(): DiffProvider = diffProvider

    override fun getVcsHistoryProvider(): VcsHistoryProvider = historyProvider

    override fun createCheckinEnvironment(): CheckinEnvironment = PlasticCheckinEnvironment(this)

    override fun createRollbackEnvironment(): RollbackEnvironment = PlasticRollbackEnvironment(this)

    override fun getType(): VcsType = VcsType.centralized

    override fun isCommitActionDisabled(): Boolean = true

    override fun isUpdateActionDisabled(): Boolean = true

    override fun shutdown() {
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

    internal fun loadStatus(
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
        if (PlasticRootChecker.isAdministrativePath(workspace.root, normalizedScope)) return null

        val status = unwrap(
            gateway.status(workspace, normalizedScope, cancellation, includePrivateFiles),
            "status",
        )
        return PlasticStatusSnapshot(workspace, status)
    }

    internal fun loadBaseContent(
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

    internal fun loadFileHistory(
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

    internal fun loadRevisionContent(
        revision: PlasticHistoryRevision,
        cancellation: PlasticCancellation,
    ): ByteArray {
        requireBackgroundThread()
        return unwrap(gateway().revisionContent(revision, cancellation), "historical content")
    }

    internal fun addPaths(
        workspaceRoot: Path,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
    ) {
        requireBackgroundThread()
        val gateway = gateway()
        val workspace = workspace(gateway, workspaceRoot, cancellation)
        unwrap(gateway.add(workspace, paths, cancellation), "add")
    }

    internal fun undoPaths(
        workspaceRoot: Path,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
    ) {
        requireBackgroundThread()
        val gateway = gateway()
        val workspace = workspace(gateway, workspaceRoot, cancellation)
        unwrap(gateway.undo(workspace, paths, cancellation), "rollback")
    }

    internal fun currentCancellation(): PlasticCancellation {
        val indicator = ProgressManager.getInstanceOrNull()?.progressIndicator
        return PlasticCancellation { project.isDisposed || indicator?.isCanceled == true }
    }

    internal fun isDispatchThread(): Boolean =
        ApplicationManager.getApplication()?.isDispatchThread == true

    private fun requireBackgroundThread() {
        if (isDispatchThread()) {
            throw VcsException("Plastic commands cannot run on Rider's event-dispatch thread.")
        }
    }

    private fun gateway(): PlasticGateway {
        val result = synchronized(gatewayLock) {
            if (stopped || project.isDisposed) throw ProcessCanceledException()
            gatewayResult ?: PlasticGatewayFactory().create().also { gatewayResult = it }
        }
        return unwrap(result, "initialization")
    }

    private fun workspace(
        gateway: PlasticGateway,
        root: Path,
        cancellation: PlasticCancellation,
    ): PlasticWorkspace {
        val normalizedRoot = root.toAbsolutePath().normalize()
        return when (val lookup = unwrap(
            gateway.discoverWorkspace(normalizedRoot, cancellation),
            "workspace discovery",
        )) {
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
        when (val lookup = unwrap(
            gateway.discoverWorkspace(path, cancellation),
            "workspace discovery",
        )) {
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

    companion object {
        const val NAME: String = "Plastic Insight"
        const val DISPLAY_NAME: String = "Plastic Insight"
        val KEY: VcsKey = VcsKey(NAME)
    }
}

internal data class PlasticStatusSnapshot(
    val workspace: PlasticWorkspace,
    val status: PlasticWorkspaceStatus,
)

/** Supplies the standard Plastic Insight submenu in Rider VCS context menus. */
class PlasticVcsMenu : StandardVcsGroup() {
    override fun getVcs(project: Project): AbstractVcs? =
        ProjectLevelVcsManager.getInstance(project).findVcsByName(PlasticVcs.NAME)
}
