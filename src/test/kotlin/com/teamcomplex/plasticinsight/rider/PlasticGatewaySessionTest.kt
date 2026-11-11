package com.teamcomplex.plasticinsight.rider

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vcs.VcsException
import com.teamcomplex.plasticinsight.core.PlasticDiagnostic
import com.teamcomplex.plasticinsight.core.PlasticDiagnosticOrigin
import com.teamcomplex.plasticinsight.core.PlasticDiagnosticOutcome
import com.teamcomplex.plasticinsight.core.PlasticExecutableSource
import com.teamcomplex.plasticinsight.core.PlasticFailure
import com.teamcomplex.plasticinsight.core.PlasticGateway
import com.teamcomplex.plasticinsight.core.PlasticHistoryPage
import com.teamcomplex.plasticinsight.core.PlasticHistoryRequest
import com.teamcomplex.plasticinsight.core.PlasticOperation
import com.teamcomplex.plasticinsight.core.PlasticResult
import com.teamcomplex.plasticinsight.core.PlasticWorkspaceLookup
import com.teamcomplex.plasticinsight.core.history.PlasticHistoryChangeset
import com.teamcomplex.plasticinsight.core.history.PlasticHistoryEntryKind
import com.teamcomplex.plasticinsight.core.history.PlasticHistoryRevision
import com.teamcomplex.plasticinsight.core.history.PlasticRevisionDataStatus
import com.teamcomplex.plasticinsight.core.history.PlasticRevisionType
import com.teamcomplex.plasticinsight.core.process.PlasticCancellation
import com.teamcomplex.plasticinsight.core.status.PlasticWorkspaceStatus
import com.teamcomplex.plasticinsight.core.workspace.PlasticWorkspace
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlasticGatewaySessionTest {
    @Test
    fun `creates the gateway once and reuses it across calls`() {
        val harness = SessionHarness()

        harness.session.loadStatus(FILE, CANCELLATION)
        harness.session.loadBaseContent(snapshot(), FILE, CANCELLATION)
        harness.session.loadRevisionContent(revision(), CANCELLATION)

        assertEquals(1, harness.creations)
        assertEquals(
            listOf("discoverWorkspace", "status", "baseContent", "revisionContent"),
            harness.gateway.calls.map(GatewayCall::operation),
        )
    }

    @Test
    fun `a failed creation surfaces on every call and is not retried`() {
        val harness = SessionHarness(creation = failure(PlasticFailure.ExecutableNotFound))

        val first = assertFailsWith<VcsException> { harness.session.loadStatus(FILE, CANCELLATION) }
        val second = assertFailsWith<VcsException> { harness.session.addPaths(ROOT, listOf(FILE), CANCELLATION) }

        assertEquals("Plastic initialization failed because cm.exe was not found.", first.message)
        assertEquals("Plastic initialization failed because cm.exe was not found.", second.message)
        assertEquals(1, harness.creations)
    }

    @Test
    fun `cancelled and disposed failures become Rider cancellation`() {
        listOf(PlasticFailure.Cancelled, PlasticFailure.Disposed).forEach { reason ->
            val harness = SessionHarness()
            harness.gateway.lookup = failure(reason)

            assertFailsWith<ProcessCanceledException>(reason.toString()) {
                harness.session.loadStatus(FILE, CANCELLATION)
            }
        }
    }

    @Test
    fun `every other failure becomes a VcsException naming the operation`() {
        val expectedMessages = listOf(
            PlasticFailure.ExecutableNotFound to "Plastic workspace discovery failed because cm.exe was not found.",
            PlasticFailure.InvalidExecutableConfiguration(PlasticExecutableSource.EXPLICIT_SETTING) to
                "Plastic workspace discovery failed because the configured cm.exe path is invalid.",
            PlasticFailure.InvalidRuntimeConfiguration to
                "Plastic workspace discovery failed because its runtime configuration is invalid.",
            PlasticFailure.LaunchFailed to "Plastic workspace discovery could not start cm.exe.",
            PlasticFailure.ExecutionFailed to "Plastic workspace discovery could not complete.",
            PlasticFailure.TimedOut to "Plastic workspace discovery timed out.",
            PlasticFailure.OutputLimitExceeded(standardOutput = true, standardError = false) to
                "Plastic workspace discovery exceeded its output limit.",
            PlasticFailure.CommandFailed(exitCode = 3) to "Plastic workspace discovery failed with exit code 3.",
            PlasticFailure.CommandFailed(exitCode = null) to "Plastic workspace discovery failed with exit code unknown.",
            PlasticFailure.MalformedOutput to "Plastic workspace discovery returned malformed output.",
            PlasticFailure.RevisionUnavailable to "The requested Plastic revision is unavailable.",
            PlasticFailure.AmbiguousRevision to "Plastic returned more than one matching revision.",
        )

        expectedMessages.forEach { (reason, expectedMessage) ->
            val harness = SessionHarness()
            harness.gateway.lookup = failure(reason)

            val exception = assertFailsWith<VcsException>(reason.toString()) {
                harness.session.loadStatus(FILE, CANCELLATION)
            }

            assertEquals(expectedMessage, exception.message)
        }
    }

    @Test
    fun `each call names its own operation in the failure message`() {
        val harness = SessionHarness()
        harness.gateway.statusResult = failure(PlasticFailure.TimedOut)
        harness.gateway.baseContentResult = failure(PlasticFailure.TimedOut)
        harness.gateway.historyResult = failure(PlasticFailure.TimedOut)
        harness.gateway.revisionContentResult = failure(PlasticFailure.TimedOut)
        harness.gateway.addResult = failure(PlasticFailure.TimedOut)
        harness.gateway.undoResult = failure(PlasticFailure.TimedOut)

        val messages = listOf(
            assertFailsWith<VcsException> { harness.session.loadStatus(FILE, CANCELLATION) },
            assertFailsWith<VcsException> { harness.session.loadBaseContent(snapshot(), FILE, CANCELLATION) },
            assertFailsWith<VcsException> { harness.session.loadFileHistory(FILE, CANCELLATION) },
            assertFailsWith<VcsException> { harness.session.loadRevisionContent(revision(), CANCELLATION) },
            assertFailsWith<VcsException> { harness.session.addPaths(ROOT, listOf(FILE), CANCELLATION) },
            assertFailsWith<VcsException> { harness.session.undoPaths(ROOT, listOf(FILE), CANCELLATION) },
        ).map { exception -> exception.message }

        assertEquals(
            listOf(
                "Plastic status timed out.",
                "Plastic base content timed out.",
                "Plastic file history timed out.",
                "Plastic historical content timed out.",
                "Plastic add timed out.",
                "Plastic rollback timed out.",
            ),
            messages,
        )
    }

    @Test
    fun `every command on the dispatch thread fails without reaching the gateway`() {
        val harness = SessionHarness(isDispatchThread = true)
        val commands: List<() -> Unit> = listOf(
            { harness.session.loadStatus(FILE, CANCELLATION) },
            { harness.session.loadBaseContent(snapshot(), FILE, CANCELLATION) },
            { harness.session.loadFileHistory(FILE, CANCELLATION) },
            { harness.session.loadRevisionContent(revision(), CANCELLATION) },
            { harness.session.addPaths(ROOT, listOf(FILE), CANCELLATION) },
            { harness.session.undoPaths(ROOT, listOf(FILE), CANCELLATION) },
        )

        commands.forEach { command ->
            val exception = assertFailsWith<VcsException> { command() }
            assertEquals("Plastic commands cannot run on Rider's event-dispatch thread.", exception.message)
        }

        assertEquals(0, harness.creations)
        assertTrue(harness.gateway.calls.isEmpty())
    }

    @Test
    fun `status outside a workspace is null`() {
        val harness = SessionHarness()
        harness.gateway.lookup = success(PlasticWorkspaceLookup.NotFound)

        assertNull(harness.session.loadStatus(FILE, CANCELLATION))
        assertEquals(listOf("discoverWorkspace"), harness.gateway.calls.map(GatewayCall::operation))
    }

    @Test
    fun `status of workspace metadata is null without asking the gateway for status`() {
        val harness = SessionHarness()
        val metadata = ROOT.resolve(".plastic").resolve("plastic.workspace")

        assertNull(harness.session.loadStatus(metadata, CANCELLATION))
        assertEquals(listOf("discoverWorkspace"), harness.gateway.calls.map(GatewayCall::operation))
    }

    @Test
    fun `status returns the snapshot and passes the normalized scope and private-file flag`() {
        val harness = SessionHarness()
        val unnormalized = ROOT.resolve("Source").resolve("..").resolve("file.cs")

        val withPrivateFiles = harness.session.loadStatus(unnormalized, CANCELLATION, includePrivateFiles = true)
        val withoutPrivateFiles = harness.session.loadStatus(FILE, CANCELLATION)

        assertEquals(PlasticStatusSnapshot(WORKSPACE, STATUS), withPrivateFiles)
        assertEquals(PlasticStatusSnapshot(WORKSPACE, STATUS), withoutPrivateFiles)
        assertEquals(
            listOf(
                GatewayCall("discoverWorkspace", listOf(FILE, CANCELLATION)),
                GatewayCall("status", listOf(WORKSPACE, FILE, CANCELLATION, true)),
                GatewayCall("discoverWorkspace", listOf(FILE, CANCELLATION)),
                GatewayCall("status", listOf(WORKSPACE, FILE, CANCELLATION, false)),
            ),
            harness.gateway.calls,
        )
    }

    @Test
    fun `base content passes the snapshot and base path and returns the gateway bytes`() {
        val harness = SessionHarness()
        val basePath = ROOT.resolve("old.cs")

        val bytes = harness.session.loadBaseContent(snapshot(), basePath, CANCELLATION)

        assertContentEquals(BYTES, bytes)
        assertEquals(
            listOf(GatewayCall("baseContent", listOf(WORKSPACE, STATUS, basePath, CANCELLATION))),
            harness.gateway.calls,
        )
    }

    @Test
    fun `revision content passes the revision and returns the gateway bytes`() {
        val harness = SessionHarness()
        val revision = revision()

        val bytes = harness.session.loadRevisionContent(revision, CANCELLATION)

        assertContentEquals(BYTES, bytes)
        assertEquals(listOf(GatewayCall("revisionContent", listOf(revision, CANCELLATION))), harness.gateway.calls)
    }

    @Test
    fun `file history passes the normalized path and requested limit`() {
        val harness = SessionHarness()
        val unnormalized = ROOT.resolve("Source").resolve("..").resolve("file.cs")

        val page = harness.session.loadFileHistory(unnormalized, CANCELLATION, limit = 200)

        assertSame(HISTORY, page)
        assertEquals(
            listOf(
                GatewayCall("discoverWorkspace", listOf(FILE, CANCELLATION)),
                GatewayCall("fileHistory", listOf(WORKSPACE, FILE, PlasticHistoryRequest(200), CANCELLATION)),
            ),
            harness.gateway.calls,
        )
    }

    @Test
    fun `file history uses the default limit when none is given`() {
        val harness = SessionHarness()

        harness.session.loadFileHistory(FILE, CANCELLATION)

        val historyCall = harness.gateway.calls.single { call -> call.operation == "fileHistory" }
        assertEquals(PlasticHistoryRequest(PlasticHistoryRequest.DEFAULT_LIMIT), historyCall.arguments[2])
    }

    @Test
    fun `file history rejects workspace metadata without asking for history`() {
        val harness = SessionHarness()
        val metadata = ROOT.resolve(".plastic").resolve("plastic.workspace")

        val exception = assertFailsWith<VcsException> { harness.session.loadFileHistory(metadata, CANCELLATION) }

        assertEquals("Plastic history is unavailable for workspace metadata.", exception.message)
        assertEquals(listOf("discoverWorkspace"), harness.gateway.calls.map(GatewayCall::operation))
    }

    @Test
    fun `file history rejects a path outside any workspace`() {
        val harness = SessionHarness()
        harness.gateway.lookup = success(PlasticWorkspaceLookup.NotFound)

        val exception = assertFailsWith<VcsException> { harness.session.loadFileHistory(FILE, CANCELLATION) }

        assertEquals("The selected path is not in a Plastic workspace.", exception.message)
        assertEquals(listOf("discoverWorkspace"), harness.gateway.calls.map(GatewayCall::operation))
    }

    @Test
    fun `add and undo use the discovered workspace and the given paths`() {
        val harness = SessionHarness()
        val paths = listOf(FILE, ROOT.resolve("other.cs"))

        harness.session.addPaths(ROOT, paths, CANCELLATION)
        harness.session.undoPaths(ROOT, paths, CANCELLATION)

        assertEquals(
            listOf(
                GatewayCall("discoverWorkspace", listOf(ROOT, CANCELLATION)),
                GatewayCall("add", listOf(WORKSPACE, paths, CANCELLATION)),
                GatewayCall("discoverWorkspace", listOf(ROOT, CANCELLATION)),
                GatewayCall("undo", listOf(WORKSPACE, paths, CANCELLATION)),
            ),
            harness.gateway.calls,
        )
    }

    @Test
    fun `add and undo fail when Plastic discovers a different workspace root`() {
        val harness = SessionHarness()
        val otherWorkspace = WORKSPACE.copy(root = ROOT.resolveSibling("other-workspace"))
        harness.gateway.lookup = success(PlasticWorkspaceLookup.Found(otherWorkspace))

        val addFailure = assertFailsWith<VcsException> { harness.session.addPaths(ROOT, listOf(FILE), CANCELLATION) }
        val undoFailure = assertFailsWith<VcsException> { harness.session.undoPaths(ROOT, listOf(FILE), CANCELLATION) }

        assertEquals("Plastic returned a different workspace root.", addFailure.message)
        assertEquals("Plastic returned a different workspace root.", undoFailure.message)
        assertEquals(listOf("discoverWorkspace", "discoverWorkspace"), harness.gateway.calls.map(GatewayCall::operation))
    }

    @Test
    fun `add and undo fail when no workspace is found`() {
        val harness = SessionHarness()
        harness.gateway.lookup = success(PlasticWorkspaceLookup.NotFound)

        val addFailure = assertFailsWith<VcsException> { harness.session.addPaths(ROOT, listOf(FILE), CANCELLATION) }
        val undoFailure = assertFailsWith<VcsException> { harness.session.undoPaths(ROOT, listOf(FILE), CANCELLATION) }

        assertEquals("The selected path is not in a Plastic workspace.", addFailure.message)
        assertEquals("The selected path is not in a Plastic workspace.", undoFailure.message)
        assertEquals(listOf("discoverWorkspace", "discoverWorkspace"), harness.gateway.calls.map(GatewayCall::operation))
    }

    @Test
    fun `close shuts a created gateway exactly once`() {
        val harness = SessionHarness()
        harness.session.loadStatus(FILE, CANCELLATION)

        harness.session.close()
        harness.session.close()

        assertEquals(1, harness.gateway.closeCount)
    }

    @Test
    fun `close without a created gateway creates and closes nothing`() {
        val harness = SessionHarness()

        harness.session.close()

        assertEquals(0, harness.creations)
        assertEquals(0, harness.gateway.closeCount)
    }

    @Test
    fun `every call after close is cancelled without reaching the gateway`() {
        val harness = SessionHarness()
        harness.session.loadStatus(FILE, CANCELLATION)
        harness.session.close()
        val callsBeforeClose = harness.gateway.calls.size

        assertFailsWith<ProcessCanceledException> { harness.session.loadStatus(FILE, CANCELLATION) }
        assertFailsWith<ProcessCanceledException> { harness.session.loadBaseContent(snapshot(), FILE, CANCELLATION) }
        assertFailsWith<ProcessCanceledException> { harness.session.loadFileHistory(FILE, CANCELLATION) }
        assertFailsWith<ProcessCanceledException> { harness.session.loadRevisionContent(revision(), CANCELLATION) }
        assertFailsWith<ProcessCanceledException> { harness.session.addPaths(ROOT, listOf(FILE), CANCELLATION) }
        assertFailsWith<ProcessCanceledException> { harness.session.undoPaths(ROOT, listOf(FILE), CANCELLATION) }

        assertEquals(1, harness.creations)
        assertEquals(callsBeforeClose, harness.gateway.calls.size)
    }

    @Test
    fun `a disposed project is cancelled without creating a gateway`() {
        val harness = SessionHarness(isProjectDisposed = true)

        assertFailsWith<ProcessCanceledException> { harness.session.loadStatus(FILE, CANCELLATION) }

        assertEquals(0, harness.creations)
        assertTrue(harness.gateway.calls.isEmpty())
    }

    private class SessionHarness(
        creation: PlasticResult<PlasticGateway>? = null,
        isProjectDisposed: Boolean = false,
        isDispatchThread: Boolean = false,
    ) {
        val gateway = FakePlasticGateway()
        var creations = 0
            private set

        val session = PlasticGatewaySession(
            createGateway = {
                creations++
                creation ?: success(gateway)
            },
            isProjectDisposed = { isProjectDisposed },
            isDispatchThread = { isDispatchThread },
        )
    }

    private data class GatewayCall(
        val operation: String,
        val arguments: List<Any?>,
    )

    /** Returns configured results and records every call it receives. */
    private class FakePlasticGateway : PlasticGateway {
        var lookup: PlasticResult<PlasticWorkspaceLookup> = success(PlasticWorkspaceLookup.Found(WORKSPACE))
        var statusResult: PlasticResult<PlasticWorkspaceStatus> = success(STATUS)
        var baseContentResult: PlasticResult<ByteArray> = success(BYTES)
        var historyResult: PlasticResult<PlasticHistoryPage> = success(HISTORY)
        var revisionContentResult: PlasticResult<ByteArray> = success(BYTES)
        var addResult: PlasticResult<Unit> = success(Unit)
        var undoResult: PlasticResult<Unit> = success(Unit)
        val calls = ArrayList<GatewayCall>()
        var closeCount = 0
            private set

        override fun discoverWorkspace(
            directory: Path,
            cancellation: PlasticCancellation,
        ): PlasticResult<PlasticWorkspaceLookup> = record("discoverWorkspace", lookup, directory, cancellation)

        override fun status(
            workspace: PlasticWorkspace,
            scope: Path,
            cancellation: PlasticCancellation,
            includePrivateFiles: Boolean,
        ): PlasticResult<PlasticWorkspaceStatus> = record("status", statusResult, workspace, scope, cancellation, includePrivateFiles)

        override fun baseContent(
            workspace: PlasticWorkspace,
            status: PlasticWorkspaceStatus,
            basePath: Path,
            cancellation: PlasticCancellation,
        ): PlasticResult<ByteArray> = record("baseContent", baseContentResult, workspace, status, basePath, cancellation)

        override fun add(
            workspace: PlasticWorkspace,
            paths: Collection<Path>,
            cancellation: PlasticCancellation,
        ): PlasticResult<Unit> = record("add", addResult, workspace, paths, cancellation)

        override fun undo(
            workspace: PlasticWorkspace,
            paths: Collection<Path>,
            cancellation: PlasticCancellation,
        ): PlasticResult<Unit> = record("undo", undoResult, workspace, paths, cancellation)

        override fun fileHistory(
            workspace: PlasticWorkspace,
            filePath: Path,
            request: PlasticHistoryRequest,
            cancellation: PlasticCancellation,
        ): PlasticResult<PlasticHistoryPage> = record("fileHistory", historyResult, workspace, filePath, request, cancellation)

        override fun revisionContent(
            revision: PlasticHistoryRevision,
            cancellation: PlasticCancellation,
        ): PlasticResult<ByteArray> = record("revisionContent", revisionContentResult, revision, cancellation)

        override fun invalidateCaches() {
            calls.add(GatewayCall("invalidateCaches", emptyList()))
        }

        override fun close() {
            closeCount++
        }

        private fun <T> record(
            operation: String,
            result: T,
            vararg arguments: Any?,
        ): T {
            calls.add(GatewayCall(operation, arguments.toList()))
            return result
        }
    }

    private companion object {
        val ROOT: Path = Path.of("build", "tmp", "synthetic-workspace").toAbsolutePath().normalize()
        val FILE: Path = ROOT.resolve("file.cs")
        val CANCELLATION = PlasticCancellation { false }
        val BYTES = byteArrayOf(0, 1, 2, -1)
        val WORKSPACE = PlasticWorkspace(
            name = "synthetic",
            root = ROOT,
            machine = "synthetic-machine",
            id = UUID(0L, 1L),
            workspaceType = "regular",
            isDynamic = false,
        )
        val STATUS = PlasticWorkspaceStatus(
            workspaceChangeset = 42,
            repository = "synthetic-repository",
            server = "synthetic-server",
            changes = emptyList(),
        )
        val HISTORY = PlasticHistoryPage(revisions = emptyList(), hasMore = false)
        val DIAGNOSTIC = PlasticDiagnostic(
            operation = PlasticOperation.STATUS,
            origin = PlasticDiagnosticOrigin.PRECHECK,
            outcome = PlasticDiagnosticOutcome.SUCCESS,
        )

        fun <T> success(value: T): PlasticResult<T> = PlasticResult.Success(value, DIAGNOSTIC)

        fun failure(reason: PlasticFailure): PlasticResult.Failure = PlasticResult.Failure(reason, DIAGNOSTIC)

        fun snapshot(): PlasticStatusSnapshot = PlasticStatusSnapshot(WORKSPACE, STATUS)

        fun revision(): PlasticHistoryRevision =
            PlasticHistoryRevision(
                revisionSpec = "synthetic.cs#cs:10",
                branch = "/main",
                createdAt = OffsetDateTime.parse("2026-01-01T12:00:00Z"),
                entryKind = PlasticHistoryEntryKind.CONTENT_REVISION,
                revisionType = PlasticRevisionType("txt"),
                changeset = PlasticHistoryChangeset.Number(10),
                owner = "tester",
                comment = "",
                repository = "synthetic-repository",
                server = "synthetic-server",
                dataStatus = PlasticRevisionDataStatus("Available"),
                itemPathOrSpec = "synthetic.cs",
                itemId = 7,
                sizeBytes = 4,
                hash = null,
                hashAlgorithm = null,
            )
    }
}
