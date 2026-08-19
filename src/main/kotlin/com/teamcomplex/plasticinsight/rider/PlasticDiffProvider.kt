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
import com.intellij.openapi.vfs.CharsetToolkit
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import com.teamcomplex.plasticinsight.core.PlasticStatusCode
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Supplies the workspace baseline and lets Rider render its standard gutter. */
internal class PlasticDiffProvider(
    private val vcs: PlasticVcs,
) : DiffProvider {
    override fun getCurrentRevision(file: VirtualFile): VcsRevisionNumber? =
        safely {
            if (file.isDirectory || vcs.isDispatchThread()) return@safely null
            val path = file.localPath()
            val snapshot = vcs.loadStatus(path, vcs.currentCancellation(), includePrivateFiles = true)
                ?: return@safely null
            if (snapshot.status.changes.any { change ->
                    change.matches(path) && change.codes.any(::hasNoControlledRevision)
                }
            ) {
                return@safely null
            }
            VcsRevisionNumber.Long(snapshot.status.workspaceChangeset)
        }

    override fun getLastRevision(file: VirtualFile): ItemLatestState? = null

    override fun getLastRevision(file: FilePath): ItemLatestState? = null

    override fun createFileContent(
        revisionNumber: VcsRevisionNumber,
        selectedFile: VirtualFile,
    ): ContentRevision? =
        safely { createBaselineContent(selectedFile, revisionNumber) }

    override fun createCurrentFileContent(file: VirtualFile): ContentRevision? =
        safely { createBaselineContent(file, expectedRevision = null) }

    override fun getLatestCommittedRevision(file: VirtualFile): VcsRevisionNumber? = null

    private fun createBaselineContent(
        selectedFile: VirtualFile,
        expectedRevision: VcsRevisionNumber?,
    ): ContentRevision? {
        if (selectedFile.isDirectory || vcs.isDispatchThread()) return null
        val selectedPath = selectedFile.localPath()
        val snapshot = vcs.loadStatus(selectedPath, vcs.currentCancellation(), includePrivateFiles = true) ?: return null
        val currentNumber = VcsRevisionNumber.Long(snapshot.status.workspaceChangeset)
        if (expectedRevision != null && expectedRevision != currentNumber) return null

        val pendingChange = snapshot.status.changes.firstOrNull { change -> change.matches(selectedPath) }
        if (pendingChange?.codes?.any { code ->
                code == PlasticStatusCode.ADDED || hasNoControlledRevision(code)
            } == true
        ) {
            return null
        }
        val basePath = pendingChange?.oldPath ?: selectedPath
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

    private fun VirtualFile.localPath(): Path =
        Path.of(path).toAbsolutePath().normalize()

    private companion object {
        val LOG: Logger = Logger.getInstance(PlasticDiffProvider::class.java)
    }
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
    override fun getContentAsBytes(): ByteArray =
        vcs.loadBaseContent(snapshot, basePath, vcs.currentCancellation())

    override fun getContent(): String =
        CharsetToolkit.bytesToString(getContentAsBytes(), file.getCharset(vcs.project))

    override fun getFile(): FilePath = file

    override fun getRevisionNumber(): VcsRevisionNumber = revisionNumber
}
