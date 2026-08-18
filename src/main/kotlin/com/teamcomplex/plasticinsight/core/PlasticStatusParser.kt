package com.teamcomplex.plasticinsight.core

/** Parses the framed machine-readable `status --includeRevId` contract. */
class PlasticStatusParser {
    fun parse(output: String): PlasticWorkspaceStatus {
        var outputEnd = output.length
        while (outputEnd > 0 && (output[outputEnd - 1] == '\r' || output[outputEnd - 1] == '\n')) {
            outputEnd--
        }
        if (outputEnd == 0) {
            throw PlasticParseException("Plastic status returned no records.")
        }

        var cursor = 0
        var header: Header? = null
        val changes = ArrayList<PlasticPendingChange>()
        while (cursor < outputEnd) {
            if (output[cursor] != RECORD_SEPARATOR) {
                throw PlasticParseException("Plastic status contains an unframed record.")
            }
            val recordEnd = output.indexOf(GROUP_SEPARATOR, cursor + 1)
            if (recordEnd < 0 || recordEnd >= outputEnd) {
                throw PlasticParseException("Plastic status contains an unterminated record.")
            }
            val record = output.substring(cursor + 1, recordEnd)
            if (header == null) {
                header = parseHeader(record)
            } else {
                changes.add(parseChange(record))
            }
            cursor = recordEnd + 1
        }

        val parsedHeader = header ?: throw PlasticParseException("Plastic status has no header.")
        return PlasticWorkspaceStatus(
            workspaceChangeset = parsedHeader.workspaceChangeset,
            repository = parsedHeader.repository,
            server = parsedHeader.server,
            changes = changes,
        )
    }

    private fun parseHeader(record: String): Header {
        val fields = record.split(FIELD_SEPARATOR)
        if (fields.size != HEADER_FIELD_COUNT || fields[0] != HEADER_MARKER) {
            throw PlasticParseException("Plastic status contains an invalid header.")
        }
        val workspaceChangeset = fields[1].toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw PlasticParseException("Plastic status contains an invalid workspace changeset.")
        if (fields[2].isBlank() || fields[3].isBlank()) {
            throw PlasticParseException("Plastic status contains incomplete repository information.")
        }
        return Header(workspaceChangeset, fields[2], fields[3])
    }

    private fun parseChange(record: String): PlasticPendingChange {
        val fields = record.split(FIELD_SEPARATOR)
        val codes = parseCodes(fields[0])
        val isMove = PlasticStatusCode.MOVED in codes || PlasticStatusCode.LOCALLY_MOVED in codes
        val expectedFieldCount = if (isMove) MOVE_FIELD_COUNT else CHANGE_FIELD_COUNT
        if (fields.size != expectedFieldCount) {
            throw PlasticParseException(
                "Plastic status change returned ${fields.size} fields; expected $expectedFieldCount.",
            )
        }

        val pathIndex = if (isMove) MOVE_PATH_INDEX else PATH_INDEX
        val directoryIndex = if (isMove) MOVE_DIRECTORY_INDEX else DIRECTORY_INDEX
        val revisionIndex = if (isMove) MOVE_REVISION_INDEX else REVISION_INDEX
        val path = parseAbsolutePlasticPath(fields[pathIndex], "pending-change path")
        val oldPath =
            if (isMove) parseAbsolutePlasticPath(fields[MOVE_OLD_PATH_INDEX], "old pending-change path") else null
        val isDirectory = parseBoolean(fields[directoryIndex])
        val revisionId = parseRevisionId(fields[revisionIndex])
        val similarity = if (isMove) parseSimilarity(fields[MOVE_SIMILARITY_INDEX]) else null

        return PlasticPendingChange(
            codes = codes,
            path = path,
            oldPath = oldPath,
            isDirectory = isDirectory,
            revisionId = revisionId,
            similarityPercent = similarity,
        )
    }

    private fun parseCodes(value: String): Set<PlasticStatusCode> {
        val codes = LinkedHashSet<PlasticStatusCode>()
        for (token in value.split('+')) {
            val code = PlasticStatusCode.fromToken(token)
                ?: throw PlasticParseException("Plastic status contains an unknown change code.")
            if (!codes.add(code)) {
                throw PlasticParseException("Plastic status contains a duplicate change code.")
            }
        }
        return codes
    }

    private fun parseBoolean(value: String): Boolean =
        when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> throw PlasticParseException("Plastic status contains an invalid directory flag.")
        }

    private fun parseRevisionId(value: String): Long? {
        val revisionId = value.toLongOrNull()
            ?: throw PlasticParseException("Plastic status contains an invalid revision ID.")
        if (revisionId < -1) {
            throw PlasticParseException("Plastic status contains an invalid revision ID.")
        }
        return revisionId.takeIf { it >= 0 }
    }

    private fun parseSimilarity(value: String): Double {
        val similarity = value.removeSuffix("%").toDoubleOrNull()
            ?: throw PlasticParseException("Plastic status contains invalid move similarity.")
        if (similarity !in 0.0..100.0) {
            throw PlasticParseException("Plastic status contains invalid move similarity.")
        }
        return similarity
    }

    private data class Header(
        val workspaceChangeset: Long,
        val repository: String,
        val server: String,
    )

    private companion object {
        const val RECORD_SEPARATOR = '\u001E'
        const val FIELD_SEPARATOR = '\u001F'
        const val GROUP_SEPARATOR = '\u001D'
        const val HEADER_MARKER = "STATUS"
        const val HEADER_FIELD_COUNT = 4
        const val CHANGE_FIELD_COUNT = 5
        const val MOVE_FIELD_COUNT = 7
        const val PATH_INDEX = 1
        const val DIRECTORY_INDEX = 2
        const val REVISION_INDEX = 3
        const val MOVE_SIMILARITY_INDEX = 1
        const val MOVE_OLD_PATH_INDEX = 2
        const val MOVE_PATH_INDEX = 3
        const val MOVE_DIRECTORY_INDEX = 4
        const val MOVE_REVISION_INDEX = 5
    }
}
