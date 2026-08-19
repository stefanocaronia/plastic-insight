package com.teamcomplex.plasticinsight.core

import java.nio.file.Path

sealed interface PlasticWorkspaceLookup {
    data class Found(
        val workspace: PlasticWorkspace,
    ) : PlasticWorkspaceLookup

    data object NotFound : PlasticWorkspaceLookup
}

data class PlasticHistoryRequest(
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "The history page limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 999
    }
}

/** A bounded newest-first history view. `hasMore` never implies a native CLI cursor. */
data class PlasticHistoryPage(
    val revisions: List<PlasticHistoryRevision>,
    val hasMore: Boolean,
)

interface PlasticGateway : AutoCloseable {
    fun discoverWorkspace(
        directory: Path,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticResult<PlasticWorkspaceLookup>

    fun status(
        workspace: PlasticWorkspace,
        scope: Path,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticResult<PlasticWorkspaceStatus>

    fun baseContent(
        workspace: PlasticWorkspace,
        status: PlasticWorkspaceStatus,
        basePath: Path,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticResult<ByteArray>

    fun fileHistory(
        workspace: PlasticWorkspace,
        filePath: Path,
        request: PlasticHistoryRequest = PlasticHistoryRequest(),
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticResult<PlasticHistoryPage>

    fun revisionContent(
        revision: PlasticHistoryRevision,
        cancellation: PlasticCancellation = PlasticCancellation.NONE,
    ): PlasticResult<ByteArray>

    fun invalidateCaches()

    override fun close()
}

data class PlasticGatewayCacheLimits(
    val workspaceEntries: Int = 32,
    val workspaceBytes: Long = 256L * 1024,
    val historyEntries: Int = 16,
    val historyBytes: Long = 2L * 1024 * 1024,
    val contentEntries: Int = 16,
    val contentBytes: Long = 16L * 1024 * 1024,
    val maxCacheableContentBytes: Int = 4 * 1024 * 1024,
    val historicalPathEntries: Int = 128,
    val historicalPathBytes: Long = 256L * 1024,
) {
    init {
        require(workspaceEntries > 0 && workspaceBytes > 0) { "Workspace cache limits must be positive." }
        require(historyEntries > 0 && historyBytes > 0) { "History cache limits must be positive." }
        require(contentEntries > 0 && contentBytes > 0) { "Content cache limits must be positive." }
        require(maxCacheableContentBytes > 0 && maxCacheableContentBytes <= contentBytes) {
            "The per-content cache limit must be positive and fit the content cache."
        }
        require(historicalPathEntries > 0 && historicalPathBytes > 0) {
            "Historical-path cache limits must be positive."
        }
    }
}
