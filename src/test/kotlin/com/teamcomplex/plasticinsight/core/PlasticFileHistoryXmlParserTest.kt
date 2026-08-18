package com.teamcomplex.plasticinsight.core

import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlasticFileHistoryXmlParserTest {
    private val parser = PlasticFileHistoryXmlParser()

    @Test
    fun `parses content checkout event and unknown metadata from UTF-8 bytes`() {
        val history = parser.parse(fixture("representative.xml"))

        assertEquals("C:\\samples\\Source Ω\\File & Name.cs", history.itemName)
        assertEquals(PlasticHistoryOrder.OLDEST_FIRST, history.order)
        assertEquals(4, history.revisions.size)

        val first = history.revisions[0]
        assertEquals(OffsetDateTime.parse("2025-01-02T03:04:05+01:00"), first.createdAt)
        assertEquals("First line\nSecond line & <tag> Ω", first.comment)
        assertEquals(PlasticRevisionTypeKind.TEXT, first.revisionType?.kind)
        assertEquals(PlasticRevisionDataStatusKind.AVAILABLE, first.dataStatus?.kind)
        assertEquals(10, assertIs<PlasticHistoryChangeset.Number>(first.changeset).value)

        val checkout = history.revisions[1]
        assertIs<PlasticHistoryChangeset.Checkout>(checkout.changeset)
        assertEquals(PlasticRevisionTypeKind.BINARY, checkout.revisionType?.kind)
        assertNull(checkout.hash)

        val event = history.revisions[2]
        assertEquals(PlasticHistoryEntryKind.METADATA_EVENT, event.entryKind)
        assertNull(event.revisionType)
        assertNull(event.dataStatus)
        assertNull(event.itemId)
        assertNull(event.itemPathOrSpec)

        val future = history.revisions[3]
        assertEquals(PlasticRevisionTypeKind.UNKNOWN, future.revisionType?.kind)
        assertEquals("future-type", future.revisionType?.raw)
        assertEquals(PlasticRevisionDataStatusKind.UNKNOWN, future.dataStatus?.kind)
        assertEquals("ColdStorage", future.dataStatus?.raw)
    }

    @Test
    fun `parses the self-closing empty result emitted for an unavailable item`() {
        val history = parser.parse(fixture("empty.xml"))

        assertNull(history.itemName)
        assertTrue(history.revisions.isEmpty())
        assertEquals(PlasticHistoryOrder.OLDEST_FIRST, history.order)
    }

    @Test
    fun `rejects a response beyond the configured entry bound`() {
        assertFailsWith<PlasticParseException> {
            PlasticFileHistoryXmlParser(maxEntries = 3).parse(fixture("representative.xml"))
        }
    }

    @Test
    fun `rejects multiple item histories for the single-file contract`() {
        val output = """
            <RevisionHistoriesResult><RevisionHistories>
              <RevisionHistory><ItemName>C:\samples\one.cs</ItemName><Revisions /></RevisionHistory>
              <RevisionHistory><ItemName>C:\samples\two.cs</ItemName><Revisions /></RevisionHistory>
            </RevisionHistories></RevisionHistoriesResult>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        assertFailsWith<PlasticParseException> {
            parser.parse(output)
        }
    }

    @Test
    fun `rejects malformed numeric and date fields`() {
        val source = fixture("representative.xml").toString(StandardCharsets.UTF_8)

        assertFailsWith<PlasticParseException> {
            parser.parse(source.replace("<Size>128</Size>", "<Size>invalid</Size>").toByteArray())
        }
        assertFailsWith<PlasticParseException> {
            parser.parse(
                source.replace(
                    "2025-01-02T03:04:05+01:00",
                    "not-a-date",
                ).toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    @Test
    fun `rejects DTD and external entity input`() {
        val output = """
            <?xml version="1.0"?>
            <!DOCTYPE history [<!ENTITY xxe SYSTEM "file:///not-readable">]>
            <RevisionHistoriesResult>&xxe;</RevisionHistoriesResult>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        assertFailsWith<PlasticParseException> {
            parser.parse(output)
        }
    }

    @Test
    fun `invokes the cancellation hook while streaming`() {
        var checks = 0

        assertFailsWith<TestCancellation> {
            parser.parse(fixture("representative.xml")) {
                checks++
                if (checks == 5) {
                    throw TestCancellation()
                }
            }
        }
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResource("/fixtures/history/$name")).readBytes()

    private class TestCancellation : RuntimeException()
}
