package com.teamcomplex.plasticinsight.rider

import com.teamcomplex.plasticinsight.core.PlasticPendingChange
import com.teamcomplex.plasticinsight.core.PlasticStatusCode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlasticChangeProviderTest {
    @Test
    fun `maps supported Plastic states without treating checkout alone as modified`() {
        assertEquals(PlasticRiderChangeKind.MODIFIED, change(PlasticStatusCode.CHANGED).riderKind())
        assertEquals(
            PlasticRiderChangeKind.MODIFIED,
            change(PlasticStatusCode.CHECKED_OUT, PlasticStatusCode.CHANGED).riderKind(),
        )
        assertEquals(PlasticRiderChangeKind.ADDED, change(PlasticStatusCode.ADDED).riderKind())
        assertEquals(PlasticRiderChangeKind.DELETED, change(PlasticStatusCode.LOCALLY_DELETED).riderKind())
        assertNull(change(PlasticStatusCode.CHECKED_OUT).riderKind())
    }

    @Test
    fun `maps moves before other combined codes`() {
        val moved = change(
            PlasticStatusCode.CHECKED_OUT,
            PlasticStatusCode.CHANGED,
            PlasticStatusCode.MOVED,
            oldPath = ROOT.resolve("old.cs"),
        )

        assertEquals(PlasticRiderChangeKind.MOVED, moved.riderKind())
    }

    @Test
    fun `added takes precedence over move metadata because it has no baseline`() {
        val addedMove = change(
            PlasticStatusCode.ADDED,
            PlasticStatusCode.LOCALLY_MOVED,
            oldPath = ROOT.resolve("old.cs"),
        )

        assertEquals(PlasticRiderChangeKind.ADDED, addedMove.riderKind())
    }

    @Test
    fun `exact status plan is deterministic and deduplicated`() {
        val source = ROOT.resolve("Source")
        val first = source.resolve("B.cs")
        val second = source.resolve("A.cs")

        assertEquals(
            listOf(PlasticStatusScope(second, false), PlasticStatusScope(first, false)),
            planStatusScopes(listOf(first, second, first)),
        )
    }

    @Test
    fun `recursive root dominates nested scopes and exact files`() {
        val source = ROOT.resolve("Source")

        assertEquals(
            listOf(PlasticStatusScope(ROOT, true)),
            planStatusScopes(
                explicitPaths = listOf(source.resolve("File.cs")),
                recursivePaths = listOf(ROOT, source),
                contentRoots = listOf(source),
            ),
        )
    }

    @Test
    fun `many exact files coalesce to the affected content root`() {
        val source = ROOT.resolve("Source")
        val files = (1..5).map { index -> source.resolve("File$index.cs") }

        assertEquals(
            listOf(PlasticStatusScope(source, true)),
            planStatusScopes(explicitPaths = files, contentRoots = listOf(source)),
        )
    }

    @Test
    fun `recursive scope accepts both current and old moved paths`() {
        val source = ROOT.resolve("Source")
        val moved = change(PlasticStatusCode.MOVED, oldPath = source.resolve("old.cs"))

        assertTrue(PlasticStatusScope(ROOT, true).contains(moved))
        assertTrue(PlasticStatusScope(source.resolve("old.cs"), false).contains(moved))
    }

    private fun change(
        vararg codes: PlasticStatusCode,
        oldPath: Path? = null,
    ): PlasticPendingChange =
        PlasticPendingChange(
            codes = codes.toSet(),
            path = ROOT.resolve("file.cs"),
            oldPath = oldPath,
            isDirectory = false,
            revisionId = 7,
            similarityPercent = oldPath?.let { 100.0 },
        )

    private companion object {
        val ROOT: Path = Path.of("C:\\synthetic-workspace")
    }
}
