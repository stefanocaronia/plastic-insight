package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.checkin.CheckinEnvironment
import com.intellij.openapi.vcs.rollback.RollbackEnvironment
import com.intellij.openapi.vcs.rollback.RollbackProgressListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.LinkedHashMap

/** Enables only the small workspace actions that Plastic Insight implements explicitly. */
internal class PlasticCheckinEnvironment(
    private val vcs: PlasticVcs,
) : CheckinEnvironment {
    override fun getHelpId(): String? = null

    override fun getCheckinOperationName(): String = "Check In"

    override fun scheduleUnversionedFilesForAddition(files: List<VirtualFile>): List<VcsException> =
        runWorkspaceOperation(vcs, files.map(VcsUtil::getFilePath), vcs::addPaths).errors

    override fun scheduleMissingFileForDeletion(files: List<FilePath>): List<VcsException> =
        emptyList() // Plastic reports a missing controlled item as a local deletion without a command.

    override fun isRefreshAfterCommitNeeded(): Boolean = false
}

internal class PlasticRollbackEnvironment(
    private val vcs: PlasticVcs,
) : RollbackEnvironment {
    override fun getRollbackOperationName(): String = "Rollback"

    override fun rollbackChanges(
        changes: List<Change>,
        exceptions: MutableList<VcsException>,
        listener: RollbackProgressListener,
    ) {
        listener.determinate()
        val paths = changes.mapNotNull(::rollbackPath)
        val result = runWorkspaceOperation(vcs, paths, vcs::undoPaths, listener::checkCanceled)
        exceptions.addAll(result.errors)
        changes.forEach { change ->
            val path = rollbackPath(change)?.toLocalPath()
            if (path != null && path in result.succeededPaths) listener.accept(change)
        }
    }

    override fun rollbackMissingFileDeletion(
        files: List<FilePath>,
        exceptions: MutableList<in VcsException>,
        listener: RollbackProgressListener,
    ) {
        listener.determinate()
        val result = runWorkspaceOperation(vcs, files, vcs::undoPaths, listener::checkCanceled)
        exceptions.addAll(result.errors)
        files.filter { file -> file.toLocalPath() in result.succeededPaths }.forEach(listener::accept)
    }

    override fun rollbackModifiedWithoutCheckout(
        files: List<VirtualFile>,
        exceptions: MutableList<in VcsException>,
        listener: RollbackProgressListener,
    ) {
        listener.determinate()
        val paths = files.map(VcsUtil::getFilePath)
        val result = runWorkspaceOperation(vcs, paths, vcs::undoPaths, listener::checkCanceled)
        exceptions.addAll(result.errors)
        files.filter { file -> VcsUtil.getFilePath(file).toLocalPath() in result.succeededPaths }
            .forEach(listener::accept)
    }
}

private fun rollbackPath(change: Change): FilePath? =
    change.afterRevision?.file ?: change.beforeRevision?.file

private data class WorkspaceOperationResult(
    val succeededPaths: Set<Path>,
    val errors: List<VcsException>,
)

private fun runWorkspaceOperation(
    vcs: PlasticVcs,
    files: List<FilePath>,
    operation: (Path, Collection<Path>, com.teamcomplex.plasticinsight.core.PlasticCancellation) -> Unit,
    checkCanceled: () -> Unit = {},
): WorkspaceOperationResult {
    val manager = ProjectLevelVcsManager.getInstance(vcs.project)
    val grouped = LinkedHashMap<Path, MutableList<Path>>()
    val errors = ArrayList<VcsException>()

    files.distinctBy(FilePath::getPath).forEach { file ->
        val path = file.toLocalPath()
        val root = manager.getVcsRootFor(file)?.path?.toLocalPath()
        if (path == null || root == null) {
            errors.add(VcsException("Plastic workspace operation requires a local mapped path."))
        } else {
            grouped.getOrPut(root) { ArrayList() }.add(path)
        }
    }

    val succeeded = HashSet<Path>()
    val cancellation = vcs.currentCancellation()
    for ((root, paths) in grouped) {
        checkCanceled()
        try {
            operation(root, paths, cancellation)
            succeeded.addAll(paths)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: VcsException) {
            errors.add(failure)
        }
    }
    return WorkspaceOperationResult(succeeded, errors)
}

private fun FilePath.toLocalPath(): Path? =
    if (isNonLocal) null else path.toLocalPath()

private fun String.toLocalPath(): Path? =
    try {
        Path.of(this).takeIf(Path::isAbsolute)?.normalize()
    } catch (_: InvalidPathException) {
        null
    }
