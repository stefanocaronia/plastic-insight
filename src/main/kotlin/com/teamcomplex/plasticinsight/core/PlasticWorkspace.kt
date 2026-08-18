package com.teamcomplex.plasticinsight.core

import java.nio.file.Path
import java.util.UUID

/** Immutable workspace metadata returned by Plastic discovery. */
data class PlasticWorkspace(
    val name: String,
    val root: Path,
    val machine: String,
    val id: UUID,
    val workspaceType: String,
    val isDynamic: Boolean,
) {
    init {
        require(name.isNotBlank()) { "The workspace name must not be blank." }
        require(root.isAbsolute) { "The workspace root must be absolute." }
        require(machine.isNotBlank()) { "The workspace machine must not be blank." }
        require(workspaceType.isNotBlank()) { "The workspace type must not be blank." }
    }
}
