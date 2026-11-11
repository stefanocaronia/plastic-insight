package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.diff.ItemLatestState
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import com.teamcomplex.plasticinsight.core.status.PlasticStatusCode
import com.teamcomplex.plasticinsight.core.status.PlasticWorkspaceStatus
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Supplies the workspace baseline and lets Rider render its standard gutter. */
internal class PlasticDiffProvider(
    private val vcs: PlasticVcs,
) : DiffProvider {
    override fun getCurrentRevision(file: VirtualFile): VcsRevisionNumber? =
        safely {
            if (file.isDirectory || vcs.isDispatchThread()) {
                return@safely null
            }

            val path = file.localPath()
            val snapshot = vcs.loadStatus(path, vcs.currentCancellation(), includePrivateFiles = true)
                ?: return@safely null
            val changeset = snapshot.status.controlledChangeset(path) ?: return@safely null
            VcsRevisionNumber.Long(changeset)
        }

    override fun getLastRevision(file: VirtualFile): ItemLatestState? = null

    override fun getLastRevision(file: FilePath): ItemLatestState? = null

    override fun createFileContent(
        revisionNumber: VcsRevisionNumber,
        selectedFile: VirtualFile,
    ): ContentRevision? = safely { createBaselineContent(selectedFile, revisionNumber) }

    override fun createCurrentFileContent(file: VirtualFile): ContentRevision? =
        safely { createBaselineContent(file, expectedRevision = null) }

    override fun getLatestCommittedRevision(file: VirtualFile): VcsRevisionNumber? = null

    private fun createBaselineContent(
        selectedFile: VirtualFile,
        expectedRevision: VcsRevisionNumber?,
    ): ContentRevision? {
        if (selectedFile.isDirectory || vcs.isDispatchThread()) {
            return null
        }

        val selectedPath = selectedFile.localPath()
        val snapshot = vcs.loadStatus(selectedPath, vcs.currentCancellation(), includePrivateFiles = true) ?: return null
        val currentNumber = VcsRevisionNumber.Long(snapshot.status.workspaceChangeset)

        if (expectedRevision != null && expectedRevision != currentNumber) {
            return null
        }

        val basePath = snapshot.status.baselinePath(selectedPath) ?: return null
        return PlasticBaseContentRevision(
            vcs = vcs,
            snapshot = snapshot,
            file = VcsUtil.getFilePath(basePath, false),
            basePath = basePath,
            revisionNumber = currentNumber,
        )
    }

    private inline fun <T> safely(action: () -> T?): T? =
        try {
            action()
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failure: VcsException) {
            LOG.debug(failure.message)
            null
        } catch (_: InvalidPathException) {
            null
        }

    private fun VirtualFile.localPath(): Path = Path.of(path).toAbsolutePath().normalize()

    private companion object {
        val LOG: Logger = Logger.getInstance(PlasticDiffProvider::class.java)
    }
}

/** The workspace changeset when the file has a controlled revision; private and ignored files have none. */
internal fun PlasticWorkspaceStatus.controlledChangeset(path: Path): Long? {
    if (changes.any { change -> change.matches(path) && change.codes.any(::hasNoControlledRevision) }) {
        return null
    }

    return workspaceChangeset
}

/** Where a file's baseline lives: its old path for a move, nowhere for added, private or ignored files. */
internal fun PlasticWorkspaceStatus.baselinePath(path: Path): Path? {
    val pendingChange = changes.firstOrNull { change -> change.matches(path) }

    if (pendingChange?.codes?.any { code ->
            code == PlasticStatusCode.ADDED || hasNoControlledRevision(code)
        } == true
    ) {
        return null
    }

    return pendingChange?.oldPath ?: path
}

private fun hasNoControlledRevision(code: PlasticStatusCode): Boolean =
    code == PlasticStatusCode.PRIVATE || code == PlasticStatusCode.IGNORED

internal class PlasticBaseContentRevision(
    private val vcs: PlasticVcs,
    private val snapshot: PlasticStatusSnapshot,
    private val file: FilePath,
    private val basePath: Path,
    private val revisionNumber: VcsRevisionNumber,
) : ByteBackedContentRevision {
    override fun getContentAsBytes(): ByteArray = vcs.loadBaseContent(snapshot, basePath, vcs.currentCancellation())

    override fun getContent(): String = String(getContentAsBytes(), file.getCharset(vcs.project)).removePrefix("\uFEFF")

    override fun getFile(): FilePath = file

    override fun getRevisionNumber(): VcsRevisionNumber = revisionNumber
}
