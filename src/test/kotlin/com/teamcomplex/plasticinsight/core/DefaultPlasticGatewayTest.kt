package com.teamcomplex.plasticinsight.core

import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import java.time.OffsetDateTime
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DefaultPlasticGatewayTest {
    @Test
    fun `workspace lookup without a marker is a fast not-found success`() {
        val runner = RecordingRunner()
        val gateway = gateway(runner, markerPresent = false)

        val result = assertIs<PlasticResult.Success<PlasticWorkspaceLookup>>(gateway.discoverWorkspace(sampleRoot))

        assertIs<PlasticWorkspaceLookup.NotFound>(result.value)
        assertEquals(PlasticDiagnosticOrigin.PRECHECK, result.diagnostic.origin)
        assertEquals(PlasticDiagnosticOutcome.NOT_FOUND, result.diagnostic.outcome)
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun `workspace discovery caches only a validated matching root and can be invalidated`() {
        val output = workspaceDiscoveryOutput().toByteArray()
        val runner = RecordingRunner(success(output), success(output))
        val gateway = gateway(runner)

        val first = assertIs<PlasticWorkspaceLookup.Found>(
            assertIs<PlasticResult.Success<PlasticWorkspaceLookup>>(gateway.discoverWorkspace(sampleRoot.resolve("nested"))).value,
        )
        val cached = assertIs<PlasticResult.Success<PlasticWorkspaceLookup>>(
            gateway.discoverWorkspace(sampleRoot.resolve("other")),
        )

        assertEquals(sampleWorkspace, first.workspace)
        assertEquals(PlasticDiagnosticOrigin.CACHE, cached.diagnostic.origin)
        assertEquals(1, runner.invocations.size)

        gateway.invalidateCaches()
        gateway.discoverWorkspace(sampleRoot)

        assertEquals(2, runner.invocations.size)
    }

    @Test
    fun `status never parses timeout cancellation truncation nonzero or malformed output as clean`() {
        val cases = listOf(
            processResult(timedOut = true) to PlasticFailure.TimedOut,
            processResult(cancelled = true) to PlasticFailure.Cancelled,
            processResult(standardOutputTruncated = true) to
                PlasticFailure.OutputLimitExceeded(standardOutput = true, standardError = false),
            processResult(exitCode = 7) to PlasticFailure.CommandFailed(7),
            success("not framed".toByteArray()) to PlasticFailure.MalformedOutput,
        )

        for ((processResult, expectedFailure) in cases) {
            val gateway = gateway(RecordingRunner(processResult))
            val result = assertIs<PlasticResult.Failure>(
                gateway.status(sampleWorkspace, sampleRoot.resolve("file.cs")),
            )

            assertEquals(expectedFailure, result.reason)
        }
    }

    @Test
    fun `launch errors become typed failures without retaining exception details`() {
        val runner = PlasticProcessRunner { throw IOException("private path must not escape") }
        val gateway = gateway(runner)

        val result = assertIs<PlasticResult.Failure>(
            gateway.status(sampleWorkspace, sampleRoot.resolve("file.cs")),
        )

        assertIs<PlasticFailure.LaunchFailed>(result.reason)
        assertEquals(PlasticDiagnosticOutcome.FAILED, result.diagnostic.outcome)
    }

    @Test
    fun `status rejects every current or old path outside the workspace`() {
        val outsideStatus = (
            "\u001ESTATUS\u001F42\u001FSample Repository\u001Fexample@cloud\u001D" +
                "\u001ECH\u001FD:\\outside\\file.cs\u001FFalse\u001F1\u001FNO_MERGES\u001D"
            ).toByteArray()
        val gateway = gateway(RecordingRunner(success(outsideStatus)))

        val result = assertIs<PlasticResult.Failure>(
            gateway.status(sampleWorkspace, sampleRoot.resolve("file.cs")),
        )

        assertIs<PlasticFailure.MalformedOutput>(result.reason)
    }

    @Test
    fun `workspace-root status uses the bounded controlled-change contract`() {
        val output = (
            "\u001ESTATUS\u001F42\u001FSample Repository\u001Fexample@cloud\u001D" +
                "\u001ECH\u001F${sampleRoot.resolve("file.cs")}\u001FFalse\u001F7\u001FNO_MERGES\u001D"
            ).toByteArray()
        val runner = RecordingRunner(success(output))
        val gateway = gateway(runner)

        val result = assertIs<PlasticResult.Success<PlasticWorkspaceStatus>>(
            gateway.status(sampleWorkspace, sampleRoot),
        )

        assertEquals(1, result.value.changes.size)
        val invocation = runner.invocations.single()
        assertEquals(sampleRoot.toString(), invocation.arguments[1])
        assertEquals(1024 * 1024, invocation.standardOutputLimitBytes)
        assertTrue("--private" !in invocation.arguments)
    }

    @Test
    fun `private status uses cut-ignored discovery for child and root scopes`() {
        val file = sampleRoot.resolve("new.cs")
        val output = (
            "\u001ESTATUS\u001F42\u001FSample Repository\u001Fexample@cloud\u001D\r\n" +
                "\u001EPR\u001F$file\u001FFalse\u001F-1\u001FNO_MERGES\u001D"
            ).toByteArray()
        val runner = RecordingRunner(success(output))
        val gateway = gateway(runner)

        val result = assertIs<PlasticResult.Success<PlasticWorkspaceStatus>>(
            gateway.status(sampleWorkspace, file, includePrivateFiles = true),
        )

        assertEquals(setOf(PlasticStatusCode.PRIVATE), result.value.changes.single().codes)
        assertTrue("--private" in runner.invocations.single().arguments)
        assertTrue("--ignored" in runner.invocations.single().arguments)
        assertTrue("--cutignored" in runner.invocations.single().arguments)

        val ignored = sampleRoot.resolve("generated.tmp")
        val rootOutput = (
            "\u001ESTATUS\u001F42\u001FSample Repository\u001Fexample@cloud\u001D\r\n" +
                "\u001EPR\u001F$file\u001FFalse\u001F-1\u001FNO_MERGES\u001D\r\n" +
                "\u001EIG\u001F$ignored\u001FFalse\u001F-1\u001FNO_MERGES\u001D"
            ).toByteArray()
        val rootRunner = RecordingRunner(success(rootOutput))
        val rootResult = assertIs<PlasticResult.Success<PlasticWorkspaceStatus>>(
            gateway(rootRunner).status(sampleWorkspace, sampleRoot, includePrivateFiles = true),
        )
        assertEquals(
            listOf(setOf(PlasticStatusCode.PRIVATE), setOf(PlasticStatusCode.IGNORED)),
            rootResult.value.changes.map(PlasticPendingChange::codes),
        )
        assertEquals(1024 * 1024, rootRunner.invocations.single().standardOutputLimitBytes)
        assertTrue("--cutignored" in rootRunner.invocations.single().arguments)
    }

    @Test
    fun `base content uses explicit path and workspace changeset while caching defensive copies`() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val expected = raw.copyOf()
        val runner = RecordingRunner(success(raw))
        val gateway = gateway(runner)
        val status = sampleStatus()
        val basePath = sampleRoot.resolve("old.cs")

        val first = assertIs<PlasticResult.Success<ByteArray>>(gateway.baseContent(sampleWorkspace, status, basePath))
        assertContentEquals(expected, first.value)
        first.value[0] = 99
        val cached = assertIs<PlasticResult.Success<ByteArray>>(gateway.baseContent(sampleWorkspace, status, basePath))

        assertContentEquals(expected, cached.value)
        assertEquals(1, runner.invocations.size)
        assertEquals(
            listOf("cm.exe", "cat", "${sampleRoot.resolve("old.cs")}#cs:42", "--raw"),
            runner.invocations.single().commandLine(),
        )
    }

    @Test
    fun `workspace mutations are exact typed and invalidate prior caches`() {
        val discovery = workspaceDiscoveryOutput().toByteArray()
        val runner = RecordingRunner(success(discovery), success(byteArrayOf()), success(discovery), success(byteArrayOf()))
        val gateway = gateway(runner)
        val file = sampleRoot.resolve("new file.cs")

        assertIs<PlasticWorkspaceLookup.Found>(
            assertIs<PlasticResult.Success<PlasticWorkspaceLookup>>(gateway.discoverWorkspace(sampleRoot)).value,
        )
        assertIs<PlasticResult.Success<Unit>>(gateway.add(sampleWorkspace, listOf(file)))
        assertIs<PlasticWorkspaceLookup.Found>(
            assertIs<PlasticResult.Success<PlasticWorkspaceLookup>>(gateway.discoverWorkspace(sampleRoot)).value,
        )
        assertIs<PlasticResult.Success<Unit>>(gateway.undo(sampleWorkspace, listOf(file)))

        assertEquals(listOf("add", "undo"), runner.invocations.mapNotNull { invocation ->
            invocation.arguments.firstOrNull()?.takeIf { it == "add" || it == "undo" }
        })
        assertEquals(4, runner.invocations.size)
    }

    @Test
    fun `failed mutation remains typed and clears caches for possible partial changes`() {
        val discovery = workspaceDiscoveryOutput().toByteArray()
        val runner = RecordingRunner(success(discovery), processResult(exitCode = 9), success(discovery))
        val gateway = gateway(runner)
        val file = sampleRoot.resolve("new file.cs")

        gateway.discoverWorkspace(sampleRoot)
        val failed = assertIs<PlasticResult.Failure>(gateway.add(sampleWorkspace, listOf(file)))
        gateway.discoverWorkspace(sampleRoot)

        assertEquals(PlasticFailure.CommandFailed(9), failed.reason)
        assertEquals(3, runner.invocations.size)
    }

    @Test
    fun `history returns a bounded newest-first page with explicit more state and caches it`() {
        val runner = RecordingRunner(success(fixture("/fixtures/history/representative.xml")))
        val gateway = gateway(runner)
        val file = Path.of("C:\\samples\\Source Ω\\File & Name.cs")

        val first = assertIs<PlasticResult.Success<PlasticHistoryPage>>(
            gateway.fileHistory(sampleWorkspace, file, PlasticHistoryRequest(limit = 3)),
        )
        val cached = assertIs<PlasticResult.Success<PlasticHistoryPage>>(
            gateway.fileHistory(sampleWorkspace, file, PlasticHistoryRequest(limit = 3)),
        )

        assertEquals(listOf("12", "11", "CO"), first.value.revisions.map { it.changeset.displayValue })
        assertTrue(first.value.hasMore)
        assertEquals(PlasticDiagnosticOrigin.CACHE, cached.diagnostic.origin)
        assertEquals("--limit=4", runner.invocations.single().arguments.last())
    }

    @Test
    fun `history expansion replaces the cached prefix and reuses it for smaller views`() {
        val history = fixture("/fixtures/history/representative.xml")
        val runner = RecordingRunner(success(history), success(history))
        val gateway = gateway(runner)
        val file = Path.of("C:\\samples\\Source Ω\\File & Name.cs")

        val initial = assertIs<PlasticResult.Success<PlasticHistoryPage>>(
            gateway.fileHistory(sampleWorkspace, file, PlasticHistoryRequest(limit = 2)),
        )
        val expanded = assertIs<PlasticResult.Success<PlasticHistoryPage>>(
            gateway.fileHistory(sampleWorkspace, file, PlasticHistoryRequest(limit = 4)),
        )
        val reused = assertIs<PlasticResult.Success<PlasticHistoryPage>>(
            gateway.fileHistory(sampleWorkspace, file, PlasticHistoryRequest(limit = 3)),
        )

        assertEquals(2, initial.value.revisions.size)
        assertEquals(4, expanded.value.revisions.size)
        assertEquals(3, reused.value.revisions.size)
        assertTrue(reused.value.hasMore)
        assertEquals(PlasticDiagnosticOrigin.CACHE, reused.diagnostic.origin)
        assertEquals(listOf("--limit=3", "--limit=5"), runner.invocations.map { it.arguments.last() })
    }

    @Test
    fun `pending move history retries through the old server path and reuses it for expansion`() {
        val currentPath = sampleRoot.resolve("New Folder").resolve("Moved.cs")
        val oldPath = sampleRoot.resolve("Old Folder").resolve("Moved.cs")
        val itemSpec =
            "serverpath:/Old Folder/Moved.cs#cs:42@Sample Repository@example@cloud"
        val moveStatus = (
            "\u001ESTATUS\u001F42\u001FSample Repository\u001Fexample@cloud\u001D" +
                "\u001ELM\u001F100%\u001F$oldPath\u001F$currentPath\u001FFalse\u001F501\u001FNO_MERGES\u001D"
            ).toByteArray()
        val history = fixture("/fixtures/history/representative.xml")
            .toString(Charsets.UTF_8)
            .replace(
                "<ItemName>C:\\samples\\Source Ω\\File &amp; Name.cs</ItemName>",
                "<ItemName>$itemSpec</ItemName>",
            )
            .toByteArray()
        val runner = RecordingRunner(
            processResult(exitCode = 1),
            success(moveStatus),
            success(history),
            success(history),
        )
        val gateway = gateway(runner)

        val initial = assertIs<PlasticResult.Success<PlasticHistoryPage>>(
            gateway.fileHistory(sampleWorkspace, currentPath, PlasticHistoryRequest(limit = 2)),
        )
        val expanded = assertIs<PlasticResult.Success<PlasticHistoryPage>>(
            gateway.fileHistory(sampleWorkspace, currentPath, PlasticHistoryRequest(limit = 4)),
        )

        assertEquals(2, initial.value.revisions.size)
        assertEquals(4, expanded.value.revisions.size)
        assertEquals(listOf("history", "status", "history", "history"), runner.invocations.map { it.arguments[0] })
        assertEquals(currentPath.toString(), runner.invocations[0].arguments[1])
        assertEquals(itemSpec, runner.invocations[2].arguments[1])
        assertEquals(itemSpec, runner.invocations[3].arguments[1])
        assertEquals(listOf("--limit=3", "--limit=5"), listOf(
            runner.invocations[2].arguments.last(),
            runner.invocations[3].arguments.last(),
        ))
    }

    @Test
    fun `maximum history view keeps its lookahead inside the parser bound`() {
        val runner = RecordingRunner(success(fixture("/fixtures/history/representative.xml")))
        val gateway = gateway(runner)

        gateway.fileHistory(
            sampleWorkspace,
            Path.of("C:\\samples\\Source Ω\\File & Name.cs"),
            PlasticHistoryRequest(limit = PlasticHistoryRequest.MAX_LIMIT),
        )

        assertEquals("--limit=1000", runner.invocations.single().arguments.last())
    }

    @Test
    fun `revision content resolves the historical path and caches defensive bytes`() {
        val raw = byteArrayOf(0, 0x80.toByte(), 0xff.toByte())
        val expected = raw.copyOf()
        val runner = RecordingRunner(
            success(fixture("/fixtures/historical-revision/one.xml")),
            success(raw),
        )
        val gateway = gateway(runner)
        val revision = sampleRevision()

        val first = assertIs<PlasticResult.Success<ByteArray>>(gateway.revisionContent(revision))
        assertContentEquals(expected, first.value)
        first.value[0] = 99
        val cached = assertIs<PlasticResult.Success<ByteArray>>(gateway.revisionContent(revision))

        assertContentEquals(expected, cached.value)
        assertEquals(2, runner.invocations.size)
        assertEquals("find", runner.invocations[0].arguments[0])
        assertEquals(
            "serverpath:/Source Ω/File & Name.cs#cs:42@Sample Repository@example@cloud",
            runner.invocations[1].arguments[1],
        )
    }

    @Test
    fun `revision lookup accepts a canonical server alias returned by cloud`() {
        val lookup = fixture("/fixtures/historical-revision/one.xml")
            .toString(Charsets.UTF_8)
            .replace("<REPSERVER>example@cloud</REPSERVER>", "<REPSERVER>Sample Organization@unity</REPSERVER>")
            .toByteArray()
        val runner = RecordingRunner(success(lookup), success(byteArrayOf(1, 2, 3)))
        val gateway = gateway(runner)

        assertIs<PlasticResult.Success<ByteArray>>(gateway.revisionContent(sampleRevision()))

        assertEquals(2, runner.invocations.size)
        assertEquals(
            "serverpath:/Source Ω/File & Name.cs#cs:42@Sample Repository@example@cloud",
            runner.invocations[1].arguments[1],
        )
    }

    @Test
    fun `revision content follows item identity to its historical path after a rename`() {
        val lookup = fixture("/fixtures/historical-revision/one.xml")
            .toString(Charsets.UTF_8)
            .replace("/Source Ω/File &amp; Name.cs", "/Legacy Folder/Old Name.cs")
            .toByteArray()
        val runner = RecordingRunner(success(lookup), success(byteArrayOf(1, 2, 3)))
        val gateway = gateway(runner)

        assertIs<PlasticResult.Success<ByteArray>>(gateway.revisionContent(sampleRevision()))

        assertEquals(
            "serverpath:/Legacy Folder/Old Name.cs#cs:42@Sample Repository@example@cloud",
            runner.invocations[1].arguments[1],
        )
    }

    @Test
    fun `ambiguous historical lookup never selects an arbitrary path`() {
        val single = fixture("/fixtures/historical-revision/one.xml").toString(Charsets.UTF_8)
        val revisionNode = single.substringAfter("<PLASTICQUERY>").substringBeforeLast("</PLASTICQUERY>")
        val ambiguous = "<PLASTICQUERY>$revisionNode$revisionNode</PLASTICQUERY>".toByteArray()
        val runner = RecordingRunner(success(ambiguous))
        val gateway = gateway(runner)

        val result = assertIs<PlasticResult.Failure>(gateway.revisionContent(sampleRevision()))

        assertIs<PlasticFailure.AmbiguousRevision>(result.reason)
        assertEquals(1, runner.invocations.size)
    }

    @Test
    fun `unsupported Plastic metadata becomes a typed execution failure`() {
        val lookup = fixture("/fixtures/historical-revision/one.xml")
            .toString(Charsets.UTF_8)
            .replace("/Source Ω/File &amp; Name.cs", "/Source Ω/File#Name.cs")
            .toByteArray()
        val runner = RecordingRunner(success(lookup))
        val gateway = gateway(runner)

        val result = assertIs<PlasticResult.Failure>(gateway.revisionContent(sampleRevision()))

        assertIs<PlasticFailure.ExecutionFailed>(result.reason)
        assertEquals(1, runner.invocations.size)
    }

    @Test
    fun `pre-cancellation and disposal do not run commands`() {
        val runner = RecordingRunner()
        val gateway = gateway(runner)
        val cancellation = PlasticCancellationSource().also { it.cancel() }

        val cancelled = assertIs<PlasticResult.Failure>(
            gateway.status(sampleWorkspace, sampleRoot.resolve("file.cs"), cancellation.token),
        )
        gateway.close()
        val disposed = assertIs<PlasticResult.Failure>(
            gateway.status(sampleWorkspace, sampleRoot.resolve("file.cs")),
        )

        assertIs<PlasticFailure.Cancelled>(cancelled.reason)
        assertIs<PlasticFailure.Disposed>(disposed.reason)
        assertTrue(runner.invocations.isEmpty())
    }

    @Test
    fun `invalidation prevents an in-flight result from repopulating the content cache`() {
        val runner = BlockingRunner(success(byteArrayOf(1, 2, 3)))
        val gateway = gateway(runner)
        val changed = PlasticPendingChange(
            codes = setOf(PlasticStatusCode.CHANGED),
            path = sampleRoot.resolve("changed.cs"),
            oldPath = null,
            isDirectory = false,
            revisionId = 7,
            similarityPercent = null,
        )
        val firstResult = AtomicReference<PlasticResult<ByteArray>>()
        val worker = Thread.ofPlatform().start {
            firstResult.set(gateway.baseContent(sampleWorkspace, sampleStatus(), changed.path))
        }

        assertTrue(runner.entered.await(5, TimeUnit.SECONDS))
        gateway.invalidateCaches()
        runner.release.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertIs<PlasticResult.Success<ByteArray>>(firstResult.get())
        assertIs<PlasticResult.Success<ByteArray>>(gateway.baseContent(sampleWorkspace, sampleStatus(), changed.path))
        assertEquals(2, runner.callCount.get())
    }

    private fun gateway(
        runner: PlasticProcessRunner,
        markerPresent: Boolean = true,
    ): DefaultPlasticGateway =
        DefaultPlasticGateway(
            cli = PlasticCli(runner, executable = "cm.exe"),
            historicalLookupDirectory = Path.of("C:\\outside-workspaces"),
            workspaceLocator = PlasticWorkspaceLocator { marker ->
                markerPresent && marker == sampleRoot.resolve(".plastic").resolve("plastic.workspace")
            },
        )

    private fun workspaceDiscoveryOutput(): String =
        listOf(
            sampleWorkspace.name,
            sampleWorkspace.root.toString(),
            sampleWorkspace.machine,
            sampleWorkspace.id.toString(),
            sampleWorkspace.workspaceType,
            "static",
        ).joinToString("\u001F")

    private fun sampleStatus(): PlasticWorkspaceStatus =
        PlasticWorkspaceStatus(
            workspaceChangeset = 42,
            repository = "Sample Repository",
            server = "example@cloud",
            changes = emptyList(),
        )

    private fun sampleRevision(): PlasticHistoryRevision =
        PlasticHistoryRevision(
            revisionSpec = "sample#cs:42",
            branch = "/main",
            createdAt = OffsetDateTime.parse("2025-01-02T03:04:05+01:00"),
            entryKind = PlasticHistoryEntryKind.CONTENT_REVISION,
            revisionType = PlasticRevisionType("txt"),
            changeset = PlasticHistoryChangeset.Number(42),
            owner = "developer@example.test",
            comment = "",
            repository = "Sample Repository",
            server = "example@cloud",
            dataStatus = PlasticRevisionDataStatus("Available"),
            itemPathOrSpec = "C:\\samples\\Source Ω\\File & Name.cs",
            itemId = 501,
            sizeBytes = 3,
            hash = "AQID",
            hashAlgorithm = "MD5",
        )

    private fun fixture(path: String): ByteArray = requireNotNull(javaClass.getResource(path)).readBytes()

    private class RecordingRunner(
        vararg results: PlasticProcessResult,
    ) : PlasticProcessRunner {
        private val results = ArrayDeque(results.toList())
        val invocations = ArrayList<PlasticInvocation>()

        override fun run(invocation: PlasticInvocation): PlasticProcessResult {
            invocations.add(invocation)
            return results.removeFirst()
        }
    }

    private class BlockingRunner(
        private val result: PlasticProcessResult,
    ) : PlasticProcessRunner {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callCount = AtomicInteger()

        override fun run(invocation: PlasticInvocation): PlasticProcessResult {
            if (callCount.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            return result
        }
    }

    private companion object {
        val sampleRoot: Path = Path.of("C:\\samples")
        val sampleWorkspace = PlasticWorkspace(
            name = "Sample Workspace",
            root = sampleRoot,
            machine = "BUILD-01",
            id = UUID.fromString("11111111-2222-4333-8444-555555555555"),
            workspaceType = "regular",
            isDynamic = false,
        )

        fun success(standardOutput: ByteArray): PlasticProcessResult =
            processResult(standardOutput = standardOutput)

        fun processResult(
            exitCode: Int? = 0,
            standardOutput: ByteArray = byteArrayOf(),
            timedOut: Boolean = false,
            cancelled: Boolean = false,
            standardOutputTruncated: Boolean = false,
        ): PlasticProcessResult =
            PlasticProcessResult(
                exitCode = if (timedOut || cancelled) null else exitCode,
                standardOutput = standardOutput,
                standardError = byteArrayOf(),
                duration = Duration.ofMillis(5),
                timedOut = timedOut,
                standardOutputTruncated = standardOutputTruncated,
                cancelled = cancelled,
            )
    }
}
