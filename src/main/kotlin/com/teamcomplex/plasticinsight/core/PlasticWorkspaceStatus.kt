package com.teamcomplex.plasticinsight.core

import java.nio.file.Path

enum class PlasticStatusCode(
    val token: String,
) {
    CHECKED_OUT("CO"),
    CHANGED("CH"),
    COPIED("CP"),
    REPLACED("RP"),
    ADDED("AD"),
    DELETED("DE"),
    LOCALLY_DELETED("LD"),
    MOVED("MV"),
    LOCALLY_MOVED("LM"),
    PRIVATE("PR"),
    IGNORED("IG"),
    ;

    companion object {
        private val byToken = entries.associateBy(PlasticStatusCode::token)

        internal fun fromToken(token: String): PlasticStatusCode? = byToken[token]
    }
}

data class PlasticPendingChange(
    val codes: Set<PlasticStatusCode>,
    val path: Path,
    val oldPath: Path?,
    val isDirectory: Boolean,
    val revisionId: Long?,
    val similarityPercent: Double?,
) {
    val isMove: Boolean
        get() = PlasticStatusCode.MOVED in codes || PlasticStatusCode.LOCALLY_MOVED in codes

    init {
        require(codes.isNotEmpty()) { "A pending change must contain at least one status code." }
        require(path.isAbsolute) { "The pending-change path must be absolute." }
        require(revisionId == null || revisionId >= 0) { "The revision ID must not be negative." }
        require(isMove == (oldPath != null)) { "Only move records may contain an old path." }
        require(isMove == (similarityPercent != null)) { "Only move records may contain similarity." }
        require(oldPath == null || oldPath.isAbsolute) { "The old pending-change path must be absolute." }
        require(similarityPercent == null || similarityPercent in 0.0..100.0) {
            "Move similarity must be between zero and one hundred."
        }
    }
}

data class PlasticWorkspaceStatus(
    val workspaceChangeset: Long,
    val repository: String,
    val server: String,
    val changes: List<PlasticPendingChange>,
) {
    init {
        require(workspaceChangeset >= 0) { "The workspace changeset must not be negative." }
        require(repository.isNotBlank()) { "The status repository must not be blank." }
        require(server.isNotBlank()) { "The status server must not be blank." }
    }
}
