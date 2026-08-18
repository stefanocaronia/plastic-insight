package com.teamcomplex.plasticinsight.core

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceDiscoveryParserTest {
    private val parser = WorkspaceDiscoveryParser()

    @Test
    fun `parses a regular static workspace and normalizes the drive letter`() {
        val workspace = parser.parse(fixture("regular-static.txt"))

        assertEquals("Sample Workspace Ω", workspace.name)
        assertEquals(Path.of("F:\\samples\\Sample Workspace Ω"), workspace.root)
        assertEquals("BUILD-01", workspace.machine)
        assertEquals(UUID.fromString("11111111-2222-4333-8444-555555555555"), workspace.id)
        assertEquals("regular", workspace.workspaceType)
        assertFalse(workspace.isDynamic)
    }

    @Test
    fun `parses Unicode metadata for a dynamic workspace`() {
        val workspace = parser.parse(fixture("dynamic-unicode.txt"))

        assertEquals("动态 Workspace", workspace.name)
        assertEquals(Path.of("C:\\工作\\动态 Workspace"), workspace.root)
        assertEquals("机器-02", workspace.machine)
        assertEquals("partial", workspace.workspaceType)
        assertTrue(workspace.isDynamic)
    }

    @Test
    fun `rejects an incomplete record`() {
        assertFailsWith<PlasticParseException> {
            parser.parse("workspace\u001FC:\\workspace\u001Fmachine")
        }
    }

    @Test
    fun `rejects an invalid workspace ID`() {
        assertFailsWith<PlasticParseException> {
            parser.parse(record(id = "not-a-uuid"))
        }
    }

    @Test
    fun `rejects a relative workspace root`() {
        assertFailsWith<PlasticParseException> {
            parser.parse(record(root = "relative\\workspace"))
        }
    }

    @Test
    fun `rejects an unknown workspace mode`() {
        assertFailsWith<PlasticParseException> {
            parser.parse(record(mode = "maybe"))
        }
    }

    private fun fixture(name: String): String {
        val resource = requireNotNull(javaClass.getResource("/fixtures/workspace-discovery/$name"))
        return resource.readText(StandardCharsets.UTF_8).replace("\\u001F", "\u001F")
    }

    private fun record(
        root: String = "C:\\workspace",
        id: String = "11111111-2222-4333-8444-555555555555",
        mode: String = "static",
    ): String =
        listOf("workspace", root, "machine", id, "regular", mode).joinToString("\u001F")
}
