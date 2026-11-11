package com.teamcomplex.plasticinsight.core

import com.teamcomplex.plasticinsight.core.cli.PlasticBinaryResult
import com.teamcomplex.plasticinsight.core.cli.PlasticCli
import com.teamcomplex.plasticinsight.core.cli.PlasticCommandResult
import com.teamcomplex.plasticinsight.core.cli.PlasticTextResult
import com.teamcomplex.plasticinsight.core.history.PlasticFileHistoryXmlParser
import com.teamcomplex.plasticinsight.core.history.PlasticHistoricalRevisionLookup
import com.teamcomplex.plasticinsight.core.history.PlasticHistoricalRevisionXmlParser
import com.teamcomplex.plasticinsight.core.history.PlasticHistoryChangeset
import com.teamcomplex.plasticinsight.core.history.PlasticHistoryEntryKind
import com.teamcomplex.plasticinsight.core.history.PlasticHistoryRevision
import com.teamcomplex.plasticinsight.core.history.PlasticRevisionDataStatusKind
import com.teamcomplex.plasticinsight.core.history.formerItemPaths
import com.teamcomplex.plasticinsight.core.history.hasContent
import com.teamcomplex.plasticinsight.core.parsing.PlasticParseException
import com.teamcomplex.plasticinsight.core.parsing.parseAbsolutePlasticPath
import com.teamcomplex.plasticinsight.core.process.PlasticCancellation
import com.teamcomplex.plasticinsight.core.status.PlasticStatusParser
import com.teamcomplex.plasticinsight.core.status.PlasticWorkspaceStatus
import com.teamcomplex.plasticinsight.core.workspace.PlasticWorkspace
import com.teamcomplex.plasticinsight.core.workspace.PlasticWorkspaceLocator
import com.teamcomplex.plasticinsight.core.workspace.WorkspaceDiscoveryParser
import java.io.IOException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource

