package com.teamcomplex.plasticinsight.core

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlasticStatusParserTest {
    private val parser = PlasticStatusParser()

    @Test
    fun `parses a clean status header captured from the installed CLI shape`() {
        val status = parser.parse(fixture("clean.txt"))

        assertEquals(42, status.workspaceChangeset)
        assertEquals("Sample Repository", status.repository)
        assertEquals("example@cloud", status.server)
        assertTrue(status.changes.isEmpty())
    }

    @Test
    fun `parses changed moved deleted and added records`() {
        val status = parser.parse(fixture("mixed.txt"))

        assertEquals(4, status.changes.size)

        val changed = status.changes[0]
        assertEquals(setOf(PlasticStatusCode.CHANGED), changed.codes)
        assertEquals(Path.of("F:\\samples\\Source Ω\\Changed.cs"), changed.path)
        assertEquals(1024, changed.revisionId)
        assertFalse(changed.isDirectory)
        assertFalse(changed.isMove)

        val moved = status.changes[1]
        assertEquals(
            setOf(PlasticStatusCode.CHECKED_OUT, PlasticStatusCode.CHANGED, PlasticStatusCode.MOVED),
            moved.codes,
        )
        assertEquals(Path.of("F:\\samples\\Old Name.cs"), moved.oldPath)
        assertEquals(Path.of("F:\\samples\\New Name.cs"), moved.path)
        assertEquals(87.5, moved.similarityPercent)
        assertTrue(moved.isMove)

        val deleted = status.changes[2]
        assertEquals(setOf(PlasticStatusCode.LOCALLY_DELETED), deleted.codes)
        assertEquals(33, deleted.revisionId)

        val added = status.changes[3]
        assertEquals(setOf(PlasticStatusCode.ADDED), added.codes)
        assertNull(added.revisionId)
    }

    @Test
    fun `accepts CLI line breaks between framed records`() {
        val output = record("STATUS", "42", "repository", "server") + "\r\n" +
            record("CH", "C:\\workspace\\file.cs", "False", "1", "NO_MERGES") + "\r\n"

        val status = parser.parse(output)

        assertEquals(1, status.changes.size)
        assertEquals(Path.of("C:\\workspace\\file.cs"), status.changes.single().path)
    }

    @Test
    fun `parses a private file with no revision`() {
        val output = record("STATUS", "42", "repository", "server") +
            record("PR", "C:\\workspace\\new.cs", "False", "-1", "NO_MERGES")

        val change = parser.parse(output).changes.single()

        assertEquals(setOf(PlasticStatusCode.PRIVATE), change.codes)
        assertNull(change.revisionId)
    }

    @Test
    fun `rejects output without record framing`() {
        assertFailsWith<PlasticParseException> {
            parser.parse("STATUS\u001F42\u001Frepository\u001Fserver")
        }
    }

    @Test
    fun `rejects an invalid header changeset`() {
        assertFailsWith<PlasticParseException> {
            parser.parse(record("STATUS", "not-a-number", "repository", "server"))
        }
    }

    @Test
    fun `rejects an unknown change code`() {
        val output = record("STATUS", "42", "repository", "server") +
            record("XX", "C:\\workspace\\file.cs", "False", "1", "NO_MERGES")

        assertFailsWith<PlasticParseException> {
            parser.parse(output)
        }
    }

    @Test
    fun `rejects a malformed move record`() {
        val output = record("STATUS", "42", "repository", "server") +
            record("MV", "100%", "C:\\workspace\\old.cs", "C:\\workspace\\new.cs", "False")

        assertFailsWith<PlasticParseException> {
            parser.parse(output)
        }
    }

    @Test
    fun `rejects a relative change path`() {
        val output = record("STATUS", "42", "repository", "server") +
            record("CH", "relative\\file.cs", "False", "1", "NO_MERGES")

        assertFailsWith<PlasticParseException> {
            parser.parse(output)
        }
    }

    @Test
    fun `rejects a revision ID below the Plastic sentinel`() {
        val output = record("STATUS", "42", "repository", "server") +
            record("AD", "C:\\workspace\\new.cs", "False", "-2", "NO_MERGES")

        assertFailsWith<PlasticParseException> {
            parser.parse(output)
        }
    }

    private fun fixture(name: String): String {
        val resource = requireNotNull(javaClass.getResource("/fixtures/status/$name"))
        return resource.readText(StandardCharsets.UTF_8)
            .replace("\\u001E", "\u001E")
            .replace("\\u001F", "\u001F")
            .replace("\\u001D", "\u001D")
    }

    private fun record(vararg fields: String): String =
        "\u001E${fields.joinToString("\u001F")}\u001D"
}
