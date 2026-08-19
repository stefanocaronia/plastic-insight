package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.VcsRootChecker
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/** Recognizes only the exact Plastic root and never starts the CLI. */
class PlasticRootChecker : VcsRootChecker() {
    override fun isRoot(root: VirtualFile): Boolean {
        if (!root.isDirectory) return false
        val administrativeDirectory = root.findChild(ADMINISTRATIVE_DIRECTORY) ?: return false
        val marker = administrativeDirectory.findChild(WORKSPACE_MARKER) ?: return false
        return administrativeDirectory.isDirectory && !marker.isDirectory
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun isRoot(path: String): Boolean =
        try {
            isRootPath(Path.of(path))
        } catch (_: InvalidPathException) {
            false
        } catch (_: SecurityException) {
            false
        }

    override fun getSupportedVcs(): VcsKey = PlasticVcs.KEY

    @Suppress("OVERRIDE_DEPRECATION")
    override fun isVcsDir(dirName: String): Boolean =
        dirName.equals(ADMINISTRATIVE_DIRECTORY, ignoreCase = true)

    internal companion object {
        private const val ADMINISTRATIVE_DIRECTORY = ".plastic"
        private const val WORKSPACE_MARKER = "plastic.workspace"

        fun isRootPath(candidate: Path): Boolean {
            val root = candidate.toAbsolutePath().normalize()
            val administrativeDirectory = root.resolve(ADMINISTRATIVE_DIRECTORY)
            return Files.isDirectory(administrativeDirectory, LinkOption.NOFOLLOW_LINKS) &&
                Files.isRegularFile(administrativeDirectory.resolve(WORKSPACE_MARKER), LinkOption.NOFOLLOW_LINKS)
        }

        fun isAdministrativePath(
            workspaceRoot: Path,
            path: Path,
        ): Boolean = path.normalize().startsWith(workspaceRoot.normalize().resolve(ADMINISTRATIVE_DIRECTORY))
    }
}
