package com.teamcomplex.plasticinsight.rider

import com.teamcomplex.plasticinsight.core.PlasticPendingChange
import com.teamcomplex.plasticinsight.core.PlasticStatusCode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `dirty path plan is deterministic and deduplicated`() {
        val source = ROOT.resolve("Source")
        val first = source.resolve("B.cs")
        val second = source.resolve("A.cs")

        assertEquals(
            listOf(second, first),
            planDirtyPaths(listOf(first, second, first)),
        )
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
