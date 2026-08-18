package com.teamcomplex.plasticinsight.core

import java.nio.file.Files
import java.nio.file.Path

/** Finds the nearest Plastic root by checking one marker per parent directory. */
class PlasticWorkspaceLocator(
    private val markerExists: (Path) -> Boolean = { marker -> Files.isRegularFile(marker) },
) {
    fun findRoot(startDirectory: Path): Path? {
        require(startDirectory.isAbsolute) { "The workspace lookup directory must be absolute." }

        var candidate: Path? = startDirectory.normalize()
        while (candidate != null) {
            if (markerExists(candidate.resolve(WORKSPACE_MARKER))) return candidate
            candidate = candidate.parent
        }
        return null
    }

    private companion object {
        val WORKSPACE_MARKER: Path = Path.of(".plastic", "plastic.workspace")
    }
}
