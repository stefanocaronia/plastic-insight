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

/** Translates bounded dirty scopes into native Rider changes. */
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
        val explicitCandidates = dirtyScope.dirtyFilesNoExpand
            .asSequence()
            .filterNot(FilePath::isDirectory)
            .filterNot(FilePath::isNonLocal)
            .mapNotNull(::localPath)
            .toList()
        val recursiveCandidates = dirtyScope.recursivelyDirtyDirectories
            .asSequence()
            .filterNot(FilePath::isNonLocal)
            .mapNotNull(::localPath)
            .toList()
        val contentRoots = dirtyScope.affectedContentRoots
            .asSequence()
            .filter { file -> file.isValid && file.isDirectory }
            .map { file -> VcsUtil.getFilePath(file) }
            .filterNot(FilePath::isNonLocal)
            .mapNotNull(::localPath)
            .toList()
        val reported = HashSet<String>()

        val scopes = planStatusScopes(
            explicitPaths = explicitCandidates,
            recursivePaths = recursiveCandidates,
            contentRoots = contentRoots,
            everythingDirty = dirtyScope.wasEveryThingDirty(),
        )
        for (scope in scopes) {
            progress.checkCanceled()
            val snapshot = vcs.loadStatus(scope.path, cancellation) ?: continue
            for (change in snapshot.status.changes) {
                if (!scope.contains(change) || change.isDirectory || !reported.add(change.identity())) continue
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

internal data class PlasticStatusScope(
    val path: Path,
    val recursive: Boolean,
) {
    fun contains(change: PlasticPendingChange): Boolean =
        if (recursive) {
            change.path.normalize().startsWith(path) || change.oldPath?.normalize()?.startsWith(path) == true
        } else {
            change.matches(path)
        }
}

internal fun planStatusScopes(
    explicitPaths: Iterable<Path>,
    recursivePaths: Iterable<Path> = emptyList(),
    contentRoots: Iterable<Path> = emptyList(),
    everythingDirty: Boolean = false,
): List<PlasticStatusScope> {
    val exact = normalizedPaths(explicitPaths)
    val recursive = normalizedPaths(recursivePaths)
    val roots = normalizedPaths(contentRoots)
    val broadRefresh = everythingDirty ||
        recursive.size > MAX_EXACT_STATUS_SCOPES ||
        exact.size > MAX_EXACT_STATUS_SCOPES
    val recursiveCandidates = if (broadRefresh && roots.isNotEmpty()) recursive + roots else recursive
    val dominantRecursive = recursiveCandidates
        .sortedWith(compareBy<Path>({ it.nameCount }, ::pathSortKey))
        .fold(mutableListOf<Path>()) { selected, candidate ->
            if (selected.none(candidate::startsWith)) selected.add(candidate)
            selected
        }

    val scopes = ArrayList<PlasticStatusScope>(dominantRecursive.size + exact.size)
    dominantRecursive.forEach { path -> scopes.add(PlasticStatusScope(path, recursive = true)) }
    exact
        .filter { path -> dominantRecursive.none(path::startsWith) }
        .forEach { path -> scopes.add(PlasticStatusScope(path, recursive = false)) }
    return scopes.sortedBy { scope -> pathSortKey(scope.path) }
}

private fun normalizedPaths(paths: Iterable<Path>): List<Path> =
    paths.asSequence()
        .filter(Path::isAbsolute)
        .map(Path::normalize)
        .distinct()
        .sortedBy(::pathSortKey)
        .toList()

private fun pathSortKey(path: Path): String = path.toString().lowercase(Locale.ROOT)

private const val MAX_EXACT_STATUS_SCOPES = 4

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
