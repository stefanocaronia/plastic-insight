package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.RepositoryLocation
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.DiffFromHistoryHandler
import com.intellij.openapi.vcs.history.VcsAbstractHistorySession
import com.intellij.openapi.vcs.history.VcsAppendableHistorySessionPartner
import com.intellij.openapi.vcs.history.VcsDependentHistoryComponents
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vcs.history.VcsHistorySession
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.util.ui.ColumnInfo
import com.teamcomplex.plasticinsight.core.PlasticHistoryChangeset
import com.teamcomplex.plasticinsight.core.PlasticHistoryEntryKind
import com.teamcomplex.plasticinsight.core.PlasticHistoryRevision
import com.teamcomplex.plasticinsight.core.PlasticRevisionDataStatusKind
import com.teamcomplex.plasticinsight.core.PlasticRevisionTypeKind
import java.nio.file.Path
import java.time.Instant
import java.util.Date
import javax.swing.JComponent

/** Maps bounded Plastic history onto Rider's standard history and diff UI. */
internal class PlasticHistoryProvider(
    private val vcs: PlasticVcs,
) : VcsHistoryProvider {
    override fun createSessionFor(filePath: FilePath): VcsHistorySession {
        val page = vcs.loadFileHistory(filePath.nioPath(), vcs.currentCancellation())
        return PlasticHistorySession(page.revisions.map(::toFileRevision), page.hasMore)
    }

    override fun reportAppendableHistory(
        filePath: FilePath,
        partner: VcsAppendableHistorySessionPartner,
    ) {
        // Plastic history has no verified cursor, so publish one bounded session.
        partner.reportCreatedEmptySession(createSessionFor(filePath) as PlasticHistorySession)
    }

    override fun getUICustomization(
        session: VcsHistorySession,
        forShortcutRegistration: JComponent,
    ): VcsDependentHistoryComponents {
        val notification = (session as? PlasticHistorySession)
            ?.takeIf { it.hasMore }
            ?.let {
                EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                    text("Showing the ${it.revisionList.size} most recent Plastic revisions.")
                }
            }
        return VcsDependentHistoryComponents(ColumnInfo.EMPTY_ARRAY, null, null, notification)
    }

    override fun getAdditionalActions(refresher: Runnable): Array<AnAction> = emptyArray()

    override fun isDateOmittable(): Boolean = false

    override fun getHelpId(): String? = null

    override fun supportsHistoryForDirectories(): Boolean = false

    override fun getHistoryDiffHandler(): DiffFromHistoryHandler? = null

    override fun canShowHistoryFor(file: VirtualFile): Boolean =
        !file.isDirectory && file.isInLocalFileSystem

    private fun toFileRevision(revision: PlasticHistoryRevision): VcsFileRevision =
        PlasticVcsFileRevision(revision) {
            vcs.loadRevisionContent(revision, vcs.currentCancellation())
        }

    private fun FilePath.nioPath(): Path = ioFile.toPath().toAbsolutePath().normalize()
}

internal class PlasticHistorySession(
    revisions: List<VcsFileRevision>,
    val hasMore: Boolean,
) : VcsAbstractHistorySession(revisions) {
    override fun calcCurrentRevisionNumber(): VcsRevisionNumber? = null

    override fun copy(): VcsHistorySession =
        PlasticHistorySession(ArrayList(revisionList), hasMore)

    override fun isContentAvailable(revision: VcsFileRevision): Boolean =
        (revision as? PlasticVcsFileRevision)?.isContentAvailable == true

    override fun hasLocalSource(): Boolean = true
}

internal class PlasticVcsFileRevision(
    private val revision: PlasticHistoryRevision,
    private val contentLoader: () -> ByteArray,
) : VcsFileRevision {
    private val number = revision.toRiderRevisionNumber()

    val isContentAvailable: Boolean = revision.hasAvailableContent()

    override fun getRevisionNumber(): VcsRevisionNumber = number

    override fun getRevisionDate(): Date = Date.from(revision.createdAt.toInstant())

    override fun getAuthor(): String = revision.owner

    override fun getCommitMessage(): String = revision.historyMessage()

    override fun getBranchName(): String = revision.branch

    override fun getChangedRepositoryPath(): RepositoryLocation? = null

    override fun loadContent(): ByteArray {
        if (!isContentAvailable) throw VcsException("This Plastic revision has no available file content.")
        return contentLoader()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getContent(): ByteArray = loadContent()
}

internal data class PlasticHistoryRevisionNumber(
    private val displayValue: String,
    private val createdAt: Instant,
    private val identity: String,
) : VcsRevisionNumber {
    override fun asString(): String = displayValue

    override fun toString(): String = asString()

    override fun compareTo(other: VcsRevisionNumber): Int =
        if (other is PlasticHistoryRevisionNumber) {
            compareValuesBy(this, other, PlasticHistoryRevisionNumber::createdAt, PlasticHistoryRevisionNumber::identity)
        } else {
            asString().compareTo(other.asString())
        }
}

internal fun PlasticHistoryRevision.toRiderRevisionNumber(): PlasticHistoryRevisionNumber =
    PlasticHistoryRevisionNumber(changeset.displayValue, createdAt.toInstant(), revisionSpec)

internal fun PlasticHistoryRevision.hasAvailableContent(): Boolean =
    entryKind == PlasticHistoryEntryKind.CONTENT_REVISION &&
        changeset is PlasticHistoryChangeset.Number &&
        itemId != null && itemId <= Int.MAX_VALUE &&
        dataStatus?.kind == PlasticRevisionDataStatusKind.AVAILABLE &&
        revisionType?.kind != PlasticRevisionTypeKind.DIRECTORY

internal fun PlasticHistoryRevision.historyMessage(): String =
    comment.ifBlank {
        branch.takeIf { entryKind == PlasticHistoryEntryKind.METADATA_EVENT }.orEmpty()
    }
