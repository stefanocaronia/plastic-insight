package com.teamcomplex.plasticinsight.core

import java.time.OffsetDateTime
import java.util.Locale

enum class PlasticHistoryOrder {
    OLDEST_FIRST,
}

enum class PlasticHistoryEntryKind {
    CONTENT_REVISION,
    METADATA_EVENT,
}

enum class PlasticRevisionTypeKind {
    TEXT,
    BINARY,
    DIRECTORY,
    SYMBOLIC_LINK,
    UNKNOWN,
}

/** Plastic revision type with the original CLI value preserved. */
data class PlasticRevisionType(
    val raw: String,
) {
    val kind: PlasticRevisionTypeKind =
        when (raw.lowercase(Locale.ROOT)) {
            "txt" -> PlasticRevisionTypeKind.TEXT
            "bin" -> PlasticRevisionTypeKind.BINARY
            "dir" -> PlasticRevisionTypeKind.DIRECTORY
            "lnk" -> PlasticRevisionTypeKind.SYMBOLIC_LINK
            else -> PlasticRevisionTypeKind.UNKNOWN
        }

    init {
        require(raw.isNotBlank()) { "The Plastic revision type must not be blank." }
    }
}

enum class PlasticRevisionDataStatusKind {
    AVAILABLE,
    ARCHIVED,
    PURGED,
    UNKNOWN,
}

/** Revision availability with forward-compatible raw-value retention. */
data class PlasticRevisionDataStatus(
    val raw: String,
) {
    val kind: PlasticRevisionDataStatusKind =
        when (raw.lowercase(Locale.ROOT)) {
            "available" -> PlasticRevisionDataStatusKind.AVAILABLE
            "archived" -> PlasticRevisionDataStatusKind.ARCHIVED
            "purged" -> PlasticRevisionDataStatusKind.PURGED
            else -> PlasticRevisionDataStatusKind.UNKNOWN
        }

    init {
        require(raw.isNotBlank()) { "The Plastic data status must not be blank." }
    }
}

sealed interface PlasticHistoryChangeset {
    val displayValue: String

    data class Number(
        val value: Long,
    ) : PlasticHistoryChangeset {
        override val displayValue: String = value.toString()

        init {
            require(value >= 0) { "The history changeset must not be negative." }
        }
    }

    data object Checkout : PlasticHistoryChangeset {
        override val displayValue: String = "CO"
    }
}

data class PlasticHistoryRevision(
    val revisionSpec: String,
    val branch: String,
    val createdAt: OffsetDateTime,
    val entryKind: PlasticHistoryEntryKind,
    val revisionType: PlasticRevisionType?,
    val changeset: PlasticHistoryChangeset,
    val owner: String,
    val comment: String,
    val repository: String,
    val server: String,
    val dataStatus: PlasticRevisionDataStatus?,
    val itemPathOrSpec: String?,
    val itemId: Long?,
    val sizeBytes: Long,
    val hash: String?,
    val hashAlgorithm: String?,
) {
    init {
        require(revisionSpec.isNotBlank()) { "The history revision spec must not be blank." }
        require(owner.isNotBlank()) { "The history owner must not be blank." }
        require(repository.isNotBlank()) { "The history repository must not be blank." }
        require(server.isNotBlank()) { "The history server must not be blank." }
        require(sizeBytes >= 0) { "The history revision size must not be negative." }
        require((hash == null) == (hashAlgorithm == null)) {
            "The history hash and algorithm must either both be present or both be absent."
        }

        when (entryKind) {
            PlasticHistoryEntryKind.CONTENT_REVISION -> {
                require(revisionType != null) { "A content revision must have a revision type." }
                require(dataStatus != null) { "A content revision must have a data status." }
                require(!itemPathOrSpec.isNullOrBlank()) { "A content revision must have an item path." }
                require(itemId != null && itemId > 0) { "A content revision must have a positive item ID." }
            }

            PlasticHistoryEntryKind.METADATA_EVENT -> {
                require(revisionType == null && dataStatus == null) {
                    "A metadata event must not have revision data metadata."
                }
                require(itemPathOrSpec == null && itemId == null) {
                    "A metadata event must not identify content."
                }
                require(sizeBytes == 0L && hash == null && hashAlgorithm == null) {
                    "A metadata event must not contain revision bytes metadata."
                }
            }
        }
    }
}

/** One bounded CLI history response, in the order emitted by Plastic. */
data class PlasticFileHistory(
    val itemName: String?,
    val revisions: List<PlasticHistoryRevision>,
    val order: PlasticHistoryOrder,
) {
    init {
        require(itemName != null || revisions.isEmpty()) {
            "A non-empty history must identify its item."
        }
    }
}
