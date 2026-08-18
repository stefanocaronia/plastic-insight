package com.teamcomplex.plasticinsight.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlasticWorkspaceLocatorTest {
    @Test
    fun `finds a workspace from its root`() {
        val root = absoluteTestPath("workspace")
        val locator = locatorWithMarkers(root)

        assertEquals(root, locator.findRoot(root))
    }

    @Test
    fun `walks parents and returns the nearest workspace`() {
        val outerRoot = absoluteTestPath("outer")
        val innerRoot = outerRoot.resolve("nested workspace")
        val start = innerRoot.resolve("Source").resolve("Feature")
        val visited = ArrayList<Path>()
        val markers = setOf(marker(outerRoot), marker(innerRoot))
        val locator = PlasticWorkspaceLocator { candidate ->
            visited.add(candidate)
            candidate in markers
        }

        val result = locator.findRoot(start)

        assertEquals(innerRoot, result)
        assertEquals(
            listOf(marker(start), marker(start.parent), marker(innerRoot)),
            visited,
        )
    }

    @Test
    fun `returns null when no parent contains the workspace marker`() {
        val locator = PlasticWorkspaceLocator { false }

        assertNull(locator.findRoot(absoluteTestPath("ordinary", "project")))
    }

    @Test
    fun `requires the plastic workspace marker file`() {
        val root = absoluteTestPath("workspace")
        val plasticDirectory = root.resolve(".plastic")
        val locator = PlasticWorkspaceLocator { candidate -> candidate == plasticDirectory }

        assertNull(locator.findRoot(root))
    }

    @Test
    fun `rejects a relative lookup directory`() {
        val locator = PlasticWorkspaceLocator { false }

        assertFailsWith<IllegalArgumentException> {
            locator.findRoot(Path.of("relative", "project"))
        }
    }

    private fun locatorWithMarkers(vararg roots: Path): PlasticWorkspaceLocator {
        val markers = roots.map(::marker).toSet()
        return PlasticWorkspaceLocator { candidate -> candidate in markers }
    }

    private fun marker(root: Path): Path = root.resolve(".plastic").resolve("plastic.workspace")

    private fun absoluteTestPath(vararg parts: String): Path =
        parts.fold(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()) { path, part ->
            path.resolve(part)
        }
}
