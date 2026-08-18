package com.teamcomplex.plasticinsight.core

import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class PlasticHistoricalRevisionXmlParserTest {
    private val parser = PlasticHistoricalRevisionXmlParser()

    @Test
    fun `parses one historical path match with Unicode and unknown type`() {
        val result = assertIs<PlasticHistoricalRevisionLookup.Found>(parser.parse(fixture("one.xml")))
        val revision = result.revision

        assertEquals(701, revision.revisionId)
        assertEquals(42, revision.changeset)
        assertNull(revision.parentRevisionId)
        assertEquals(501, revision.itemId)
        assertEquals("/Source Ω/File & Name.cs", revision.path)
        assertEquals(OffsetDateTime.parse("2025-01-02T03:04:05+01:00"), revision.createdAt)
        assertEquals(PlasticRevisionTypeKind.UNKNOWN, revision.revisionType.kind)
        assertEquals("future-type", revision.revisionType.raw)
        assertEquals(UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"), revision.guid)
    }

    @Test
    fun `maps zero matches explicitly`() {
        assertIs<PlasticHistoricalRevisionLookup.NotFound>(parser.parse(fixture("empty.xml")))
    }

    @Test
    fun `maps multiple matches explicitly`() {
        val output = repeatedMatches(2)

        val result = assertIs<PlasticHistoricalRevisionLookup.Ambiguous>(parser.parse(output))

        assertEquals(2, result.revisions.size)
    }

    @Test
    fun `rejects results beyond the configured match bound`() {
        assertFailsWith<PlasticParseException> {
            PlasticHistoricalRevisionXmlParser(maxEntries = 2).parse(repeatedMatches(3))
        }
    }

    @Test
    fun `rejects a missing historical path`() {
        val output = fixture("one.xml").toString(StandardCharsets.UTF_8)
            .replace("<PATH>/Source Ω/File &amp; Name.cs</PATH>", "<PATH></PATH>")
            .toByteArray(StandardCharsets.UTF_8)

        assertFailsWith<PlasticParseException> {
            parser.parse(output)
        }
    }

    @Test
    fun `rejects malformed numeric date and GUID fields`() {
        val source = fixture("one.xml").toString(StandardCharsets.UTF_8)

        assertFailsWith<PlasticParseException> {
            parser.parse(source.replace("<ID>701</ID>", "<ID>invalid</ID>").toByteArray())
        }
        assertFailsWith<PlasticParseException> {
            parser.parse(
                source.replace(
                    "2025-01-02T03:04:05+01:00",
                    "not-a-date",
                ).toByteArray(StandardCharsets.UTF_8),
            )
        }
        assertFailsWith<PlasticParseException> {
            parser.parse(
                source.replace(
                    "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                    "not-a-guid",
                ).toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    @Test
    fun `rejects DTD and external entity input`() {
        val output = """
            <?xml version="1.0"?>
            <!DOCTYPE query [<!ENTITY xxe SYSTEM "file:///not-readable">]>
            <PLASTICQUERY>&xxe;</PLASTICQUERY>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        assertFailsWith<PlasticParseException> {
            parser.parse(output)
        }
    }

    @Test
    fun `invokes the cancellation hook while streaming`() {
        var checks = 0

        assertFailsWith<TestCancellation> {
            parser.parse(fixture("one.xml")) {
                checks++
                if (checks == 5) {
                    throw TestCancellation()
                }
            }
        }
    }

    private fun repeatedMatches(count: Int): ByteArray {
        val source = fixture("one.xml").toString(StandardCharsets.UTF_8)
        val revision = source.substringAfter("<PLASTICQUERY>").substringBeforeLast("</PLASTICQUERY>")
        return "<PLASTICQUERY>${revision.repeat(count)}</PLASTICQUERY>".toByteArray(StandardCharsets.UTF_8)
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResource("/fixtures/historical-revision/$name")).readBytes()

    private class TestCancellation : RuntimeException()
}
