package com.teamcomplex.plasticinsight.core.history

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlasticItemMovesTest {
    @Test
    fun `one move gives the old path and then the new path`() {
        val revisions = listOf(moveEvent("Moved from /Source/Old.cs to /Source/New.cs"))

        assertEquals(listOf("/Source/Old.cs", "/Source/New.cs"), revisions.formerItemPaths())
    }

    @Test
    fun `several moves keep the first occurrence of each path in order`() {
        val revisions = listOf(
            moveEvent("Moved from /Source/Renamed.cs to /Source/Final.cs"),
            moveEvent("Moved from /Source/Original.cs to /Source/Renamed.cs"),
        )

        assertEquals(
            listOf("/Source/Renamed.cs", "/Source/Final.cs", "/Source/Original.cs"),
            revisions.formerItemPaths(),
        )
    }

    @Test
    fun `a path with spaces and a folder named like the separator word is kept whole`() {
        val revisions = listOf(moveEvent("Moved from /Assets/Moved to/File.cs to /Assets/New/File.cs"))

        assertEquals(listOf("/Assets/Moved to/File.cs", "/Assets/New/File.cs"), revisions.formerItemPaths())
    }

    @Test
    fun `a localized move text of the same shape still gives both paths`() {
        val revisions = listOf(moveEvent("Spostato da /Sorgente/Vecchio file.cs a /Altro/Nuovo file.cs"))

        assertEquals(
            listOf("/Sorgente/Vecchio file.cs", "/Altro/Nuovo file.cs"),
            revisions.formerItemPaths(),
        )
    }

    @Test
    fun `a metadata event that is not a move gives no path`() {
        val revisions = listOf(moveEvent("Label applied"), moveEvent("/main/task"))

        assertEquals(emptyList<String>(), revisions.formerItemPaths())
    }

    @Test
    fun `content revisions are ignored even when their branch looks like a move`() {
        val revisions = listOf(contentRevision(branch = "Moved from /Source/Old.cs to /Source/New.cs"))

        assertEquals(emptyList<String>(), revisions.formerItemPaths())
    }

    @Test
    fun `at most eight paths are kept`() {
        val revisions = (1..10).map { index -> moveEvent("Moved from /Source/Old$index.cs to /Source/New$index.cs") }

        val paths = revisions.formerItemPaths()

        assertEquals(MAX_FORMER_ITEM_PATHS, paths.size)
        assertEquals("/Source/Old1.cs", paths.first())
        assertEquals("/Source/New4.cs", paths.last())
    }

    @Test
    fun `bytes with the reported size and MD5 are the revision content`() {
        val bytes = "sample content".toByteArray()

        assertTrue(contentRevision(sizeBytes = bytes.size.toLong(), hash = md5(bytes)).hasContent(bytes))
    }

    @Test
    fun `bytes of another size are not the revision content`() {
        val bytes = "sample content".toByteArray()

        assertFalse(contentRevision(sizeBytes = bytes.size + 1L, hash = md5(bytes)).hasContent(bytes))
    }

    @Test
    fun `bytes with another hash are not the revision content`() {
        val bytes = "sample content".toByteArray()

        assertFalse(
            contentRevision(sizeBytes = bytes.size.toLong(), hash = md5("other content".toByteArray())).hasContent(bytes),
        )
    }

    @Test
    fun `bytes cannot be verified without a hash`() {
        val bytes = "sample content".toByteArray()

        assertFalse(contentRevision(sizeBytes = bytes.size.toLong(), hash = null).hasContent(bytes))
    }

    @Test
    fun `bytes cannot be verified with an algorithm other than MD5`() {
        val bytes = "sample content".toByteArray()
        val revision = contentRevision(sizeBytes = bytes.size.toLong(), hash = md5(bytes), hashAlgorithm = "SHA256")

        assertFalse(revision.hasContent(bytes))
    }

    @Test
    fun `an empty file with the MD5 of empty bytes is the revision content`() {
        val bytes = byteArrayOf()

        assertTrue(contentRevision(sizeBytes = 0, hash = md5(bytes), hashAlgorithm = "md5").hasContent(bytes))
    }

    private fun md5(bytes: ByteArray): String = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))

    private fun moveEvent(branch: String): PlasticHistoryRevision =
        PlasticHistoryRevision(
            revisionSpec = "sample#cs:11",
            branch = branch,
            createdAt = OffsetDateTime.parse("2025-01-02T03:04:05+01:00"),
            entryKind = PlasticHistoryEntryKind.METADATA_EVENT,
            revisionType = null,
            changeset = PlasticHistoryChangeset.Number(11),
            owner = "developer@example.test",
            comment = "",
            repository = "Sample Repository",
            server = "example@cloud",
            dataStatus = null,
            itemPathOrSpec = null,
            itemId = null,
            sizeBytes = 0,
            hash = null,
            hashAlgorithm = null,
        )

    private fun contentRevision(
        branch: String = "/main",
        sizeBytes: Long = 0,
        hash: String? = null,
        hashAlgorithm: String? = hash?.let { "MD5" },
    ): PlasticHistoryRevision =
        PlasticHistoryRevision(
            revisionSpec = "sample#cs:10",
            branch = branch,
            createdAt = OffsetDateTime.parse("2025-01-02T03:04:05+01:00"),
            entryKind = PlasticHistoryEntryKind.CONTENT_REVISION,
            revisionType = PlasticRevisionType("txt"),
            changeset = PlasticHistoryChangeset.Number(10),
            owner = "developer@example.test",
            comment = "",
            repository = "Sample Repository",
            server = "example@cloud",
            dataStatus = PlasticRevisionDataStatus("Available"),
            itemPathOrSpec = "C:\\samples\\Source\\File.cs",
            itemId = 501,
            sizeBytes = sizeBytes,
            hash = hash,
            hashAlgorithm = hashAlgorithm,
        )
}
