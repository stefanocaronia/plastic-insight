package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManagerGate
import com.intellij.openapi.vcs.changes.ChangeProvider
import com.intellij.openapi.vcs.changes.ChangelistBuilder
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.VcsDirtyScope
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.vcsUtil.VcsUtil
import com.teamcomplex.plasticinsight.core.PlasticCancellation
import com.teamcomplex.plasticinsight.core.PlasticPendingChange
import com.teamcomplex.plasticinsight.core.PlasticStatusCode
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

/** Translates exact dirty files into native Rider changes. */
internal class PlasticChangeProvider(
    private val vcs: PlasticVcs,
) : ChangeProvider {
    override fun getChanges(
        dirtyScope: VcsDirtyScope,
        builder: ChangelistBuilder,
        progress: ProgressIndicator,
        changeListManagerGate: ChangeListManagerGate,
    ) {
        ApplicationManager.getApplication().assertIsNonDispatchThread()
        val cancellation = PlasticCancellation { progress.isCanceled || vcs.project.isDisposed }
        val candidates = dirtyScope.dirtyFilesNoExpand
            .asSequence()
            .filterNot(FilePath::isDirectory)
            .filterNot(FilePath::isNonLocal)
            .mapNotNull(::localPath)
            .toList()
        val reported = HashSet<String>()

        for (scope in planDirtyPaths(candidates)) {
            progress.checkCanceled()
            val snapshot = vcs.loadStatus(scope, cancellation) ?: continue
            for (change in snapshot.status.changes) {
                if (!change.matches(scope) || change.isDirectory || !reported.add(change.identity())) continue
                progress.checkCanceled()
                toRiderChange(vcs, snapshot, change)?.let { builder.processChange(it, PlasticVcs.KEY) }
            }
        }
    }

    override fun isModifiedDocumentTrackingRequired(): Boolean = true

    private fun localPath(file: FilePath): Path? =
        try {
            Path.of(file.path).takeIf(Path::isAbsolute)?.normalize()
        } catch (_: InvalidPathException) {
            null
        }
}

internal enum class PlasticRiderChangeKind {
    MODIFIED,
    ADDED,
    DELETED,
    MOVED,
}

internal fun PlasticPendingChange.riderKind(): PlasticRiderChangeKind? =
    when {
        isDirectory -> null
        PlasticStatusCode.ADDED in codes -> PlasticRiderChangeKind.ADDED
        isMove -> PlasticRiderChangeKind.MOVED
        PlasticStatusCode.DELETED in codes || PlasticStatusCode.LOCALLY_DELETED in codes ->
            PlasticRiderChangeKind.DELETED

        PlasticStatusCode.CHANGED in codes || PlasticStatusCode.REPLACED in codes ->
            PlasticRiderChangeKind.MODIFIED

        else -> null
    }

internal fun planDirtyPaths(paths: Iterable<Path>): List<Path> =
    paths.asSequence()
        .filter(Path::isAbsolute)
        .map(Path::normalize)
        .distinct()
        .sortedBy { path -> path.toString().lowercase(Locale.ROOT) }
        .toList()

internal fun PlasticPendingChange.matches(scope: Path): Boolean {
    val normalizedScope = scope.normalize()
    return path.normalize() == normalizedScope || oldPath?.normalize() == normalizedScope
}

private fun PlasticPendingChange.identity(): String =
    "${oldPath?.normalize()?.toString().orEmpty()}\u0000${path.normalize()}"

private fun toRiderChange(
    vcs: PlasticVcs,
    snapshot: PlasticStatusSnapshot,
    change: PlasticPendingChange,
): Change? {
    val kind = change.riderKind() ?: return null
    val revisionNumber = VcsRevisionNumber.Long(snapshot.status.workspaceChangeset)
    val currentFile = VcsUtil.getFilePath(change.path, false)
    val before = when (kind) {
        PlasticRiderChangeKind.ADDED -> null
        PlasticRiderChangeKind.MOVED -> {
            val oldPath = requireNotNull(change.oldPath)
            PlasticBaseContentRevision(
                vcs = vcs,
                snapshot = snapshot,
                file = VcsUtil.getFilePath(oldPath, false),
                basePath = oldPath,
                revisionNumber = revisionNumber,
            )
        }

        PlasticRiderChangeKind.MODIFIED,
        PlasticRiderChangeKind.DELETED,
        -> PlasticBaseContentRevision(vcs, snapshot, currentFile, change.path, revisionNumber)
    }
    val after = when (kind) {
        PlasticRiderChangeKind.DELETED -> null
        else -> CurrentContentRevision.create(currentFile)
    }
    return Change(before, after)
}
