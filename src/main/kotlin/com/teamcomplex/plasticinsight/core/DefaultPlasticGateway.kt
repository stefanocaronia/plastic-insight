package com.teamcomplex.plasticinsight.core

import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
) : PlasticGateway {
    private val disposed = AtomicBoolean()
    private val cacheGeneration = AtomicLong()
    private val lifecycleLock = Any()
    private val workspaceCache = BoundedLruCache<Path, PlasticWorkspace>(
        maxEntries = cacheLimits.workspaceEntries,
        maxWeight = cacheLimits.workspaceBytes,
        weigh = ::workspaceWeight,
    )
    private val historyCache = BoundedLruCache<HistoryKey, PlasticHistoryPage>(
        maxEntries = cacheLimits.historyEntries,
        maxWeight = cacheLimits.historyBytes,
        weigh = ::historyWeight,
    )
    private val contentCache = BoundedLruCache<ContentKey, ByteArray>(
        maxEntries = cacheLimits.contentEntries,
        maxWeight = cacheLimits.contentBytes,
        weigh = { key, bytes -> contentKeyWeight(key) + bytes.size },
    )
    private val historicalPathCache = BoundedLruCache<RevisionKey, String>(
        maxEntries = cacheLimits.historicalPathEntries,
        maxWeight = cacheLimits.historicalPathBytes,
        weigh = { key, path -> revisionKeyWeight(key) + textWeight(path) },
    )

    init {
        require(historicalLookupDirectory.isAbsolute) { "The historical lookup directory must be absolute." }
    }

    override fun discoverWorkspace(
        directory: Path,
        cancellation: PlasticCancellation,
    ): PlasticResult<PlasticWorkspaceLookup> {
        require(directory.isAbsolute) { "The workspace lookup directory must be absolute." }
        disposedFailure(PlasticOperation.WORKSPACE_DISCOVERY)?.let { return it }
        cancellationPrecheck(PlasticOperation.WORKSPACE_DISCOVERY, cancellation)?.let { return it }

        val root = workspaceLocator.findRoot(directory.normalize())
            ?: return success(
                operation = PlasticOperation.WORKSPACE_DISCOVERY,
                origin = PlasticDiagnosticOrigin.PRECHECK,
                outcome = PlasticDiagnosticOutcome.NOT_FOUND,
                value = PlasticWorkspaceLookup.NotFound,
            )
        val generation = cacheGeneration.get()
        workspaceCache[root]?.let { workspace ->
            return success(
                operation = PlasticOperation.WORKSPACE_DISCOVERY,
                origin = PlasticDiagnosticOrigin.CACHE,
                value = PlasticWorkspaceLookup.Found(workspace),
            )
        }

        val token = combinedCancellation(cancellation)
        val execution = runCommand(PlasticOperation.WORKSPACE_DISCOVERY) {
            cli.workspaceFromPath(root, root, token)
        }
        val result = execution.resultOrReturnFailure() ?: return requireNotNull(execution.failure)
        processFailure(PlasticOperation.WORKSPACE_DISCOVERY, result.state())?.let { return it }

        val workspace = try {
            checkParsingCancellation(token)
            workspaceParser.parse(result.standardOutput).also { parsed ->
                if (parsed.root.normalize() != root) {
                    throw PlasticParseException("Plastic workspace discovery returned an inconsistent root.")
                }
            }
        } catch (_: ParsingCancelledException) {
            return cancellationFailure(PlasticOperation.WORKSPACE_DISCOVERY, result.state())
        } catch (_: PlasticParseException) {
            return parseFailure(PlasticOperation.WORKSPACE_DISCOVERY, result.state())
        }
        if (token.isCancellationRequested()) {
            return cancellationFailure(PlasticOperation.WORKSPACE_DISCOVERY, result.state())
        }

        cacheIfCurrent(generation) { workspaceCache.put(root, workspace) }
        return processSuccess(
            operation = PlasticOperation.WORKSPACE_DISCOVERY,
            state = result.state(),
            value = PlasticWorkspaceLookup.Found(workspace),
        )
    }

    override fun status(
        workspace: PlasticWorkspace,
        scope: Path,
        cancellation: PlasticCancellation,
        includePrivateFiles: Boolean,
    ): PlasticResult<PlasticWorkspaceStatus> {
        disposedFailure(PlasticOperation.STATUS)?.let { return it }
        cancellationPrecheck(PlasticOperation.STATUS, cancellation)?.let { return it }
        val normalizedRoot = workspace.root.normalize()
        val normalizedScope = scope.normalize()
        val token = combinedCancellation(cancellation)
        val execution = runCommand(PlasticOperation.STATUS) {
            if (normalizedScope == normalizedRoot) {
                cli.workspaceStatus(normalizedRoot, token, includePrivateFiles)
            } else {
                cli.constrainedStatus(normalizedRoot, normalizedScope, token, includePrivateFiles)
            }
        }
        val result = execution.resultOrReturnFailure() ?: return requireNotNull(execution.failure)
        processFailure(PlasticOperation.STATUS, result.state())?.let { return it }

        val status = try {
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
        } catch (_: ParsingCancelledException) {
            return cancellationFailure(PlasticOperation.STATUS, result.state())
        } catch (_: PlasticParseException) {
            return parseFailure(PlasticOperation.STATUS, result.state())
        }
        if (token.isCancellationRequested()) {
            return cancellationFailure(PlasticOperation.STATUS, result.state())
        }
        return processSuccess(PlasticOperation.STATUS, result.state(), status)
    }

    override fun baseContent(
        workspace: PlasticWorkspace,
        status: PlasticWorkspaceStatus,
        basePath: Path,
        cancellation: PlasticCancellation,
    ): PlasticResult<ByteArray> {
        disposedFailure(PlasticOperation.BASE_CONTENT)?.let { return it }
        cancellationPrecheck(PlasticOperation.BASE_CONTENT, cancellation)?.let { return it }
        val normalizedBasePath = basePath.normalize()
        val generation = cacheGeneration.get()
        val key = ContentKey.Baseline(
            workspaceId = workspace.id,
            repository = status.repository,
            server = status.server,
            changeset = status.workspaceChangeset,
            path = normalizedBasePath.toString(),
        )
        contentCache[key]?.let { bytes ->
            return success(
                operation = PlasticOperation.BASE_CONTENT,
                origin = PlasticDiagnosticOrigin.CACHE,
                value = bytes.copyOf(),
            )
        }

        val token = combinedCancellation(cancellation)
        val execution = runCommand(PlasticOperation.BASE_CONTENT) {
            cli.workspaceBaseline(workspace.root, normalizedBasePath, status.workspaceChangeset, token)
        }
        val result = execution.resultOrReturnFailure() ?: return requireNotNull(execution.failure)
        processFailure(PlasticOperation.BASE_CONTENT, result.state())?.let { return it }
        if (token.isCancellationRequested()) {
            return cancellationFailure(PlasticOperation.BASE_CONTENT, result.state())
        }

        cacheContent(key, result.standardOutput, generation)
        return processSuccess(PlasticOperation.BASE_CONTENT, result.state(), result.standardOutput)
    }

    override fun add(
        workspace: PlasticWorkspace,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
    ): PlasticResult<Unit> = mutateWorkspace(
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
    ): PlasticResult<Unit> = mutateWorkspace(
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
        disposedFailure(PlasticOperation.FILE_HISTORY)?.let { return it }
        cancellationPrecheck(PlasticOperation.FILE_HISTORY, cancellation)?.let { return it }
        require(filePath.isAbsolute) { "The history path must be absolute." }
        val normalizedPath = filePath.normalize()
        require(normalizedPath != workspace.root.normalize()) { "The history path must not be the workspace root." }

        val generation = cacheGeneration.get()
        val key = HistoryKey(workspace.id, normalizedPath.toString(), generation)
        historyCache[key]?.boundedView(request.limit)?.let { page ->
            return success(
                operation = PlasticOperation.FILE_HISTORY,
                origin = PlasticDiagnosticOrigin.CACHE,
                value = page,
            )
        }

        val token = combinedCancellation(cancellation)
        val execution = runCommand(PlasticOperation.FILE_HISTORY) {
            cli.fileHistory(workspace.root, normalizedPath, request.limit + 1, token)
        }
        val result = execution.resultOrReturnFailure() ?: return requireNotNull(execution.failure)
        processFailure(PlasticOperation.FILE_HISTORY, result.state())?.let { return it }

        val parsed = try {
            historyParser.parse(result.standardOutput) { checkParsingCancellation(token) }.also { history ->
                if (history.itemName != null &&
                    parseAbsolutePlasticPath(history.itemName, "history item path") != normalizedPath
                ) {
                    throw PlasticParseException("Plastic history returned an inconsistent item path.")
                }
            }
        } catch (_: ParsingCancelledException) {
            return cancellationFailure(PlasticOperation.FILE_HISTORY, result.state())
        } catch (_: PlasticParseException) {
            return parseFailure(PlasticOperation.FILE_HISTORY, result.state())
        }
        if (token.isCancellationRequested()) {
            return cancellationFailure(PlasticOperation.FILE_HISTORY, result.state())
        }
        val newestFirst = parsed.revisions.asReversed()
        val page = PlasticHistoryPage(
            revisions = Collections.unmodifiableList(ArrayList(newestFirst.take(request.limit))),
            hasMore = newestFirst.size > request.limit,
        )
        cacheIfCurrent(generation) { historyCache.put(key, page) }
        return processSuccess(PlasticOperation.FILE_HISTORY, result.state(), page)
    }

    override fun revisionContent(
        revision: PlasticHistoryRevision,
        cancellation: PlasticCancellation,
    ): PlasticResult<ByteArray> {
        disposedFailure(PlasticOperation.REVISION_CONTENT)?.let { return it }
        cancellationPrecheck(PlasticOperation.REVISION_CONTENT, cancellation)?.let { return it }
        val changeset = (revision.changeset as? PlasticHistoryChangeset.Number)?.value
        val itemId = revision.itemId
        if (revision.entryKind != PlasticHistoryEntryKind.CONTENT_REVISION ||
            changeset == null ||
            itemId == null ||
            itemId > Int.MAX_VALUE ||
            revision.dataStatus?.kind != PlasticRevisionDataStatusKind.AVAILABLE
        ) {
            return precheckFailure(PlasticOperation.REVISION_CONTENT, PlasticFailure.RevisionUnavailable)
        }

        val key = RevisionKey(revision.repository, revision.server, itemId, changeset)
        val generation = cacheGeneration.get()
        val contentKey = ContentKey.Revision(key)
        contentCache[contentKey]?.let { bytes ->
            return success(
                operation = PlasticOperation.REVISION_CONTENT,
                origin = PlasticDiagnosticOrigin.CACHE,
                value = bytes.copyOf(),
            )
        }

        val token = combinedCancellation(cancellation)
        val cachedPath = historicalPathCache[key]
        val historicalPath = if (cachedPath != null) {
            recordIntermediateCacheHit(PlasticOperation.HISTORICAL_PATH)
            cachedPath
        } else {
            when (val pathResult = resolveHistoricalPath(key, token, generation)) {
                is PlasticResult.Success -> pathResult.value
                is PlasticResult.Failure -> return pathResult
            }
        }
        val execution = runCommand(PlasticOperation.REVISION_CONTENT) {
            cli.historicalContent(
                executionDirectory = historicalLookupDirectory,
                historicalPath = historicalPath,
                changeset = changeset,
                repository = revision.repository,
                server = revision.server,
                cancellation = token,
            )
        }
        val result = execution.resultOrReturnFailure() ?: return requireNotNull(execution.failure)
        processFailure(PlasticOperation.REVISION_CONTENT, result.state())?.let { return it }
        if (token.isCancellationRequested()) {
            return cancellationFailure(PlasticOperation.REVISION_CONTENT, result.state())
        }

        cacheContent(contentKey, result.standardOutput, generation)
        return processSuccess(PlasticOperation.REVISION_CONTENT, result.state(), result.standardOutput)
    }

    override fun invalidateCaches() {
        synchronized(lifecycleLock) {
            cacheGeneration.incrementAndGet()
            clearCaches()
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            disposed.set(true)
            cacheGeneration.incrementAndGet()
            clearCaches()
        }
    }

    private fun mutateWorkspace(
        operation: PlasticOperation,
        workspace: PlasticWorkspace,
        paths: Collection<Path>,
        cancellation: PlasticCancellation,
        command: (Path, Collection<Path>, PlasticCancellation) -> PlasticTextResult,
    ): PlasticResult<Unit> {
        disposedFailure(operation)?.let { return it }
        cancellationPrecheck(operation, cancellation)?.let { return it }
        val token = combinedCancellation(cancellation)
        val execution = runCommand(operation) {
            command(workspace.root.normalize(), paths, token)
        }
        val result = execution.resultOrReturnFailure() ?: return requireNotNull(execution.failure)

        // A failed or cancelled multi-item command may still have changed a subset.
        invalidateCaches()
        processFailure(operation, result.state())?.let { return it }
        if (token.isCancellationRequested()) return cancellationFailure(operation, result.state())
        return processSuccess(operation, result.state(), Unit)
    }

    private fun resolveHistoricalPath(
        key: RevisionKey,
        cancellation: PlasticCancellation,
        generation: Long,
    ): PlasticResult<String> {
        val execution = runCommand(PlasticOperation.HISTORICAL_PATH) {
            cli.historicalPathResolution(
                executionDirectory = historicalLookupDirectory,
                itemId = key.itemId,
                changeset = key.changeset,
                repository = key.repository,
                server = key.server,
                cancellation = cancellation,
            )
        }
        val result = execution.resultOrReturnFailure() ?: return requireNotNull(execution.failure)
        processFailure(PlasticOperation.HISTORICAL_PATH, result.state())?.let { return it }

        val lookup = try {
            historicalRevisionParser.parse(result.standardOutput) { checkParsingCancellation(cancellation) }
        } catch (_: ParsingCancelledException) {
            return cancellationFailure(PlasticOperation.HISTORICAL_PATH, result.state())
        } catch (_: PlasticParseException) {
            return parseFailure(PlasticOperation.HISTORICAL_PATH, result.state())
        }

        val found = when (lookup) {
            PlasticHistoricalRevisionLookup.NotFound ->
                return processFailure(
                    operation = PlasticOperation.HISTORICAL_PATH,
                    state = result.state(),
                    reason = PlasticFailure.RevisionUnavailable,
                )

            is PlasticHistoricalRevisionLookup.Ambiguous ->
                return processFailure(
                    operation = PlasticOperation.HISTORICAL_PATH,
                    state = result.state(),
                    reason = PlasticFailure.AmbiguousRevision,
                )

            is PlasticHistoricalRevisionLookup.Found -> lookup.revision
        }
        if (found.itemId != key.itemId ||
            found.changeset != key.changeset ||
            found.repositoryName != key.repository ||
            !found.path.startsWith('/')
        ) {
            return parseFailure(PlasticOperation.HISTORICAL_PATH, result.state())
        }

        cacheIfCurrent(generation) { historicalPathCache.put(key, found.path) }
        return processSuccess(PlasticOperation.HISTORICAL_PATH, result.state(), found.path)
    }

    private fun cacheContent(
        key: ContentKey,
        bytes: ByteArray,
        generation: Long,
    ) {
        if (bytes.size <= cacheLimits.maxCacheableContentBytes) {
            cacheIfCurrent(generation) { contentCache.put(key, bytes.copyOf()) }
        }
    }

    private inline fun cacheIfCurrent(
        generation: Long,
        action: () -> Unit,
    ) {
        synchronized(lifecycleLock) {
            if (!disposed.get() && cacheGeneration.get() == generation) action()
        }
    }

    private fun clearCaches() {
        workspaceCache.clear()
        historyCache.clear()
        contentCache.clear()
        historicalPathCache.clear()
    }

    private fun combinedCancellation(caller: PlasticCancellation): PlasticCancellation =
        PlasticCancellation { disposed.get() || caller.isCancellationRequested() }

    private fun checkParsingCancellation(cancellation: PlasticCancellation) {
        if (cancellation.isCancellationRequested()) throw ParsingCancelledException()
    }

    private inline fun <T> runCommand(
        operation: PlasticOperation,
        action: () -> T,
    ): CommandExecution<T> {
        val startedAt = System.nanoTime()
        return try {
            CommandExecution(result = action())
        } catch (_: IOException) {
            CommandExecution(failure = launchFailure(operation, startedAt))
        } catch (_: SecurityException) {
            CommandExecution(failure = launchFailure(operation, startedAt))
        } catch (_: IllegalArgumentException) {
            CommandExecution(failure = executionFailure(operation, startedAt))
        } catch (_: IllegalStateException) {
            CommandExecution(failure = executionFailure(operation, startedAt))
        } catch (_: UnsupportedOperationException) {
            CommandExecution(failure = executionFailure(operation, startedAt))
        }
    }

    private fun launchFailure(
        operation: PlasticOperation,
        startedAt: Long,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            origin = PlasticDiagnosticOrigin.PROCESS,
            outcome = PlasticDiagnosticOutcome.FAILED,
            reason = PlasticFailure.LaunchFailed,
            duration = elapsedSince(startedAt),
        )

    private fun executionFailure(
        operation: PlasticOperation,
        startedAt: Long,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            origin = PlasticDiagnosticOrigin.PROCESS,
            outcome = PlasticDiagnosticOutcome.FAILED,
            reason = PlasticFailure.ExecutionFailed,
            duration = elapsedSince(startedAt),
        )

    private fun processFailure(
        operation: PlasticOperation,
        state: CommandState,
    ): PlasticResult.Failure? =
        when {
            state.cancelled -> cancellationFailure(operation, state)
            state.timedOut -> processFailure(operation, state, PlasticFailure.TimedOut)
            state.standardOutputTruncated || state.standardErrorTruncated ->
                processFailure(
                    operation,
                    state,
                    PlasticFailure.OutputLimitExceeded(
                        standardOutput = state.standardOutputTruncated,
                        standardError = state.standardErrorTruncated,
                    ),
                )

            state.exitCode != 0 -> processFailure(operation, state, PlasticFailure.CommandFailed(state.exitCode))
            else -> null
        }

    private fun processFailure(
        operation: PlasticOperation,
        state: CommandState,
        reason: PlasticFailure,
    ): PlasticResult.Failure {
        val outcome = when (reason) {
            PlasticFailure.TimedOut -> PlasticDiagnosticOutcome.TIMED_OUT
            PlasticFailure.Cancelled,
            PlasticFailure.Disposed,
            -> PlasticDiagnosticOutcome.CANCELLED

            is PlasticFailure.OutputLimitExceeded -> PlasticDiagnosticOutcome.TRUNCATED
            else -> PlasticDiagnosticOutcome.FAILED
        }
        return failure(operation, state, outcome, reason)
    }

    private fun cancellationFailure(
        operation: PlasticOperation,
        state: CommandState,
    ): PlasticResult.Failure =
        processFailure(
            operation = operation,
            state = state,
            reason = if (disposed.get()) PlasticFailure.Disposed else PlasticFailure.Cancelled,
        )

    private fun parseFailure(
        operation: PlasticOperation,
        state: CommandState,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            state = state,
            outcome = PlasticDiagnosticOutcome.FAILED,
            reason = PlasticFailure.MalformedOutput,
        )

    private fun disposedFailure(operation: PlasticOperation): PlasticResult.Failure? =
        if (disposed.get()) precheckFailure(operation, PlasticFailure.Disposed) else null

    private fun cancellationPrecheck(
        operation: PlasticOperation,
        cancellation: PlasticCancellation,
    ): PlasticResult.Failure? =
        if (cancellation.isCancellationRequested()) {
            precheckFailure(operation, PlasticFailure.Cancelled)
        } else {
            null
        }

    private fun precheckFailure(
        operation: PlasticOperation,
        reason: PlasticFailure,
    ): PlasticResult.Failure =
        failure(
            operation = operation,
            origin = PlasticDiagnosticOrigin.PRECHECK,
            outcome = if (reason == PlasticFailure.Disposed || reason == PlasticFailure.Cancelled) {
                PlasticDiagnosticOutcome.CANCELLED
            } else {
                PlasticDiagnosticOutcome.FAILED
            },
            reason = reason,
        )

    private fun <T> processSuccess(
        operation: PlasticOperation,
        state: CommandState,
        value: T,
    ): PlasticResult.Success<T> {
        val diagnostic = state.diagnostic(operation, PlasticDiagnosticOutcome.SUCCESS)
        diagnosticSink.recordSafely(diagnostic)
        return PlasticResult.Success(value, diagnostic)
    }

    private fun <T> success(
        operation: PlasticOperation,
        origin: PlasticDiagnosticOrigin,
        value: T,
        outcome: PlasticDiagnosticOutcome = PlasticDiagnosticOutcome.SUCCESS,
    ): PlasticResult.Success<T> {
        val diagnostic = PlasticDiagnostic(operation, origin, outcome)
        diagnosticSink.recordSafely(diagnostic)
        return PlasticResult.Success(value, diagnostic)
    }

    private fun recordIntermediateCacheHit(operation: PlasticOperation) {
        diagnosticSink.recordSafely(
            PlasticDiagnostic(
                operation = operation,
                origin = PlasticDiagnosticOrigin.CACHE,
                outcome = PlasticDiagnosticOutcome.SUCCESS,
            ),
        )
    }

    private fun failure(
        operation: PlasticOperation,
        state: CommandState,
        outcome: PlasticDiagnosticOutcome,
        reason: PlasticFailure,
    ): PlasticResult.Failure {
        val diagnostic = state.diagnostic(operation, outcome)
        diagnosticSink.recordSafely(diagnostic)
        return PlasticResult.Failure(reason, diagnostic)
    }

    private fun failure(
        operation: PlasticOperation,
        origin: PlasticDiagnosticOrigin,
        outcome: PlasticDiagnosticOutcome,
        reason: PlasticFailure,
        duration: Duration = Duration.ZERO,
    ): PlasticResult.Failure {
        val diagnostic = PlasticDiagnostic(operation, origin, outcome, duration)
        diagnosticSink.recordSafely(diagnostic)
        return PlasticResult.Failure(reason, diagnostic)
    }

    private fun PlasticTextResult.state(): CommandState =
        CommandState(
            exitCode,
            duration,
            timedOut,
            cancelled,
            standardOutputTruncated,
            standardErrorTruncated,
            standardOutputBytesRead,
            standardErrorBytesRead,
        )

    private fun PlasticBinaryResult.state(): CommandState =
        CommandState(
            exitCode,
            duration,
            timedOut,
            cancelled,
            standardOutputTruncated,
            standardErrorTruncated,
            standardOutputBytesRead,
            standardErrorBytesRead,
        )

    private fun workspaceWeight(key: Path, workspace: PlasticWorkspace): Long =
        128L + textWeight(key.toString(), workspace.name, workspace.root.toString(), workspace.machine, workspace.workspaceType)

    private fun historyWeight(key: HistoryKey, page: PlasticHistoryPage): Long =
        128L + textWeight(key.path) + page.revisions.sumOf(::historyRevisionWeight)

    /** Reuses a cached wider prefix, or a known complete history, without retaining overlapping pages. */
    private fun PlasticHistoryPage.boundedView(limit: Int): PlasticHistoryPage? {
        if (revisions.size < limit) return takeUnless { hasMore }
        if (revisions.size == limit) return this
        return PlasticHistoryPage(
            revisions = Collections.unmodifiableList(ArrayList(revisions.take(limit))),
            hasMore = true,
        )
    }

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

    private fun revisionKeyWeight(key: RevisionKey): Long =
        96L + textWeight(key.repository, key.server)

    private fun textWeight(vararg values: String?): Long =
        values.sumOf { value -> (value?.length ?: 0).toLong() * Char.SIZE_BYTES }

    private fun elapsedSince(startedAt: Long): Duration =
        Duration.ofNanos((System.nanoTime() - startedAt).coerceAtLeast(0L))

    private data class CommandExecution<T>(
        val result: T? = null,
        val failure: PlasticResult.Failure? = null,
    ) {
        init {
            require((result == null) != (failure == null)) { "A command execution must contain one outcome." }
        }

        fun resultOrReturnFailure(): T? = result
    }

    private data class CommandState(
        val exitCode: Int?,
        val duration: Duration,
        val timedOut: Boolean,
        val cancelled: Boolean,
        val standardOutputTruncated: Boolean,
        val standardErrorTruncated: Boolean,
        val standardOutputBytesRead: Long,
        val standardErrorBytesRead: Long,
    ) {
        fun diagnostic(
            operation: PlasticOperation,
            outcome: PlasticDiagnosticOutcome,
        ): PlasticDiagnostic =
            PlasticDiagnostic(
                operation = operation,
                origin = PlasticDiagnosticOrigin.PROCESS,
                outcome = outcome,
                duration = duration,
                exitCode = exitCode,
                standardOutputBytesRead = standardOutputBytesRead,
                standardErrorBytesRead = standardErrorBytesRead,
            )
    }

    private data class HistoryKey(
        val workspaceId: UUID,
        val path: String,
        val generation: Long,
    )

    private data class RevisionKey(
        val repository: String,
        val server: String,
        val itemId: Long,
        val changeset: Long,
    )

    private sealed interface ContentKey {
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

    private class ParsingCancelledException : RuntimeException()
}
