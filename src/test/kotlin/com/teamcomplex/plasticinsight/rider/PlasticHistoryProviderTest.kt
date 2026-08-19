package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.vcs.VcsException
import com.teamcomplex.plasticinsight.core.PlasticHistoryChangeset
import com.teamcomplex.plasticinsight.core.PlasticHistoryEntryKind
import com.teamcomplex.plasticinsight.core.PlasticHistoryRevision
import com.teamcomplex.plasticinsight.core.PlasticRevisionDataStatus
import com.teamcomplex.plasticinsight.core.PlasticRevisionType
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlasticHistoryProviderTest {
    @Test
    fun `maps revision metadata and loads bytes only when requested`() {
        val bytes = byteArrayOf(0, 1, 2, -1)
        var loads = 0
        val revision = contentRevision(
            changeset = PlasticHistoryChangeset.Number(12),
            comment = "Synthetic change",
        )
        val riderRevision = PlasticVcsFileRevision(revision) {
            loads++
            bytes
        }

        assertEquals("12", riderRevision.revisionNumber.asString())
        assertEquals(revision.createdAt.toInstant(), riderRevision.revisionDate.toInstant())
        assertEquals("tester", riderRevision.author)
        assertEquals("Synthetic change", riderRevision.commitMessage)
        assertEquals("/main", riderRevision.branchName)
        assertTrue(riderRevision.isContentAvailable)
        assertEquals(0, loads)
        assertContentEquals(bytes, riderRevision.loadContent())
        assertEquals(1, loads)
    }

    @Test
    fun `uses stable chronological ordering even when the displayed changeset is checkout`() {
        val older = contentRevision(
            changeset = PlasticHistoryChangeset.Number(10),
        ).toRiderRevisionNumber()
        val checkout = contentRevision(
            changeset = PlasticHistoryChangeset.Checkout,
            createdAt = OffsetDateTime.parse("2026-02-01T12:00:00Z"),
        ).toRiderRevisionNumber()

        assertTrue(older < checkout)
        assertEquals("CO", checkout.asString())
        assertEquals("CO", checkout.toString())
    }

    @Test
    fun `metadata events remain visible but never claim file content`() {
        var loaded = false
        val event = metadataEvent()
        val riderRevision = PlasticVcsFileRevision(event) {
            loaded = true
            byteArrayOf(1)
        }
        val session = PlasticHistorySession(
            filePath = Path.of("C:\\synthetic\\file.cs"),
            revisions = listOf(riderRevision),
            hasMore = true,
            requestedLimit = 50,
        )

        assertEquals("Moved from synthetic-old.cs", riderRevision.commitMessage)
        assertFalse(riderRevision.isContentAvailable)
        assertFalse(session.isContentAvailable(riderRevision))
        assertTrue(session.hasMore)
        assertTrue(session.hasLocalSource())
        assertNull(session.currentRevisionNumber)
        assertFailsWith<VcsException> { riderRevision.loadContent() }
        assertFalse(loaded)
    }

    @Test
    fun `history expansion uses only two deliberate bounded steps`() {
        assertEquals(200, nextHistoryLimit(50))
        assertEquals(999, nextHistoryLimit(200))
        assertEquals(999, nextHistoryLimit(999))
    }

    @Test
    fun `checkout archived and directory revisions do not expose retrievable bytes`() {
        assertFalse(
            contentRevision(changeset = PlasticHistoryChangeset.Checkout).hasAvailableContent(),
        )
        assertFalse(
            contentRevision(dataStatus = PlasticRevisionDataStatus("Archived")).hasAvailableContent(),
        )
        assertFalse(
            contentRevision(revisionType = PlasticRevisionType("dir")).hasAvailableContent(),
        )
    }

    private fun contentRevision(
        changeset: PlasticHistoryChangeset = PlasticHistoryChangeset.Number(10),
        createdAt: OffsetDateTime = OffsetDateTime.parse("2026-01-01T12:00:00Z"),
        comment: String = "",
        revisionType: PlasticRevisionType = PlasticRevisionType("txt"),
        dataStatus: PlasticRevisionDataStatus = PlasticRevisionDataStatus("Available"),
    ): PlasticHistoryRevision =
        PlasticHistoryRevision(
            revisionSpec = "synthetic.cs#cs:${changeset.displayValue}",
            branch = "/main",
            createdAt = createdAt,
            entryKind = PlasticHistoryEntryKind.CONTENT_REVISION,
            revisionType = revisionType,
            changeset = changeset,
            owner = "tester",
            comment = comment,
            repository = "synthetic-repository",
            server = "synthetic-server",
            dataStatus = dataStatus,
            itemPathOrSpec = "synthetic.cs",
            itemId = 7,
            sizeBytes = 4,
            hash = null,
            hashAlgorithm = null,
        )

    private fun metadataEvent(): PlasticHistoryRevision =
        PlasticHistoryRevision(
            revisionSpec = "synthetic.cs#cs:11",
            branch = "Moved from synthetic-old.cs",
            createdAt = OffsetDateTime.parse("2026-01-02T12:00:00Z"),
            entryKind = PlasticHistoryEntryKind.METADATA_EVENT,
            revisionType = null,
            changeset = PlasticHistoryChangeset.Number(11),
            owner = "tester",
            comment = "",
            repository = "synthetic-repository",
            server = "synthetic-server",
            dataStatus = null,
            itemPathOrSpec = null,
            itemId = null,
            sizeBytes = 0,
            hash = null,
            hashAlgorithm = null,
        )
}
