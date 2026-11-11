package com.teamcomplex.plasticinsight.core.history

import java.security.MessageDigest
import java.util.Base64

/** Server paths named by move events, in the order given, without duplicates and at most [MAX_FORMER_ITEM_PATHS]. */
internal fun List<PlasticHistoryRevision>.formerItemPaths(): List<String> {
    val paths = LinkedHashSet<String>()

    for (revision in this) {
        if (revision.entryKind != PlasticHistoryEntryKind.METADATA_EVENT) {
            continue
        }

        val match = MOVE_EVENT_PATTERN.matchEntire(revision.branch.trim()) ?: continue

        for (path in match.groupValues.drop(1)) {
            if (paths.size >= MAX_FORMER_ITEM_PATHS) {
                return paths.toList()
            }

            paths.add(path)
        }
    }

    return paths.toList()
}

/** True only when the bytes are proven to be this revision's content: same size and same MD5 as Plastic reported. */
internal fun PlasticHistoryRevision.hasContent(bytes: ByteArray): Boolean {
    val expectedHash = hash ?: return false

    if (bytes.size.toLong() != sizeBytes || !MD5_ALGORITHM.equals(hashAlgorithm, ignoreCase = true)) {
        return false
    }

    val digest = MessageDigest.getInstance(MD5_ALGORITHM).digest(bytes)
    return Base64.getEncoder().encodeToString(digest) == expectedHash
}

internal const val MAX_FORMER_ITEM_PATHS = 8

private const val MD5_ALGORITHM = "MD5"

/**
 * `<words> <old path> <one word> <new path>` with both paths absolute, matched without the localized words.
 * The old path ends at the first whitespace, word without `/`, and whitespace that are followed by `/`.
 */
private val MOVE_EVENT_PATTERN = Regex("""[^/]+?\s(/.*?)\s+[^\s/]+\s+(/.*)""")
