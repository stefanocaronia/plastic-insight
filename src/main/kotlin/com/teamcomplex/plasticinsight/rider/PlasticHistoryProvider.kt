package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.RepositoryLocation
import com.intellij.openapi.vcs.VcsDataKeys
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
import com.teamcomplex.plasticinsight.core.PlasticHistoryRequest
import com.teamcomplex.plasticinsight.core.PlasticHistoryRevision
import com.teamcomplex.plasticinsight.core.PlasticRevisionDataStatusKind
import com.teamcomplex.plasticinsight.core.PlasticRevisionTypeKind
import java.nio.file.Path
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent

/** Maps bounded Plastic history onto Rider's standard history and diff UI. */
internal class PlasticHistoryProvider(
    private val vcs: PlasticVcs,
) : VcsHistoryProvider {
    private val requestedExpansion = AtomicReference<HistoryExpansionRequest?>(null)

    override fun createSessionFor(filePath: FilePath): VcsHistorySession {
        val path = filePath.nioPath()
        val limit = consumeRequestedLimit(path)
        val page = vcs.loadFileHistory(path, vcs.currentCancellation(), limit)
        return PlasticHistorySession(path, page.revisions.map(::toFileRevision), page.hasMore, limit)
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
                    text(
                        "Showing the ${it.revisionList.size} most recent Plastic revisions. " +
                            "Use Load More Revisions in the toolbar to expand this bounded view.",
                    )
                }
            }
        return VcsDependentHistoryComponents(ColumnInfo.EMPTY_ARRAY, null, null, notification)
    }

    override fun getAdditionalActions(refresher: Runnable): Array<AnAction> =
        arrayOf(LoadMoreRevisionsAction(refresher))

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

    private fun consumeRequestedLimit(path: Path): Int {
        while (true) {
            val request = requestedExpansion.get() ?: return INITIAL_HISTORY_LIMIT
            if (request.path != path) return INITIAL_HISTORY_LIMIT
            if (requestedExpansion.compareAndSet(request, null)) return request.limit
        }
    }

    private inner class LoadMoreRevisionsAction(
        private val refresher: Runnable,
    ) : DumbAwareAction("Load More Revisions") {
        override fun actionPerformed(event: AnActionEvent) {
            val session = event.getData(VcsDataKeys.HISTORY_SESSION) as? PlasticHistorySession ?: return
            if (!session.hasMore) return
            requestedExpansion.set(
                HistoryExpansionRequest(session.filePath, nextHistoryLimit(session.requestedLimit)),
            )
            refresher.run()
        }

        override fun update(event: AnActionEvent) {
            val session = event.getData(VcsDataKeys.HISTORY_SESSION) as? PlasticHistorySession
            if (session == null || !session.hasMore || session.requestedLimit >= PlasticHistoryRequest.MAX_LIMIT) {
                event.presentation.isEnabledAndVisible = false
                return
            }
            val nextLimit = nextHistoryLimit(session.requestedLimit)
            event.presentation.isEnabledAndVisible = true
            event.presentation.text = "Load Up to $nextLimit Revisions"
        }
    }

    private fun FilePath.nioPath(): Path = ioFile.toPath().toAbsolutePath().normalize()
}

internal class PlasticHistorySession(
    val filePath: Path,
    revisions: List<VcsFileRevision>,
    val hasMore: Boolean,
    val requestedLimit: Int,
) : VcsAbstractHistorySession(revisions) {
    override fun calcCurrentRevisionNumber(): VcsRevisionNumber? = null

    override fun copy(): VcsHistorySession =
        PlasticHistorySession(filePath, ArrayList(revisionList), hasMore, requestedLimit)

    override fun isContentAvailable(revision: VcsFileRevision): Boolean =
        (revision as? PlasticVcsFileRevision)?.isContentAvailable == true

    override fun hasLocalSource(): Boolean = true
}

internal fun nextHistoryLimit(currentLimit: Int): Int =
    when {
        currentLimit < EXTENDED_HISTORY_LIMIT -> EXTENDED_HISTORY_LIMIT
        currentLimit < PlasticHistoryRequest.MAX_LIMIT -> PlasticHistoryRequest.MAX_LIMIT
        else -> PlasticHistoryRequest.MAX_LIMIT
    }

private data class HistoryExpansionRequest(
    val path: Path,
    val limit: Int,
)

private const val INITIAL_HISTORY_LIMIT = PlasticHistoryRequest.DEFAULT_LIMIT
private const val EXTENDED_HISTORY_LIMIT = 200

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
