package com.teamcomplex.plasticinsight.core

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Parses the bounded `cm history --xml` response without materializing a DOM. */
class PlasticFileHistoryXmlParser(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "The maximum history entry count must be positive." }
    }

    fun parse(
        output: ByteArray,
        cancellationCheck: () -> Unit = {},
    ): PlasticFileHistory {
        PlasticXmlCursor.open(output, cancellationCheck).use { cursor ->
            cursor.moveToRoot(ROOT)
            var history: PlasticFileHistory? = null
            var historiesContainerSeen = false
            cursor.readChildren(ROOT) { child ->
                when (child) {
                    HISTORIES -> {
                        if (historiesContainerSeen) {
                            throw PlasticParseException("Plastic history contains duplicate histories containers.")
                        }
                        historiesContainerSeen = true
                        history = parseHistories(cursor)
                    }

                    else -> cursor.skipElement()
                }
            }
            cursor.requireDocumentEnd()
            return history ?: emptyHistory()
        }
    }

    private fun parseHistories(cursor: PlasticXmlCursor): PlasticFileHistory? {
        var history: PlasticFileHistory? = null
        cursor.readChildren(HISTORIES) { child ->
            when (child) {
                HISTORY -> {
                    if (history != null) {
                        throw PlasticParseException("Plastic history returned more than one requested item.")
                    }
                    history = parseHistory(cursor)
                }

                else -> cursor.skipElement()
            }
        }
        return history
    }

    private fun parseHistory(cursor: PlasticXmlCursor): PlasticFileHistory {
        var itemName: String? = null
        var revisions: List<PlasticHistoryRevision>? = null
        cursor.readChildren(HISTORY) { child ->
            when (child) {
                ITEM_NAME -> {
                    if (itemName != null) {
                        throw PlasticParseException("Plastic history contains a duplicate item name.")
                    }
                    itemName = cursor.readText(ITEM_NAME).requireNotBlank(ITEM_NAME)
                }

                REVISIONS -> {
                    if (revisions != null) {
                        throw PlasticParseException("Plastic history contains duplicate revision containers.")
                    }
                    revisions = parseRevisions(cursor)
                }

                else -> cursor.skipElement()
            }
        }

        return PlasticFileHistory(
            itemName = itemName ?: throw PlasticParseException("Plastic history has no item name."),
            revisions = revisions ?: throw PlasticParseException("Plastic history has no revisions container."),
            order = PlasticHistoryOrder.OLDEST_FIRST,
        )
    }

    private fun parseRevisions(cursor: PlasticXmlCursor): List<PlasticHistoryRevision> {
        val revisions = ArrayList<PlasticHistoryRevision>()
        cursor.readChildren(REVISIONS) { child ->
            when (child) {
                REVISION -> {
                    if (revisions.size >= maxEntries) {
                        throw PlasticParseException("Plastic history exceeds the configured entry limit.")
                    }
                    revisions.add(parseRevision(cursor))
                }

                else -> cursor.skipElement()
            }
        }
        return revisions
    }

    private fun parseRevision(cursor: PlasticXmlCursor): PlasticHistoryRevision {
        val fields = LinkedHashMap<String, String>()
        var repositorySpec: RepositorySpecFields? = null
        cursor.readChildren(REVISION) { child ->
            when (child) {
                REPOSITORY_SPEC -> {
                    if (repositorySpec != null) {
                        throw PlasticParseException("Plastic history contains a duplicate repository spec.")
                    }
                    repositorySpec = parseRepositorySpec(cursor)
                }

                in REVISION_TEXT_FIELDS -> fields.putUnique(child, cursor.readText(child))
                else -> cursor.skipElement()
            }
        }

        val repository = fields.requiredNonBlank(REPOSITORY)
        val server = fields.requiredNonBlank(SERVER)
        val parsedRepositorySpec = repositorySpec
            ?: throw PlasticParseException("Plastic history has no repository spec.")
        if (parsedRepositorySpec.name != repository || parsedRepositorySpec.server != server) {
            throw PlasticParseException("Plastic history contains inconsistent repository information.")
        }

        val revisionTypeValue = fields.required(REVISION_TYPE).trim()
        val dataStatusValue = fields.required(DATA_STATUS).trim()
        if (revisionTypeValue.isEmpty() != dataStatusValue.isEmpty()) {
            throw PlasticParseException("Plastic history contains incomplete revision metadata.")
        }
        val isMetadataEvent = revisionTypeValue.isEmpty()
        val size = fields.requiredLong(SIZE, minimum = 0)
        val itemPath = fields.required(ITEM_PATH_OR_SPEC).takeUnless(String::isBlank)
        val itemId = fields.required(ITEM_ID).trim().takeUnless(String::isEmpty)?.parseLong(ITEM_ID, minimum = 1)
        val hash = fields.required(HASH).trim().takeUnless(String::isEmpty)
        val hashAlgorithm = fields.required(HASH_ALGORITHM).trim().takeUnless(String::isEmpty)

        if (isMetadataEvent && (itemPath != null || itemId != null || size != 0L || hash != null || hashAlgorithm != null)) {
            throw PlasticParseException("Plastic history metadata event unexpectedly contains revision bytes metadata.")
        }

        return try {
            PlasticHistoryRevision(
                revisionSpec = fields.requiredNonBlank(REVISION_SPEC),
                branch = fields.required(BRANCH),
                createdAt = fields.requiredOffsetDateTime(CREATION_DATE),
                entryKind = if (isMetadataEvent) {
                    PlasticHistoryEntryKind.METADATA_EVENT
                } else {
                    PlasticHistoryEntryKind.CONTENT_REVISION
                },
                revisionType = revisionTypeValue.takeUnless(String::isEmpty)?.let(::PlasticRevisionType),
                changeset = fields.requiredChangeset(CHANGELIST_NUMBER),
                owner = fields.requiredNonBlank(OWNER),
                comment = fields.required(COMMENT),
                repository = repository,
                server = server,
                dataStatus = dataStatusValue.takeUnless(String::isEmpty)?.let(::PlasticRevisionDataStatus),
                itemPathOrSpec = itemPath,
                itemId = itemId,
                sizeBytes = size,
                hash = hash,
                hashAlgorithm = hashAlgorithm,
            )
        } catch (exception: IllegalArgumentException) {
            throw PlasticParseException("Plastic history contains invalid revision metadata.", exception)
        }
    }

    private fun parseRepositorySpec(cursor: PlasticXmlCursor): RepositorySpecFields {
        val fields = LinkedHashMap<String, String>()
        cursor.readChildren(REPOSITORY_SPEC) { child ->
            when (child) {
                SERVER,
                NAME,
                -> fields.putUnique(child, cursor.readText(child))

                else -> cursor.skipElement()
            }
        }
        return RepositorySpecFields(
            server = fields.requiredNonBlank(SERVER),
            name = fields.requiredNonBlank(NAME),
        )
    }

    private fun emptyHistory(): PlasticFileHistory =
        PlasticFileHistory(
            itemName = null,
            revisions = emptyList(),
            order = PlasticHistoryOrder.OLDEST_FIRST,
        )

    private fun MutableMap<String, String>.putUnique(name: String, value: String) {
        if (put(name, value) != null) {
            throw PlasticParseException("Plastic history contains a duplicate $name field.")
        }
    }

    private fun Map<String, String>.required(name: String): String =
        this[name] ?: throw PlasticParseException("Plastic history is missing the $name field.")

    private fun Map<String, String>.requiredNonBlank(name: String): String = required(name).requireNotBlank(name)

    private fun Map<String, String>.requiredLong(
        name: String,
        minimum: Long,
    ): Long = required(name).trim().parseLong(name, minimum)

    private fun Map<String, String>.requiredOffsetDateTime(name: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(required(name).trim())
        } catch (exception: DateTimeParseException) {
            throw PlasticParseException("Plastic history contains an invalid $name field.", exception)
        }

    private fun Map<String, String>.requiredChangeset(name: String): PlasticHistoryChangeset {
        val value = required(name).trim()
        if (value == CHECKOUT_CHANGESET) {
            return PlasticHistoryChangeset.Checkout
        }
        return PlasticHistoryChangeset.Number(value.parseLong(name, minimum = 0))
    }

    private fun String.requireNotBlank(name: String): String =
        if (isBlank()) {
            throw PlasticParseException("Plastic history contains a blank $name field.")
        } else {
            this
        }

    private fun String.parseLong(
        name: String,
        minimum: Long,
    ): Long {
        val result = toLongOrNull()
            ?: throw PlasticParseException("Plastic history contains an invalid $name field.")
        if (result < minimum) {
            throw PlasticParseException("Plastic history contains an invalid $name field.")
        }
        return result
    }

    private data class RepositorySpecFields(
        val server: String,
        val name: String,
    )

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 1000
        const val CHECKOUT_CHANGESET = "CO"
        const val ROOT = "RevisionHistoriesResult"
        const val HISTORIES = "RevisionHistories"
        const val HISTORY = "RevisionHistory"
        const val ITEM_NAME = "ItemName"
        const val REVISIONS = "Revisions"
        const val REVISION = "Revision"
        const val REVISION_SPEC = "RevisionSpec"
        const val BRANCH = "Branch"
        const val CREATION_DATE = "CreationDate"
        const val REVISION_TYPE = "RevisionType"
        const val CHANGELIST_NUMBER = "ChangesetNumber"
        const val OWNER = "Owner"
        const val COMMENT = "Comment"
        const val REPOSITORY = "Repository"
        const val SERVER = "Server"
        const val REPOSITORY_SPEC = "RepositorySpec"
        const val NAME = "Name"
        const val DATA_STATUS = "DataStatus"
        const val ITEM_PATH_OR_SPEC = "ItemPathOrSpec"
        const val ITEM_ID = "ItemId"
        const val SIZE = "Size"
        const val HASH = "Hash"
        const val HASH_ALGORITHM = "HashAlgorithm"

        val REVISION_TEXT_FIELDS = setOf(
            REVISION_SPEC,
            BRANCH,
            CREATION_DATE,
            REVISION_TYPE,
            CHANGELIST_NUMBER,
            OWNER,
            COMMENT,
            REPOSITORY,
            SERVER,
            DATA_STATUS,
            ITEM_PATH_OR_SPEC,
            ITEM_ID,
            SIZE,
            HASH,
            HASH_ALGORITHM,
        )
    }
}
