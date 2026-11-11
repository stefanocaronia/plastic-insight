package com.teamcomplex.plasticinsight.core

import com.teamcomplex.plasticinsight.core.history.PlasticHistoryRevision
import com.teamcomplex.plasticinsight.core.workspace.PlasticWorkspace
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** The gateway's bounded caches; a store is dropped once the caches were invalidated or closed after its generation was read. */
internal class GatewayCaches(
    private val limits: PlasticGatewayCacheLimits,
) {
    private val currentGeneration = AtomicLong()
    private val lifecycleLock = Any()
    private var closed = false

    val workspaces = BoundedLruCache<Path, PlasticWorkspace>(
        maxEntries = limits.workspaceEntries,
        maxWeight = limits.workspaceBytes,
        weigh = ::workspaceWeight,
    )
    val histories = BoundedLruCache<HistoryKey, CachedHistory>(
        maxEntries = limits.historyEntries,
        maxWeight = limits.historyBytes,
        weigh = ::historyWeight,
    )
    val contents = BoundedLruCache<ContentKey, ByteArray>(
        maxEntries = limits.contentEntries,
        maxWeight = limits.contentBytes,
        weigh = { key, bytes -> contentKeyWeight(key) + bytes.size },
    )
    val historicalPaths = BoundedLruCache<RevisionKey, String>(
        maxEntries = limits.historicalPathEntries,
        maxWeight = limits.historicalPathBytes,
        weigh = { key, path -> revisionKeyWeight(key) + textWeight(path) },
    )

    fun generation(): Long = currentGeneration.get()

    fun invalidate() {
        synchronized(lifecycleLock) {
            currentGeneration.incrementAndGet()
            clearAll()
        }
    }

    fun close() {
        synchronized(lifecycleLock) {
            closed = true
            currentGeneration.incrementAndGet()
            clearAll()
        }
    }

    fun storeIfCurrent(
        generation: Long,
        store: () -> Unit,
    ) {
        synchronized(lifecycleLock) {
            if (!closed && currentGeneration.get() == generation) {
                store()
            }
        }
    }

    fun storeContent(
        key: ContentKey,
        bytes: ByteArray,
        generation: Long,
    ) {
        if (bytes.size <= limits.maxCacheableContentBytes) {
            storeIfCurrent(generation) { contents.put(key, bytes.copyOf()) }
        }
    }

    private fun clearAll() {
        workspaces.clear()
        histories.clear()
        contents.clear()
        historicalPaths.clear()
    }

    private fun workspaceWeight(
        key: Path,
        workspace: PlasticWorkspace,
    ): Long = 128L + textWeight(key.toString(), workspace.name, workspace.root.toString(), workspace.machine, workspace.workspaceType)

    private fun historyWeight(
        key: HistoryKey,
        cached: CachedHistory,
    ): Long = 128L + textWeight(key.path, cached.query.cacheText) + cached.page.revisions.sumOf(::historyRevisionWeight)

    private fun historyRevisionWeight(revision: PlasticHistoryRevision): Long =
        256L + textWeight(
            revision.revisionSpec,
            revision.branch,
            revision.owner,
            revision.comment,
            revision.repository,
            revision.server,
            revision.itemPathOrSpec,
            revision.hash,
            revision.hashAlgorithm,
        )

    private fun contentKeyWeight(key: ContentKey): Long =
        when (key) {
            is ContentKey.Baseline -> 128L + textWeight(key.repository, key.server, key.path)
            is ContentKey.Revision -> revisionKeyWeight(key.key)
        }

    private fun revisionKeyWeight(key: RevisionKey): Long = 96L + textWeight(key.repository, key.server)

    private fun textWeight(vararg values: String?): Long = values.sumOf { value -> (value?.length ?: 0).toLong() * Char.SIZE_BYTES }
}

internal data class HistoryKey(
    val workspaceId: UUID,
    val path: String,
    val generation: Long,
)

internal data class CachedHistory(
    val page: PlasticHistoryPage,
    val query: HistoryQuery,
)

internal data class RevisionKey(
    val repository: String,
    val server: String,
    val itemId: Long,
    val changeset: Long,
)

internal sealed interface ContentKey {
    data class Baseline(
        val workspaceId: UUID,
        val repository: String,
        val server: String,
        val changeset: Long,
        val path: String,
    ) : ContentKey

    data class Revision(
        val key: RevisionKey,
    ) : ContentKey
}
