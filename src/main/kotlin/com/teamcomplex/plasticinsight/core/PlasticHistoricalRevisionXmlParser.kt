package com.teamcomplex.plasticinsight.core

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/** Parses the exact historical-path `cm find revision --xml` response. */
class PlasticHistoricalRevisionXmlParser(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries >= 2) { "The maximum lookup entry count must allow ambiguity detection." }
    }

    fun parse(
        output: ByteArray,
        cancellationCheck: () -> Unit = {},
    ): PlasticHistoricalRevisionLookup {
        val revisions = ArrayList<PlasticHistoricalRevision>()
        PlasticXmlCursor.open(output, cancellationCheck).use { cursor ->
            cursor.moveToRoot(ROOT)
            cursor.readChildren(ROOT) { child ->
                when (child) {
                    REVISION -> {
                        if (revisions.size >= maxEntries) {
                            throw PlasticParseException("Plastic revision lookup exceeds the configured entry limit.")
                        }
                        revisions.add(parseRevision(cursor))
                    }

                    else -> cursor.skipElement()
                }
            }
            cursor.requireDocumentEnd()
        }

        return when (revisions.size) {
            0 -> PlasticHistoricalRevisionLookup.NotFound
            1 -> PlasticHistoricalRevisionLookup.Found(revisions.single())
            else -> PlasticHistoricalRevisionLookup.Ambiguous(revisions)
        }
    }

    private fun parseRevision(cursor: PlasticXmlCursor): PlasticHistoricalRevision {
        val fields = LinkedHashMap<String, String>()
        cursor.readChildren(REVISION) { child ->
            when (child) {
                in REVISION_FIELDS -> fields.putUnique(child, cursor.readText(child))
                else -> cursor.skipElement()
            }
        }

        val parentValue = fields.requiredLong(PARENT, minimum = -1)
        if (parentValue < -1) {
            throw PlasticParseException("Plastic revision lookup contains an invalid parent revision ID.")
        }
        val hash = fields.required(HASH).trim().takeUnless(String::isEmpty)
        val hashAlgorithm = fields.required(HASH_ALGORITHM).trim().takeUnless(String::isEmpty)

        return try {
            PlasticHistoricalRevision(
                revisionId = fields.requiredLong(ID, minimum = 1),
                comment = fields.required(COMMENT),
                createdAt = fields.requiredOffsetDateTime(DATE),
                owner = fields.requiredNonBlank(OWNER),
                revisionType = PlasticRevisionType(fields.requiredNonBlank(TYPE).trim()),
                sizeBytes = fields.requiredLong(SIZE, minimum = 0),
                changeset = fields.requiredLong(CHANGESET, minimum = 0),
                parentRevisionId = parentValue.takeIf { it >= 0 },
                item = fields.requiredNonBlank(ITEM),
                itemId = fields.requiredLong(ITEM_ID, minimum = 1),
                branch = fields.requiredNonBlank(BRANCH),
                path = fields.requiredNonBlank(PATH),
                repository = fields.requiredNonBlank(REPOSITORY),
                repositoryName = fields.requiredNonBlank(REPOSITORY_NAME),
                server = fields.requiredNonBlank(REPOSITORY_SERVER),
                hash = hash,
                hashAlgorithm = hashAlgorithm,
                guid = fields.requiredUuid(GUID),
            )
        } catch (exception: IllegalArgumentException) {
            throw PlasticParseException("Plastic revision lookup contains invalid metadata.", exception)
        }
    }

    private fun MutableMap<String, String>.putUnique(name: String, value: String) {
        if (put(name, value) != null) {
            throw PlasticParseException("Plastic revision lookup contains a duplicate $name field.")
        }
    }

    private fun Map<String, String>.required(name: String): String =
        this[name] ?: throw PlasticParseException("Plastic revision lookup is missing the $name field.")

    private fun Map<String, String>.requiredNonBlank(name: String): String {
        val value = required(name)
        if (value.isBlank()) {
            throw PlasticParseException("Plastic revision lookup contains a blank $name field.")
        }
        return value
    }

    private fun Map<String, String>.requiredLong(
        name: String,
        minimum: Long,
    ): Long {
        val result = required(name).trim().toLongOrNull()
            ?: throw PlasticParseException("Plastic revision lookup contains an invalid $name field.")
        if (result < minimum) {
            throw PlasticParseException("Plastic revision lookup contains an invalid $name field.")
        }
        return result
    }

    private fun Map<String, String>.requiredOffsetDateTime(name: String): OffsetDateTime =
        try {
            OffsetDateTime.parse(required(name).trim())
        } catch (exception: DateTimeParseException) {
            throw PlasticParseException("Plastic revision lookup contains an invalid $name field.", exception)
        }

    private fun Map<String, String>.requiredUuid(name: String): UUID =
        try {
            UUID.fromString(required(name).trim())
        } catch (exception: IllegalArgumentException) {
            throw PlasticParseException("Plastic revision lookup contains an invalid $name field.", exception)
        }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 16
        const val ROOT = "PLASTICQUERY"
        const val REVISION = "REVISION"
        const val ID = "ID"
        const val COMMENT = "COMMENT"
        const val DATE = "DATE"
        const val OWNER = "OWNER"
        const val TYPE = "TYPE"
        const val SIZE = "SIZE"
        const val CHANGESET = "CHANGESET"
        const val PARENT = "PARENT"
        const val ITEM = "ITEM"
        const val ITEM_ID = "ITEMID"
        const val BRANCH = "BRANCH"
        const val PATH = "PATH"
        const val REPOSITORY = "REPOSITORY"
        const val REPOSITORY_NAME = "REPNAME"
        const val REPOSITORY_SERVER = "REPSERVER"
        const val HASH = "HASH"
        const val HASH_ALGORITHM = "HASHALGORITHM"
        const val GUID = "GUID"

        val REVISION_FIELDS = setOf(
            ID,
            COMMENT,
            DATE,
            OWNER,
            TYPE,
            SIZE,
            CHANGESET,
            PARENT,
            ITEM,
            ITEM_ID,
            BRANCH,
            PATH,
            REPOSITORY,
            REPOSITORY_NAME,
            REPOSITORY_SERVER,
            HASH,
            HASH_ALGORITHM,
            GUID,
        )
    }
}
