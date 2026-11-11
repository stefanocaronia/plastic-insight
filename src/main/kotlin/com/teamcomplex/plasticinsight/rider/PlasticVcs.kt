package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.VcsType
import com.intellij.openapi.vcs.actions.StandardVcsGroup
import com.intellij.openapi.vcs.changes.ChangeProvider
import com.intellij.openapi.vcs.checkin.CheckinEnvironment
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vcs.rollback.RollbackEnvironment
import com.teamcomplex.plasticinsight.core.PlasticGatewayFactory
import com.teamcomplex.plasticinsight.core.PlasticHistoryPage
import com.teamcomplex.plasticinsight.core.PlasticHistoryRequest
import com.teamcomplex.plasticinsight.core.history.PlasticHistoryRevision
import com.teamcomplex.plasticinsight.core.process.PlasticCancellation
import com.teamcomplex.plasticinsight.core.status.PlasticWorkspaceStatus
import com.teamcomplex.plasticinsight.core.workspace.PlasticWorkspace
import java.nio.file.Path

/** Thin Rider boundary over the synchronous, Rider-independent gateway. */
class PlasticVcs(
    project: Project,
) : AbstractVcs(project, NAME) {
    private val session = PlasticGatewaySession(
        createGateway = { PlasticGatewayFactory().create() },
        isProjectDisposed = { project.isDisposed },
        isDispatchThread = ::isDispatchThread,
    )
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
        session.close()
    }

    internal fun loadStatus(
        scope: Path,
        cancellation: PlasticCancellation,
        includePrivateFiles: Boolean = false,
    ): PlasticStatusSnapshot? = session.loadStatus(scope, cancellation, includePrivateFiles)

    internal fun loadBaseContent(
        snapshot: PlasticStatusSnapshot,
        basePath: Path,
        cancellation: PlasticCancellation,
    ): ByteArray = session.loadBaseContent(snapshot, basePath, cancellation)

    internal fun loadFileHistory(
        filePath: Path,
        cancellation: PlasticCancellation,
        limit: Int = PlasticHistoryRequest.DEFAULT_LIMIT,
    ): PlasticHistoryPage = session.loadFileHistory(filePath, cancellation, limit)

    internal fun loadRevisionContent(
        revision: PlasticHistoryRevision,
        cancellation: PlasticCancellation,
    ): ByteArray = session.loadRevisionContent(revision, cancellation)

    internal fun addPaths(
        workspaceRoot: Path,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
    ) = session.addPaths(workspaceRoot, paths, cancellation)

    internal fun undoPaths(
        workspaceRoot: Path,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
    ) = session.undoPaths(workspaceRoot, paths, cancellation)

    internal fun currentCancellation(): PlasticCancellation {
        val indicator = ProgressManager.getInstance().progressIndicator
        return PlasticCancellation { project.isDisposed || indicator?.isCanceled == true }
    }

    internal fun isDispatchThread(): Boolean = ApplicationManager.getApplication()?.isDispatchThread == true

    companion object {
        const val NAME: String = "Plastic Insight"
        const val DISPLAY_NAME: String = "Plastic Insight"
        val KEY: VcsKey = createKey(NAME)
    }
}

internal data class PlasticStatusSnapshot(
    val workspace: PlasticWorkspace,
    val status: PlasticWorkspaceStatus,
)

/** Supplies the standard Plastic Insight submenu in Rider VCS context menus. */
class PlasticVcsMenu : StandardVcsGroup() {
    override fun getVcs(project: Project): AbstractVcs? = ProjectLevelVcsManager.getInstance(project).findVcsByName(PlasticVcs.NAME)
}