/** Synchronous core gateway; Rider owns background scheduling. */
internal class DefaultPlasticGateway(
    private val cli: PlasticCli,
    private val historicalLookupDirectory: Path,
    private val workspaceLocator: PlasticWorkspaceLocator = PlasticWorkspaceLocator(),
    private val cacheLimits: PlasticGatewayCacheLimits = PlasticGatewayCacheLimits(),
    private val diagnosticSink: PlasticDiagnosticSink = PlasticDiagnosticSink.NONE,
    private val workspaceParser: WorkspaceDiscoveryParser = WorkspaceDiscoveryParser(),
    private val statusParser: PlasticStatusParser = PlasticStatusParser(),
    private val historyParser: PlasticFileHistoryXmlParser = PlasticFileHistoryXmlParser(),
    private val historicalRevisionParser: PlasticHistoricalRevisionXmlParser = PlasticHistoricalRevisionXmlParser(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : PlasticGateway {
    private val disposed = AtomicBoolean()
    private val results = GatewayResults(diagnosticSink) { disposed.get() }
    private val caches = GatewayCaches(cacheLimits)

    init {
        require(historicalLookupDirectory.isAbsolute) { "The historical lookup directory must be absolute." }
    }

    override fun discoverWorkspace(
        directory: Path,
        cancellation: PlasticCancellation,
    ): PlasticResult<PlasticWorkspaceLookup> {
        val operation = PlasticOperation.WORKSPACE_DISCOVERY
        require(directory.isAbsolute) { "The workspace lookup directory must be absolute." }
        precheck(operation, cancellation)?.let { return it }

        val root = workspaceLocator.findRoot(directory.normalize())
            ?: return results.success(
                operation = operation,
                origin = PlasticDiagnosticOrigin.PRECHECK,
                outcome = PlasticDiagnosticOutcome.NOT_FOUND,
                value = PlasticWorkspaceLookup.NotFound,
            )
        val generation = caches.generation()
        caches.workspaces[root]?.let { workspace ->
            return results.success(
                operation = operation,
                origin = PlasticDiagnosticOrigin.CACHE,
                value = PlasticWorkspaceLookup.Found(workspace),
            )
        }

        val token = combinedCancellation(cancellation)
        val result = runCommand(operation, onFailure = { return it }) {
            cli.workspaceFromPath(root, root, token)
        }
        results.processFailure(operation, result)?.let { return it }

        val workspace = parseOutput(operation, result, onFailure = { return it }) {
            checkParsingCancellation(token)
            workspaceParser.parse(result.standardOutput).also { parsed ->
                if (parsed.root.normalize() != root) {
                    throw PlasticParseException("Plastic workspace discovery returned an inconsistent root.")
                }
            }
        }

        if (token.isCancellationRequested()) {
            return results.cancellationFailure(operation, result)
        }

        caches.storeIfCurrent(generation) { caches.workspaces.put(root, workspace) }
        return results.processSuccess(
            operation = operation,
            result = result,
            value = PlasticWorkspaceLookup.Found(workspace),
        )
    }

    override fun status(
        workspace: PlasticWorkspace,
        scope: Path,
        cancellation: PlasticCancellation,
        includePrivateFiles: Boolean,
    ): PlasticResult<PlasticWorkspaceStatus> {
        val operation = PlasticOperation.STATUS
        precheck(operation, cancellation)?.let { return it }
        val normalizedRoot = workspace.root.normalize()
        val normalizedScope = scope.normalize()
        val token = combinedCancellation(cancellation)
        val result = runCommand(operation, onFailure = { return it }) {
            if (normalizedScope == normalizedRoot) {
                cli.workspaceStatus(normalizedRoot, token, includePrivateFiles)
            } else {
                cli.constrainedStatus(normalizedRoot, normalizedScope, token, includePrivateFiles)
            }
        }
        results.processFailure(operation, result)?.let { return it }

        val status = parseOutput(operation, result, onFailure = { return it }) {
            checkParsingCancellation(token)
            statusParser.parse(result.standardOutput) { checkParsingCancellation(token) }.also { parsed ->
                val root = workspace.root.normalize()

                if (parsed.changes.any { change ->
                        !change.path.normalize().startsWith(root) ||
                            (change.oldPath != null && !change.oldPath.normalize().startsWith(root))
                    }
                ) {
                    throw PlasticParseException("Plastic status returned a path outside the workspace.")
                }
            }
        }

        if (token.isCancellationRequested()) {
            return results.cancellationFailure(operation, result)
        }

        return results.processSuccess(operation, result, status)
    }

    override fun baseContent(
        workspace: PlasticWorkspace,
        status: PlasticWorkspaceStatus,
        basePath: Path,
        cancellation: PlasticCancellation,
    ): PlasticResult<ByteArray> {
        val operation = PlasticOperation.BASE_CONTENT
        precheck(operation, cancellation)?.let { return it }
        val normalizedBasePath = basePath.normalize()
        val generation = caches.generation()
        val key = ContentKey.Baseline(
            workspaceId = workspace.id,
            repository = status.repository,
            server = status.server,
            changeset = status.workspaceChangeset,
            path = normalizedBasePath.toString(),
        )
        caches.contents[key]?.let { bytes ->
            return results.success(
                operation = operation,
                origin = PlasticDiagnosticOrigin.CACHE,
                value = bytes.copyOf(),
            )
        }

        val token = combinedCancellation(cancellation)
        val result = runCommand(operation, onFailure = { return it }) {
            cli.workspaceBaseline(workspace.root, normalizedBasePath, status.workspaceChangeset, token)
        }
        results.processFailure(operation, result)?.let { return it }

        if (token.isCancellationRequested()) {
            return results.cancellationFailure(operation, result)
        }

        caches.storeContent(key, result.standardOutput, generation)
        return results.processSuccess(operation, result, result.standardOutput)
    }

    override fun add(
        workspace: PlasticWorkspace,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
    ): PlasticResult<Unit> =
        mutateWorkspace(
            operation = PlasticOperation.ADD,
            workspace = workspace,
            paths = paths,
            cancellation = cancellation,
            command = cli::add,
        )

    override fun undo(
        workspace: PlasticWorkspace,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
    ): PlasticResult<Unit> =
        mutateWorkspace(
            operation = PlasticOperation.UNDO,
            workspace = workspace,
            paths = paths,
            cancellation = cancellation,
            command = cli::undo,
        )

    override fun fileHistory(
        workspace: PlasticWorkspace,
        filePath: Path,
        request: PlasticHistoryRequest,
        cancellation: PlasticCancellation,
    ): PlasticResult<PlasticHistoryPage> {
        val operation = PlasticOperation.FILE_HISTORY
        precheck(operation, cancellation)?.let { return it }
        require(filePath.isAbsolute) { "The history path must be absolute." }
        val normalizedPath = filePath.normalize()
        require(normalizedPath != workspace.root.normalize()) { "The history path must not be the workspace root." }

        val generation = caches.generation()
        val key = HistoryKey(workspace.id, normalizedPath.toString(), generation)
        val cached = caches.histories[key]
        cached?.page?.boundedView(request.limit)?.let { page ->
            return results.success(
                operation = operation,
                origin = PlasticDiagnosticOrigin.CACHE,
                value = page,
            )
        }

        val token = combinedCancellation(cancellation)
        var query = cached?.query ?: HistoryQuery.WorkspacePath(normalizedPath)
        var result = executeFileHistory(workspace, query, request.limit + 1, token, onFailure = { return it })

        if (query is HistoryQuery.WorkspacePath && result.isCleanRejection()) {
            val status = when (val statusResult = status(workspace, workspace.root, token)) {
                is PlasticResult.Success -> statusResult.value
                is PlasticResult.Failure -> null
            }

            if (token.isCancellationRequested()) {
                return results.cancellationFailure(operation, result)
            }

            val movedQuery = status?.movedHistoryQuery(workspace, normalizedPath)

            if (movedQuery != null) {
                query = movedQuery
                result = executeFileHistory(workspace, query, request.limit + 1, token, onFailure = { return it })
            }
        }

        results.processFailure(operation, result)?.let { return it }

        val parsed = parseOutput(operation, result, onFailure = { return it }) {
            historyParser.parse(result.standardOutput) { checkParsingCancellation(token) }.also { history ->
                if (history.itemName != null && !query.matches(history.itemName, normalizedPath)) {
                    throw PlasticParseException("Plastic history returned an inconsistent item path.")
                }
            }
        }

        if (token.isCancellationRequested()) {
            return results.cancellationFailure(operation, result)
        }

        val newestFirst = parsed.revisions.asReversed()
        val formerPaths = newestFirst.formerItemPaths()
        val page = PlasticHistoryPage(
            revisions = Collections.unmodifiableList(
                ArrayList(newestFirst.take(request.limit).withFormerItemPaths(formerPaths)),
            ),
            hasMore = newestFirst.size > request.limit,
        )
        caches.storeIfCurrent(generation) { caches.histories.put(key, CachedHistory(page, query)) }
        return results.processSuccess(operation, result, page)
    }

    override fun revisionContent(
        revision: PlasticHistoryRevision,
        cancellation: PlasticCancellation,
    ): PlasticResult<ByteArray> {
        val operation = PlasticOperation.REVISION_CONTENT
        precheck(operation, cancellation)?.let { return it }
        val changeset = (revision.changeset as? PlasticHistoryChangeset.Number)?.value
        val itemId = revision.itemId

        if (revision.entryKind != PlasticHistoryEntryKind.CONTENT_REVISION ||
            changeset == null ||
            itemId == null ||
            itemId > Int.MAX_VALUE ||
            revision.dataStatus?.kind != PlasticRevisionDataStatusKind.AVAILABLE
        ) {
            return results.precheckFailure(operation, PlasticFailure.RevisionUnavailable)
        }

        val key = RevisionKey(revision.repository, revision.server, itemId, changeset)
        val generation = caches.generation()
        val contentKey = ContentKey.Revision(key)
        caches.contents[contentKey]?.let { bytes ->
            return results.success(
                operation = operation,
                origin = PlasticDiagnosticOrigin.CACHE,
                value = bytes.copyOf(),
            )
        }

        val token = combinedCancellation(cancellation)
        val cachedPath = caches.historicalPaths[key]

        val historicalPath = if (cachedPath != null) {
            results.recordIntermediateCacheHit(PlasticOperation.HISTORICAL_PATH)
            cachedPath
        } else {
            when (val pathResult = resolveHistoricalPath(key, token, generation)) {
                is PlasticResult.Success -> pathResult.value
                is PlasticResult.Failure -> return pathResult
            }
        }

        val firstResult = runCommand(operation, onFailure = { return it }) {
            cli.historicalContent(
                executionDirectory = historicalLookupDirectory,
                historicalPath = historicalPath,
                changeset = changeset,
                repository = revision.repository,
                server = revision.server,
                cancellation = token,
            )
        }

        val result = if (firstResult.isCleanRejection()) {
            contentAtFormerPath(revision, historicalPath, key, generation, token, onFailure = { return it })
                ?: firstResult
        } else {
            firstResult
        }

        results.processFailure(operation, result)?.let { return it }

        if (token.isCancellationRequested()) {
            return results.cancellationFailure(operation, result)
        }

        caches.storeContent(contentKey, result.standardOutput, generation)
        return results.processSuccess(operation, result, result.standardOutput)
    }

    override fun invalidateCaches() {
        caches.invalidate()
    }

    override fun close() {
        disposed.set(true)
        caches.close()
    }

    private fun mutateWorkspace(
        operation: PlasticOperation,
        workspace: PlasticWorkspace,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
        command: (Path, Collection<Path>, PlasticCancellation) -> PlasticTextResult,
    ): PlasticResult<Unit> {
        precheck(operation, cancellation)?.let { return it }
        val token = combinedCancellation(cancellation)
        val result = runCommand(operation, onFailure = { return it }) {
            command(workspace.root.normalize(), paths, token)
        }

        // A failed or cancelled multi-item command may still have changed a subset.
        invalidateCaches()
        results.processFailure(operation, result)?.let { return it }

        if (token.isCancellationRequested()) {
            return results.cancellationFailure(operation, result)
        }

        return results.processSuccess(operation, result, Unit)
    }

    private fun resolveHistoricalPath(
        key: RevisionKey,
        cancellation: PlasticCancellation,
        generation: Long,
    ): PlasticResult<String> {
        val operation = PlasticOperation.HISTORICAL_PATH
        val result = runCommand(operation, onFailure = { return it }) {
            cli.historicalPathResolution(
                executionDirectory = historicalLookupDirectory,
                itemId = key.itemId,
                changeset = key.changeset,
                repository = key.repository,
                server = key.server,
                cancellation = cancellation,
            )
        }
        results.processFailure(operation, result)?.let { return it }

        val lookup = parseOutput(operation, result, onFailure = { return it }) {
            historicalRevisionParser.parse(result.standardOutput) { checkParsingCancellation(cancellation) }
        }

        val found = when (lookup) {
            PlasticHistoricalRevisionLookup.NotFound ->
                return results.processFailure(
                    operation = operation,
                    result = result,
                    reason = PlasticFailure.RevisionUnavailable,
                )

            is PlasticHistoricalRevisionLookup.Ambiguous ->
                return results.processFailure(
                    operation = operation,
                    result = result,
                    reason = PlasticFailure.AmbiguousRevision,
                )

            is PlasticHistoricalRevisionLookup.Found -> lookup.revision
        }

        if (found.itemId != key.itemId ||
            found.changeset != key.changeset ||
            found.repositoryName != key.repository ||
            !found.path.startsWith('/')
        ) {
            return results.parseFailure(operation, result)
        }

        caches.storeIfCurrent(generation) { caches.historicalPaths.put(key, found.path) }
        return results.processSuccess(operation, result, found.path)
    }

    private inline fun executeFileHistory(
        workspace: PlasticWorkspace,
        query: HistoryQuery,
        limit: Int,
        cancellation: PlasticCancellation,
        onFailure: (PlasticResult.Failure) -> Nothing,
    ): PlasticBinaryResult =
        runCommand(PlasticOperation.FILE_HISTORY, onFailure) {
            when (query) {
                is HistoryQuery.WorkspacePath ->
                    cli.fileHistory(workspace.root, query.path, limit, cancellation)

                is HistoryQuery.ServerSpec ->
                    cli.fileHistoryForServerSpec(workspace.root, query.value, limit, cancellation)
            }
        }

    /** The first attempt at a path the item had before a move that is verified or interrupted, or null. */
    private inline fun contentAtFormerPath(
        revision: PlasticHistoryRevision,
        rejectedPath: String,
        key: RevisionKey,
        generation: Long,
        cancellation: PlasticCancellation,
        onFailure: (PlasticResult.Failure) -> Nothing,
    ): PlasticBinaryResult? {
        for (formerPath in revision.formerItemPaths) {
            if (formerPath == rejectedPath) {
                continue
            }

            val attempt = runCommand(PlasticOperation.REVISION_CONTENT, onFailure) {
                cli.historicalContent(
                    executionDirectory = historicalLookupDirectory,
                    historicalPath = formerPath,
                    changeset = key.changeset,
                    repository = revision.repository,
                    server = revision.server,
                    cancellation = cancellation,
                )
            }

            if (attempt.cancelled || attempt.timedOut) {
                return attempt
            }

            if (attempt.succeeded && revision.hasContent(attempt.standardOutput)) {
                caches.storeIfCurrent(generation) { caches.historicalPaths.put(key, formerPath) }
                return attempt
            }
        }

        return null
    }

    private fun List<PlasticHistoryRevision>.withFormerItemPaths(paths: List<String>): List<PlasticHistoryRevision> {
        if (paths.isEmpty()) {
            return this
        }

        return map { revision ->
            if (revision.entryKind == PlasticHistoryEntryKind.CONTENT_REVISION) {
                revision.copy(formerItemPaths = paths)
            } else {
                revision
            }
        }
    }

    private fun PlasticBinaryResult.isCleanRejection(): Boolean =
        exitCode == 1 && !timedOut && !cancelled && !standardOutputTruncated && !standardErrorTruncated

    private fun PlasticWorkspaceStatus.movedHistoryQuery(
        workspace: PlasticWorkspace,
        requestedPath: Path,
    ): HistoryQuery.ServerSpec? {
        val oldPath = changes
            .firstOrNull { change -> change.isMove && change.path.normalize() == requestedPath }
            ?.oldPath
            ?.normalize()
            ?: return null
        val relativePath = workspace.root.normalize().relativize(oldPath)
        val serverPath = relativePath.joinToString(separator = "/", prefix = "/")
        return HistoryQuery.ServerSpec(
            "serverpath:$serverPath#cs:$workspaceChangeset@$repository@$server",
        )
    }

    /** Disposal and caller cancellation are checked before any command runs. */
    private fun precheck(
        operation: PlasticOperation,
        cancellation: PlasticCancellation,
    ): PlasticResult.Failure? =
        when {
            disposed.get() -> results.precheckFailure(operation, PlasticFailure.Disposed)
            cancellation.isCancellationRequested() -> results.precheckFailure(operation, PlasticFailure.Cancelled)
            else -> null
        }

    private fun combinedCancellation(caller: PlasticCancellation): PlasticCancellation =
        PlasticCancellation { disposed.get() || caller.isCancellationRequested() }

    private fun checkParsingCancellation(cancellation: PlasticCancellation) {
        if (cancellation.isCancellationRequested()) {
            throw ParsingCancelledException()
        }
    }

    private inline fun <T> runCommand(
        operation: PlasticOperation,
        onFailure: (PlasticResult.Failure) -> Nothing,
        action: () -> T,
    ): T {
        val started = timeSource.markNow()

        return try {
            action()
        } catch (_: IOException) {
            onFailure(results.launchFailure(operation, started.elapsedNow()))
        } catch (_: SecurityException) {
            onFailure(results.launchFailure(operation, started.elapsedNow()))
        } catch (_: IllegalArgumentException) {
            onFailure(results.executionFailure(operation, started.elapsedNow()))
        } catch (_: IllegalStateException) {
            onFailure(results.executionFailure(operation, started.elapsedNow()))
        } catch (_: UnsupportedOperationException) {
            onFailure(results.executionFailure(operation, started.elapsedNow()))
        }
    }

    private inline fun <T> parseOutput(
        operation: PlasticOperation,
        result: PlasticCommandResult<*>,
        onFailure: (PlasticResult.Failure) -> Nothing,
        parse: () -> T,
    ): T =
        try {
            parse()
        } catch (_: ParsingCancelledException) {
            onFailure(results.cancellationFailure(operation, result))
        } catch (_: PlasticParseException) {
            onFailure(results.parseFailure(operation, result))
        }

    /** Reuses a cached wider prefix, or a known complete history, without retaining overlapping pages. */
    private fun PlasticHistoryPage.boundedView(limit: Int): PlasticHistoryPage? {
        if (revisions.size < limit) {
            return takeUnless { hasMore }
        }

        if (revisions.size == limit) {
            return this
        }

        return PlasticHistoryPage(
            revisions = Collections.unmodifiableList(ArrayList(revisions.take(limit))),
            hasMore = true,
        )
    }

    private class ParsingCancelledException : RuntimeException()
}

internal sealed interface HistoryQuery {
    val cacheText: String

    fun matches(
        itemName: String,
        requestedPath: Path,
    ): Boolean

    data class WorkspacePath(
        val path: Path,
    ) : HistoryQuery {
        override val cacheText: String = path.toString()

        override fun matches(
            itemName: String,
            requestedPath: Path,
        ): Boolean = parseAbsolutePlasticPath(itemName, "history item path") == requestedPath
    }

    data class ServerSpec(
        val value: String,
    ) : HistoryQuery {
        override val cacheText: String = value

        override fun matches(
            itemName: String,
            requestedPath: Path,
        ): Boolean = itemName == value
    }
}
