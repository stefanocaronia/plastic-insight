package com.teamcomplex.plasticinsight.core

import java.time.OffsetDateTime
import java.util.UUID

data class PlasticHistoricalRevision(
    val revisionId: Long,
    val comment: String,
    val createdAt: OffsetDateTime,
    val owner: String,
    val revisionType: PlasticRevisionType,
    val sizeBytes: Long,
    val changeset: Long,
    val parentRevisionId: Long?,
    val item: String,
    val itemId: Long,
    val branch: String,
    val path: String,
    val repository: String,
    val repositoryName: String,
    val server: String,
    val hash: String?,
    val hashAlgorithm: String?,
    val guid: UUID,
) {
    init {
        require(revisionId > 0) { "The historical revision ID must be positive." }
        require(owner.isNotBlank()) { "The historical revision owner must not be blank." }
        require(sizeBytes >= 0) { "The historical revision size must not be negative." }
        require(changeset >= 0) { "The historical changeset must not be negative." }
        require(parentRevisionId == null || parentRevisionId > 0) {
            "The historical parent revision ID must be positive."
        }
        require(item.isNotBlank()) { "The historical item must not be blank." }
        require(itemId > 0) { "The historical item ID must be positive." }
        require(branch.isNotBlank()) { "The historical branch must not be blank." }
        require(path.isNotBlank()) { "The historical path must not be blank." }
        require(repository.isNotBlank()) { "The historical repository must not be blank." }
        require(repositoryName.isNotBlank()) { "The historical repository name must not be blank." }
        require(server.isNotBlank()) { "The historical server must not be blank." }
        require((hash == null) == (hashAlgorithm == null)) {
            "The historical hash and algorithm must either both be present or both be absent."
        }
    }
}

sealed interface PlasticHistoricalRevisionLookup {
    data object NotFound : PlasticHistoricalRevisionLookup

    data class Found(
        val revision: PlasticHistoricalRevision,
    ) : PlasticHistoricalRevisionLookup

    data class Ambiguous(
        val revisions: List<PlasticHistoricalRevision>,
    ) : PlasticHistoricalRevisionLookup {
        init {
            require(revisions.size >= 2) { "An ambiguous lookup must contain at least two revisions." }
        }
    }
}
