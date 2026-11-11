package com.teamcomplex.plasticinsight.rider

import com.teamcomplex.plasticinsight.core.status.PlasticPendingChange
import com.teamcomplex.plasticinsight.core.status.PlasticStatusCode
import com.teamcomplex.plasticinsight.core.status.PlasticWorkspaceStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlasticDiffProviderTest {
    @Test
    fun `a clean file uses the workspace changeset and its own path as baseline`() {
        val status = status(change(PlasticStatusCode.CHANGED, path = OTHER_FILE))

        assertEquals(WORKSPACE_CHANGESET, status.controlledChangeset(FILE))
        assertEquals(FILE, status.baselinePath(FILE))
    }

    @Test
    fun `a modified file uses the workspace changeset and its own path as baseline`() {
        val status = status(change(PlasticStatusCode.CHECKED_OUT, PlasticStatusCode.CHANGED, path = FILE))

        assertEquals(WORKSPACE_CHANGESET, status.controlledChangeset(FILE))
        assertEquals(FILE, status.baselinePath(FILE))
    }

    @Test
    fun `an added file keeps the workspace changeset but has no baseline`() {
        val status = status(change(PlasticStatusCode.ADDED, path = FILE))

        assertEquals(WORKSPACE_CHANGESET, status.controlledChangeset(FILE))
        assertNull(status.baselinePath(FILE))
    }

    @Test
    fun `a private file has neither a controlled revision nor a baseline`() {
        val status = status(change(PlasticStatusCode.PRIVATE, path = FILE))

        assertNull(status.controlledChangeset(FILE))
        assertNull(status.baselinePath(FILE))
    }

    @Test
    fun `an ignored file has neither a controlled revision nor a baseline`() {
        val status = status(change(PlasticStatusCode.IGNORED, path = FILE))

        assertNull(status.controlledChangeset(FILE))
        assertNull(status.baselinePath(FILE))
    }

    @Test
    fun `a moved file finds its baseline at the old path from either path`() {
        val destination = ROOT.resolve("Moved").resolve("file.cs")
        val status = status(
            change(PlasticStatusCode.MOVED, PlasticStatusCode.CHANGED, path = destination, oldPath = FILE),
        )

        assertEquals(WORKSPACE_CHANGESET, status.controlledChangeset(destination))
        assertEquals(WORKSPACE_CHANGESET, status.controlledChangeset(FILE))
        assertEquals(FILE, status.baselinePath(destination))
        assertEquals(FILE, status.baselinePath(FILE))
    }

    @Test
    fun `an empty status uses the workspace changeset and the file's own path`() {
        val status = status()

        assertEquals(WORKSPACE_CHANGESET, status.controlledChangeset(FILE))
        assertEquals(FILE, status.baselinePath(FILE))
    }

    @Test
    fun `paths are compared after normalization`() {
        val unnormalized = ROOT.resolve("Source").resolve("..").resolve("file.cs")
        val status = status(change(PlasticStatusCode.PRIVATE, path = FILE))

        assertNull(status.controlledChangeset(unnormalized))
        assertNull(status.baselinePath(unnormalized))
    }

    @Test
    fun `any private match removes the revision while only the first match decides the baseline`() {
        val status = status(
            change(PlasticStatusCode.CHANGED, path = FILE),
            change(PlasticStatusCode.PRIVATE, path = FILE),
        )

        assertNull(status.controlledChangeset(FILE))
        assertEquals(FILE, status.baselinePath(FILE))
    }

    private fun status(vararg changes: PlasticPendingChange): PlasticWorkspaceStatus =
        PlasticWorkspaceStatus(
            workspaceChangeset = WORKSPACE_CHANGESET,
            repository = "synthetic-repository",
            server = "synthetic-server",
            changes = changes.toList(),
        )

    private fun change(
        vararg codes: PlasticStatusCode,
        path: Path,
        oldPath: Path? = null,
    ): PlasticPendingChange =
        PlasticPendingChange(
            codes = codes.toSet(),
            path = path,
            oldPath = oldPath,
            isDirectory = false,
            revisionId = 7L.takeUnless { PlasticStatusCode.PRIVATE in codes || PlasticStatusCode.IGNORED in codes },
            similarityPercent = oldPath?.let { 100.0 },
        )

    private companion object {
        const val WORKSPACE_CHANGESET = 42L
        val ROOT: Path = Path.of("build", "tmp", "synthetic-workspace").toAbsolutePath().normalize()
        val FILE: Path = ROOT.resolve("file.cs")
        val OTHER_FILE: Path = ROOT.resolve("other.cs")
    }
}
