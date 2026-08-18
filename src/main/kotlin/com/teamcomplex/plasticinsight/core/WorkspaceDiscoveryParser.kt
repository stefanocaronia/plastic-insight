package com.teamcomplex.plasticinsight.core

import java.util.UUID

/** Parses the six-field `getworkspacefrompath` contract. */
class WorkspaceDiscoveryParser {
    fun parse(output: String): PlasticWorkspace {
        val record = output.trimEnd('\r', '\n')
        if (record.isEmpty()) {
            throw PlasticParseException("Workspace discovery returned no fields.")
        }
        if (record.any { it == '\r' || it == '\n' }) {
            throw PlasticParseException("Workspace discovery returned more than one record.")
        }

        val fields = record.split(FIELD_SEPARATOR)
        if (fields.size != FIELD_COUNT) {
            throw PlasticParseException("Workspace discovery returned ${fields.size} fields; expected $FIELD_COUNT.")
        }

        val root = parseAbsolutePlasticPath(fields[ROOT_INDEX], "workspace root path")
        val id = parseId(fields[ID_INDEX])
        val isDynamic = parseDynamicMode(fields[DYNAMIC_INDEX])

        return try {
            PlasticWorkspace(
                name = fields[NAME_INDEX],
                root = root,
                machine = fields[MACHINE_INDEX],
                id = id,
                workspaceType = fields[TYPE_INDEX],
                isDynamic = isDynamic,
            )
        } catch (exception: IllegalArgumentException) {
            throw PlasticParseException("Workspace discovery contains a blank or invalid field.", exception)
        }
    }

    private fun parseId(value: String): UUID =
        try {
            UUID.fromString(value)
        } catch (exception: IllegalArgumentException) {
            throw PlasticParseException("Workspace discovery contains an invalid workspace ID.", exception)
        }

    private fun parseDynamicMode(value: String): Boolean =
        when {
            value.equals(STATIC_MODE, ignoreCase = true) -> false
            value.equals(DYNAMIC_MODE, ignoreCase = true) -> true
            else -> throw PlasticParseException("Workspace discovery contains an unknown workspace mode.")
        }

    private companion object {
        const val FIELD_SEPARATOR = '\u001F'
        const val FIELD_COUNT = 6
        const val NAME_INDEX = 0
        const val ROOT_INDEX = 1
        const val MACHINE_INDEX = 2
        const val ID_INDEX = 3
        const val TYPE_INDEX = 4
        const val DYNAMIC_INDEX = 5
        const val STATIC_MODE = "static"
        const val DYNAMIC_MODE = "dynamic"
    }
}
