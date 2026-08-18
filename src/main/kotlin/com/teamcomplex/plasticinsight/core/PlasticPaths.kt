package com.teamcomplex.plasticinsight.core

import java.nio.file.InvalidPathException
import java.nio.file.Path

internal fun parseAbsolutePlasticPath(
    value: String,
    fieldDescription: String,
): Path {
    val normalizedValue =
        if (value.length >= 2 && value[0].isLetter() && value[1] == ':') {
            value[0].uppercaseChar() + value.substring(1)
        } else {
            value
        }
    val path = try {
        Path.of(normalizedValue).normalize()
    } catch (exception: InvalidPathException) {
        throw PlasticParseException("Plastic output contains an invalid $fieldDescription.", exception)
    }
    if (!path.isAbsolute) {
        throw PlasticParseException("Plastic output contains a relative $fieldDescription.")
    }
    return path
}
