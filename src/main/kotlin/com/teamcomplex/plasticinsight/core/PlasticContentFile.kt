package com.teamcomplex.plasticinsight.core

import java.nio.file.Files
import java.nio.file.Path

/** Lends one `cm cat --file` call a file in a private temporary directory and removes both afterwards. */
internal inline fun <T> withTemporaryContentFile(action: (Path) -> T): T {
    val directory = Files.createTempDirectory(CONTENT_DIRECTORY_PREFIX)
    val file = directory.resolve(CONTENT_FILE_NAME)
    try {
        return action(file)
    } finally {
        runCatching { Files.deleteIfExists(file) }
        runCatching { Files.deleteIfExists(directory) }
    }
}

/** A file over the limit is reported as truncated without being loaded. */
internal fun readBoundedContentFile(
    file: Path,
    limitBytes: Int,
): BoundedOutputCapture {
    check(Files.isRegularFile(file)) { "Plastic reported success without writing the revision content." }
    val size = Files.size(file)
    if (size > limitBytes) {
        return BoundedOutputCapture(byteArrayOf(), size, truncated = true)
    }
    return Files.newInputStream(file).use { input -> captureBoundedOutput(input, limitBytes) }
}

private const val CONTENT_DIRECTORY_PREFIX = "plastic-insight-"
private const val CONTENT_FILE_NAME = "revision"
